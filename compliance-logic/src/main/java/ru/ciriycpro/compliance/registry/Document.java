package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Audited
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_client", columnList = "client_id"),
        @Index(name = "idx_documents_type", columnList = "type"),
        @Index(name = "idx_documents_sha256", columnList = "sha256"),
        @Index(name = "idx_documents_historic", columnList = "historic")
})
public class Document {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private DocumentType type;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private DocumentSource source;

    @Column(name = "historic", nullable = false)
    private boolean historic = false;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb")
    private Map<String, Object> attributes = new HashMap<>();

    @Column(name = "parsed_at")
    private Instant parsedAt;

    @Column(name = "classified_at")
    private Instant classifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }

    public DocumentType getType() { return type; }
    public void setType(DocumentType type) { this.type = type; }

    public DocumentSource getSource() { return source; }
    public void setSource(DocumentSource source) { this.source = source; }

    public boolean isHistoric() { return historic; }
    public void setHistoric(boolean historic) { this.historic = historic; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }

    public Instant getParsedAt() { return parsedAt; }
    public void setParsedAt(Instant parsedAt) { this.parsedAt = parsedAt; }

    public Instant getClassifiedAt() { return classifiedAt; }
    public void setClassifiedAt(Instant classifiedAt) { this.classifiedAt = classifiedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
