package ru.ciriycpro.compliance.registry;

import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.ciriycpro.compliance.service.DocumentService;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@RestController
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @RequestParam("client_id") UUID clientId,
            @RequestParam("type") DocumentType type,
            @RequestParam("source") DocumentSource source,
            @RequestParam(value = "historic", defaultValue = "false") boolean historic,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        DocumentService.CreateResult result = documentService.create(clientId, type, source, historic, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result.document()));
    }

    @GetMapping("/documents/{id}")
    public ResponseEntity<DocumentResponse> getById(@PathVariable UUID id) {
        return documentService.findById(id)
                .map(d -> ResponseEntity.ok(toResponse(d)))
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
        return ResponseEntity.ok(documentService.listForClient(clientId, type, pageable).map(DocumentController::toResponse));
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) throws IOException {
        Document doc = documentService.findById(id).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();

        byte[] content = documentService.readBlob(doc);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream"));
        headers.setContentDispositionFormData("attachment", doc.getOriginalFilename());
        return new ResponseEntity<>(content, headers, HttpStatus.OK);
    }

    private static DocumentResponse toResponse(Document d) {
        return new DocumentResponse(
                d.getId(), d.getClient().getId(), d.getType().name(), d.getSource().name(),
                d.isHistoric(), d.getOriginalFilename(), d.getMimeType(), d.getSizeBytes(),
                d.getSha256(), d.getCreatedAt(), d.getUpdatedAt()
        );
    }

    public record DocumentResponse(
            @NotNull UUID id, @NotNull UUID clientId, String type, String source,
            boolean historic, String originalFilename, String mimeType, Long sizeBytes,
            String sha256, Instant createdAt, Instant updatedAt
    ) {}
}
