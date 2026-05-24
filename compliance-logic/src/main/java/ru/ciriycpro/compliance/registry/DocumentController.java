package ru.ciriycpro.compliance.registry;

import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.ciriycpro.compliance.storage.DocumentStorageService;
import ru.ciriycpro.compliance.storage.DocumentStorageService.StorageResult;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@RestController
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentRepository documentRepository;
    private final ClientRepository clientRepository;
    private final DocumentStorageService storageService;

    public DocumentController(DocumentRepository documentRepository,
                              ClientRepository clientRepository,
                              DocumentStorageService storageService) {
        this.documentRepository = documentRepository;
        this.clientRepository = clientRepository;
        this.storageService = storageService;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<DocumentResponse> upload(
            @RequestParam("client_id") UUID clientId,
            @RequestParam("type") DocumentType type,
            @RequestParam("source") DocumentSource source,
            @RequestParam(value = "historic", defaultValue = "false") boolean historic,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("client not found: " + clientId));

        byte[] content = file.getBytes();
        String sha256 = DocumentStorageService.computeSha256(content);

        if (documentRepository.existsByClientIdAndSha256(clientId, sha256)) {
            log.warn("document with sha256={} already exists for client={}", sha256, clientId);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
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
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
        } catch (Exception e) {
            log.error("failed to save document, deleting orphan file path={}", stored.filePath(), e);
            storageService.deleteBlob(stored.filePath());
            throw e;
        }
    }

    @GetMapping("/documents/{id}")
    public ResponseEntity<DocumentResponse> getById(@PathVariable UUID id) {
        return documentRepository.findById(id)
                .map(doc -> ResponseEntity.ok(toResponse(doc)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/clients/{clientId}/documents")
    public ResponseEntity<Page<DocumentResponse>> listForClient(
            @PathVariable UUID clientId,
            @RequestParam(required = false) DocumentType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Document> docs = (type != null)
                ? documentRepository.findByClientIdAndType(clientId, type, pageable)
                : documentRepository.findByClientId(clientId, pageable);
        return ResponseEntity.ok(docs.map(DocumentController::toResponse));
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) throws IOException {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();

        byte[] content = storageService.readBlob(doc.getFilePath());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream"));
        headers.setContentDispositionFormData("attachment", doc.getOriginalFilename());
        return new ResponseEntity<>(content, headers, HttpStatus.OK);
    }

    private static DocumentResponse toResponse(Document d) {
        return new DocumentResponse(
                d.getId(),
                d.getClient().getId(),
                d.getType().name(),
                d.getSource().name(),
                d.isHistoric(),
                d.getOriginalFilename(),
                d.getMimeType(),
                d.getSizeBytes(),
                d.getSha256(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }

    public record DocumentResponse(
            @NotNull UUID id,
            @NotNull UUID clientId,
            String type,
            String source,
            boolean historic,
            String originalFilename,
            String mimeType,
            Long sizeBytes,
            String sha256,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
