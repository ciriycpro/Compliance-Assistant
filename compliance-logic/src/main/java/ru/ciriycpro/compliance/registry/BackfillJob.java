package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * BackfillJob — batch-задача загрузки исторических документов.
 *
 * Создаётся при POST /admin/backfill. Tracking прогресса для:
 *  - Аудита: кто/когда/что импортировал
 *  - Retry/resume при ошибках (Phase 2)
 *  - UI прогресс-бара (Phase 3)
 *
 * Lifecycle:
 *   PENDING — job создан, файлы ещё не процессились
 *   RUNNING — батч-обработка идёт
 *   COMPLETED — все файлы обработаны (created + skipped + failed = total)
 *   FAILED — fatal exception в процессе обработки
 *
 * См. DEC-023 строки 164-180 (BackfillJob spec).
 */
@Audited
@Entity
@Table(name = "backfill_jobs",
    indexes = {
        @Index(name = "idx_backfill_jobs_client", columnList = "client_id"),
        @Index(name = "idx_backfill_jobs_status", columnList = "status"),
        @Index(name = "idx_backfill_jobs_started_at", columnList = "started_at")
    }
)
public class BackfillJob {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "source_path", nullable = false, length = 500)
    private String sourcePath;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BackfillJobStatus status = BackfillJobStatus.PENDING;

    @Column(name = "total_files", nullable = false)
    private int totalFiles = 0;

    @Column(name = "processed_files", nullable = false)
    private int processedFiles = 0;

    @Column(name = "created_documents", nullable = false)
    private int createdDocuments = 0;

    @Column(name = "skipped_documents", nullable = false)
    private int skippedDocuments = 0;

    @Column(name = "failed_documents", nullable = false)
    private int failedDocuments = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public BackfillJobStatus getStatus() { return status; }
    public void setStatus(BackfillJobStatus status) { this.status = status; }
    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
    public int getProcessedFiles() { return processedFiles; }
    public void setProcessedFiles(int processedFiles) { this.processedFiles = processedFiles; }
    public int getCreatedDocuments() { return createdDocuments; }
    public void setCreatedDocuments(int v) { this.createdDocuments = v; }
    public int getSkippedDocuments() { return skippedDocuments; }
    public void setSkippedDocuments(int v) { this.skippedDocuments = v; }
    public int getFailedDocuments() { return failedDocuments; }
    public void setFailedDocuments(int v) { this.failedDocuments = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
