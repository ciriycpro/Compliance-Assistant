package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.ciriycpro.compliance.registry.Client;
import ru.ciriycpro.compliance.registry.ClientRepository;
import ru.ciriycpro.compliance.registry.Document;
import ru.ciriycpro.compliance.registry.DocumentRepository;
import ru.ciriycpro.compliance.registry.DocumentSource;
import ru.ciriycpro.compliance.registry.DocumentType;
import ru.ciriycpro.compliance.storage.DocumentStorageService;
import ru.ciriycpro.compliance.storage.DocumentStorageService.StorageResult;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * DocumentService — orchestration документов: проверка клиента, дедуп по sha256,
 * запись blob в filesystem, сохранение метаданных в БД.
 *
 * Бизнес-инварианты:
 *  - Document уникален per (client_id, sha256) — защита от дублей
 *  - Storage пишется ПОСЛЕ проверки дубля (избегаем orphan-файлов)
 *  - При БД exception — orphan blob удаляется через storageService.deleteBlob (try/catch в Controller)
 *
 * См. DEC-023 Document storage strategy + DEC-017 Уровень 0 (input validation).
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final ClientRepository clientRepository;
    private final DocumentStorageService storageService;

    public DocumentService(DocumentRepository documentRepository,
                           ClientRepository clientRepository,
                           DocumentStorageService storageService) {
        this.documentRepository = documentRepository;
        this.clientRepository = clientRepository;
        this.storageService = storageService;
    }

    /**
     * Создание Document с загрузкой blob в filesystem.
     *
     * @return CreateResult с doc и stored — последний нужен Controller'у для orphan cleanup при exception
     */
    @Transactional
    /**
     * Создание Document из byte[] (для batch-импорта).
     * Возвращает null если документ с таким sha256 уже существует (для skip-логики backfill).
     */
    public Document createFromBytes(UUID clientId, String filename, String mimeType,
                                    byte[] content, DocumentType type, DocumentSource source,
                                    boolean historic) throws IOException {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));

        String sha256 = DocumentStorageService.computeSha256(content);

        if (documentRepository.existsByClientIdAndSha256(clientId, sha256)) {
            log.debug("backfill skip: document with sha256={} already exists for client={}", sha256, clientId);
            return null;
        }

        StorageResult stored = storageService.store(client.getInn(), type, filename, content);

        Document doc = new Document();
        doc.setClient(client);
        doc.setType(type);
        doc.setSource(source);
        doc.setHistoric(historic);
        doc.setFilePath(stored.filePath());
        doc.setSha256(stored.sha256());
        doc.setSizeBytes(stored.sizeBytes());
        doc.setMimeType(mimeType != null ? mimeType : "application/octet-stream");
        doc.setOriginalFilename(filename);

        try {
            Document saved = documentRepository.save(doc);
            log.info("document created via backfill id={} client_inn={} type={} sha256={}",
                    saved.getId(), client.getInn(), type, sha256);
            return saved;
        } catch (RuntimeException e) {
            log.error("failed to save document, deleting orphan blob filePath={}", stored.filePath(), e);
            storageService.deleteBlob(stored.filePath());
            throw e;
        }
    }

    public CreateResult create(UUID clientId, DocumentType type, DocumentSource source,
                               boolean historic, MultipartFile file) throws IOException {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));

        byte[] content = file.getBytes();
        String sha256 = DocumentStorageService.computeSha256(content);

        if (documentRepository.existsByClientIdAndSha256(clientId, sha256)) {
            log.warn("document with sha256={} already exists for client={}", sha256, clientId);
            throw new DuplicateDocumentException("document already exists for client=" + clientId + " sha256=" + sha256);
        }

        StorageResult stored = storageService.store(client.getInn(), type, file.getOriginalFilename(), content);

        Document doc = new Document();
        doc.setClient(client);
        doc.setType(type);
        doc.setSource(source);
        doc.setHistoric(historic);
        doc.setFilePath(stored.filePath());
        doc.setSha256(stored.sha256());
        doc.setSizeBytes(stored.sizeBytes());
        doc.setMimeType(file.getContentType());
        doc.setOriginalFilename(file.getOriginalFilename());

        try {
            Document saved = documentRepository.save(doc);
            log.info("document created id={} client_inn={} type={} historic={} sha256={}",
                    saved.getId(), client.getInn(), type, historic, sha256);
            return new CreateResult(saved, stored);
        } catch (RuntimeException e) {
            log.error("failed to save document, deleting orphan blob filePath={}", stored.filePath(), e);
            storageService.deleteBlob(stored.filePath());
            throw e;
        }
    }

    public Optional<Document> findById(UUID id) {
        return documentRepository.findById(id);
    }

    public Page<Document> listForClient(UUID clientId, DocumentType type, Pageable pageable) {
        if (type != null) {
            return documentRepository.findByClientIdAndType(clientId, type, pageable);
        }
        return documentRepository.findByClientId(clientId, pageable);
    }

    public byte[] readBlob(Document doc) throws IOException {
        return storageService.readBlob(doc.getFilePath());
    }

    public record CreateResult(Document document, StorageResult storedBlob) {}

    public static class DuplicateDocumentException extends RuntimeException {
        public DuplicateDocumentException(String message) { super(message); }
    }

    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String message) { super(message); }
    }
}
