package ru.ciriycpro.compliance.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ciriycpro.compliance.service.BackfillService;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin endpoints для batch backfill (DEC-023 v1.5).
 *
 * v1.0 — синхронная обработка. Клиент ждёт ответа пока все файлы загрузятся.
 *   Подходит для < 500 файлов в одном батче.
 *
 * v2.0 — асинхронная через @Async. POST возвращает 202 + jobId, прогресс через GET.
 */
@RestController
public class AdminBackfillController {

    private final BackfillService backfillService;
    private final BackfillJobRepository backfillJobRepository;

    public AdminBackfillController(BackfillService backfillService,
                                   BackfillJobRepository backfillJobRepository) {
        this.backfillService = backfillService;
        this.backfillJobRepository = backfillJobRepository;
    }

    /**
     * POST /admin/backfill — синхронный запуск batch-импорта.
     *
     * Body: BackfillRequestDTO (clientId, sourcePath, docType, source)
     * Returns: 201 + BackfillJobResponse (с финальными счётчиками)
     * Errors: 400 — backfill disabled / invalid source_path / client not found
     */
    @PostMapping("/admin/backfill")
    public ResponseEntity<BackfillJobResponse> startBackfill(@Valid @RequestBody BackfillRequestDTO dto) {
        if (!backfillService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        BackfillJob job = backfillService.createJob(
                dto.clientId(),
                dto.sourcePath(),
                dto.docType(),
                dto.source()
        );
        BackfillJob completed = backfillService.runJob(job.getId(), dto.docType(), dto.source());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(completed));
    }

    @GetMapping("/admin/backfill/{jobId}")
    public ResponseEntity<BackfillJobResponse> getJob(@PathVariable UUID jobId) {
        try {
            BackfillJob job = backfillService.findById(jobId);
            return ResponseEntity.ok(toResponse(job));
        } catch (BackfillService.BackfillJobNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/clients/{clientId}/backfill-jobs")
    public ResponseEntity<Page<BackfillJobResponse>> listForClient(
            @PathVariable UUID clientId,
            @RequestParam(required = false) BackfillJobStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<BackfillJob> jobs = (status != null)
                ? backfillJobRepository.findByClientIdAndStatus(clientId, status, pageable)
                : backfillJobRepository.findByClientId(clientId, pageable);
        return ResponseEntity.ok(jobs.map(AdminBackfillController::toResponse));
    }

    private static BackfillJobResponse toResponse(BackfillJob j) {
        return new BackfillJobResponse(
                j.getId(),
                j.getClient().getId(),
                j.getSourcePath(),
                j.getStatus().name(),
                j.getTotalFiles(),
                j.getProcessedFiles(),
                j.getCreatedDocuments(),
                j.getSkippedDocuments(),
                j.getFailedDocuments(),
                j.getStartedAt(),
                j.getCompletedAt(),
                j.getErrorMessage(),
                j.getCreatedAt(),
                j.getUpdatedAt()
        );
    }

    public record BackfillRequestDTO(
            @NotNull UUID clientId,
            @NotBlank String sourcePath,
            @NotNull DocumentType docType,
            @NotNull DocumentSource source
    ) {}

    public record BackfillJobResponse(
            UUID id,
            UUID clientId,
            String sourcePath,
            String status,
            int totalFiles,
            int processedFiles,
            int createdDocuments,
            int skippedDocuments,
            int failedDocuments,
            Instant startedAt,
            Instant completedAt,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
