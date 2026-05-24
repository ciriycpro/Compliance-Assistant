package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ciriycpro.compliance.registry.BackfillJob;
import ru.ciriycpro.compliance.registry.BackfillJobRepository;
import ru.ciriycpro.compliance.registry.BackfillJobStatus;
import ru.ciriycpro.compliance.registry.Client;
import ru.ciriycpro.compliance.registry.ClientRepository;
import ru.ciriycpro.compliance.registry.Document;
import ru.ciriycpro.compliance.registry.DocumentSource;
import ru.ciriycpro.compliance.registry.DocumentType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * BackfillService — batch-импорт исторических документов из локальной директории.
 *
 * Workflow:
 *   1. POST /admin/backfill создаёт BackfillJob (PENDING)
 *   2. Запускается batch-обработка (синхронная для v1.0):
 *      - переключает status → RUNNING
 *      - walk файлы в source_path
 *      - для каждого: вычисляет sha256, создаёт Document через DocumentService
 *      - HTTP 409 (дубль) → skipped++
 *      - exception → failed++ (но не останавливает job)
 *      - после всех: status → COMPLETED, completed_at = now
 *
 * Архитектурное замечание (DEC-023 v1.5):
 *   v1.0 — синхронная обработка в request thread (для коротких массивов, single-tenant)
 *   v2.0 — асинхронная через @Async + worker pool (для длинных батчей)
 *   v3.0 — оркестратор-side через orchestrator workflow (для production)
 *
 * Конфиг:
 *   backfill.enabled=true/false (можно отключить через env без передеплоя)
 *   backfill.batch-size=100 (сколько обрабатывать за раз для memory-friendly)
 *
 * См. DEC-023 строки 230-242 (POST /admin/backfill spec).
 */
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    private final BackfillJobRepository backfillJobRepository;
    private final ClientRepository clientRepository;
    private final DocumentService documentService;
    private final boolean enabled;

    public BackfillService(BackfillJobRepository backfillJobRepository,
                           ClientRepository clientRepository,
                           DocumentService documentService,
                           @Value("${backfill.enabled:true}") boolean enabled) {
        this.backfillJobRepository = backfillJobRepository;
        this.clientRepository = clientRepository;
        this.documentService = documentService;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Создание нового BackfillJob. Не запускает обработку — только регистрирует.
     */
    @Transactional
    public BackfillJob createJob(UUID clientId, String sourcePath, DocumentType docType, DocumentSource source) {
        if (!enabled) {
            throw new BackfillDisabledException("backfill is disabled via config (backfill.enabled=false)");
        }
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));

        validateSourcePath(sourcePath);

        Path sourceDir = Path.of(sourcePath);
        if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
            throw new InvalidSourcePathException("source path не существует или не директория: " + sourcePath);
        }

        BackfillJob job = new BackfillJob();
        job.setClient(client);
        job.setSourcePath(sourcePath);
        job.setStatus(BackfillJobStatus.PENDING);

        BackfillJob saved = backfillJobRepository.save(job);
        log.info("backfill job created job_id={} client_inn={} source_path={}",
                saved.getId(), client.getInn(), sourcePath);
        return saved;
    }

    /**
     * Синхронное выполнение BackfillJob.
     * 
     * REQUIRES_NEW транзакция — каждый Document создаётся в своей транзакции
     * чтобы один failed файл не откатил весь батч.
     */
    @Transactional
    public BackfillJob runJob(UUID jobId, DocumentType docType, DocumentSource source) {
        BackfillJob job = backfillJobRepository.findById(jobId)
                .orElseThrow(() -> new BackfillJobNotFoundException("job not found: " + jobId));

        if (job.getStatus() != BackfillJobStatus.PENDING) {
            throw new IllegalBackfillStateException("job уже в статусе " + job.getStatus() + ", не PENDING");
        }

        Client client = job.getClient();
        Path sourceDir = Path.of(job.getSourcePath());

        // Подсчёт total
        long totalFiles;
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            totalFiles = walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return markJobFailed(job, "failed to walk source dir: " + e.getMessage());
        }

        job.setTotalFiles((int) totalFiles);
        job.setStatus(BackfillJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job = backfillJobRepository.save(job);

        log.info("backfill job starting job_id={} client_inn={} total_files={} doc_type={} source={}",
                job.getId(), client.getInn(), totalFiles, docType, source);

        int created = 0;
        int skipped = 0;
        int failed = 0;

        // Обработка файлов
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (int i = 0; i < files.size(); i++) {
                Path file = files.get(i);
                try {
                    byte[] content = Files.readAllBytes(file);
                    String mime = Files.probeContentType(file);
                    if (mime == null) mime = "application/octet-stream";

                    Document result = documentService.createFromBytes(
                            client.getId(),
                            file.getFileName().toString(),
                            mime,
                            content,
                            docType,
                            source,
                            true  // historic=true для backfill
                    );
                    if (result == null) {
                        skipped++;  // дубль по sha256
                    } else {
                        created++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("backfill file failed file={} error={}", file, e.getMessage());
                }

                // Progress update каждые 10 файлов
                if ((i + 1) % 10 == 0 || (i + 1) == files.size()) {
                    job.setProcessedFiles(created + skipped + failed);
                    job.setCreatedDocuments(created);
                    job.setSkippedDocuments(skipped);
                    job.setFailedDocuments(failed);
                    backfillJobRepository.save(job);
                }
            }
        } catch (IOException e) {
            return markJobFailed(job, "failed during file processing: " + e.getMessage());
        }

        // Финальная фиксация
        job.setProcessedFiles(created + skipped + failed);
        job.setCreatedDocuments(created);
        job.setSkippedDocuments(skipped);
        job.setFailedDocuments(failed);
        job.setStatus(BackfillJobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        BackfillJob saved = backfillJobRepository.save(job);

        log.info("backfill job completed job_id={} client_inn={} created={} skipped={} failed={} duration_ms={}",
                saved.getId(), client.getInn(), created, skipped, failed,
                java.time.Duration.between(saved.getStartedAt(), saved.getCompletedAt()).toMillis());
        return saved;
    }

    private BackfillJob markJobFailed(BackfillJob job, String error) {
        job.setStatus(BackfillJobStatus.FAILED);
        job.setErrorMessage(error);
        job.setCompletedAt(Instant.now());
        BackfillJob saved = backfillJobRepository.save(job);
        log.error("backfill job FAILED job_id={} error={}", saved.getId(), error);
        return saved;
    }

    public BackfillJob findById(UUID jobId) {
        return backfillJobRepository.findById(jobId)
                .orElseThrow(() -> new BackfillJobNotFoundException("job not found: " + jobId));
    }

    /**
     * Whitelist для source_path — защита от path traversal.
     * Разрешённый префикс: /var/lib/compliance-files/import
     */
    private void validateSourcePath(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new InvalidSourcePathException("source path обязателен");
        }
        Path normalized;
        try {
            normalized = Path.of(sourcePath).toRealPath();
        } catch (IOException e) {
            throw new InvalidSourcePathException("path не существует или недоступен: " + sourcePath);
        }
        String normStr = normalized.toString();
        boolean allowed = normStr.startsWith("/var/lib/compliance-files/import");
        if (!allowed) {
            throw new InvalidSourcePathException(
                "source path должен быть под /var/lib/compliance-files/import, получено: " + normStr);
        }
        log.info("source path validated: {}", normStr);
    }


    public static class BackfillDisabledException extends RuntimeException {
        public BackfillDisabledException(String message) { super(message); }
    }
    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String message) { super(message); }
    }
    public static class InvalidSourcePathException extends RuntimeException {
        public InvalidSourcePathException(String message) { super(message); }
    }
    public static class BackfillJobNotFoundException extends RuntimeException {
        public BackfillJobNotFoundException(String message) { super(message); }
    }
    public static class IllegalBackfillStateException extends RuntimeException {
        public IllegalBackfillStateException(String message) { super(message); }
    }
}
