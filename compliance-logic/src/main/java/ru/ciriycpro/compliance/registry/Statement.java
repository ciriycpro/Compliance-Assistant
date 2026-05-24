package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Банковская выписка — специализация Document.
 *
 * Архитектурно: Document держит file_path/sha256/mime_type/attributes JSONB.
 * Statement держит ТОЛЬКО банковскую специфику (период, банк, агрегаты).
 *
 * См. DEC-023 Document master entity strategy.
 */
@Audited
@Entity
@Table(name = "statements",
    indexes = {
        @Index(name = "idx_statements_document", columnList = "document_id"),
        @Index(name = "idx_statements_client", columnList = "client_id"),
        @Index(name = "idx_statements_bank", columnList = "counterparty_id"),
        @Index(name = "idx_statements_period", columnList = "period_start, period_end"),
        @Index(name = "idx_statements_status", columnList = "status")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_statements_document", columnNames = {"document_id"})
    }
)
public class Statement {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counterparty_id", nullable = false)
    private Counterparty bank;

    @Column(name = "bank_name", nullable = false, length = 200)
    private String bankName;

    @NotNull
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @NotNull
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "source_message_id", length = 500)
    private String sourceMessageId;

    @Column(name = "amount_total", precision = 18, scale = 2)
    private BigDecimal amountTotal;

    @Column(name = "operation_count")
    private Integer operationCount;

    @Column(name = "currency", length = 3)
    private String currency;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatementStatus status = StatementStatus.RECEIVED;

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

    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }

    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }

    public Counterparty getBank() { return bank; }
    public void setBank(Counterparty bank) { this.bank = bank; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }

    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }

    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String sourceMessageId) { this.sourceMessageId = sourceMessageId; }

    public BigDecimal getAmountTotal() { return amountTotal; }
    public void setAmountTotal(BigDecimal amountTotal) { this.amountTotal = amountTotal; }

    public Integer getOperationCount() { return operationCount; }
    public void setOperationCount(Integer operationCount) { this.operationCount = operationCount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public StatementStatus getStatus() { return status; }
    public void setStatus(StatementStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
