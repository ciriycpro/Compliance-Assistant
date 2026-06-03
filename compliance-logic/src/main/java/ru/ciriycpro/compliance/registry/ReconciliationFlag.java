package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Флаг сверки — stateful-сущность (паттерн StatementGap).
 * v1: один открытый флаг на (client, counterparty, flag_type). Re-scan авто-закрывает
 * при появлении договора. Реестр отсутствующих договоров = открытые флаги. DEC-023 Коммит 4.
 */
@Audited
@Entity
@Table(name = "reconciliation_flags",
    indexes = {
        @Index(name = "idx_recflags_client", columnList = "client_id"),
        @Index(name = "idx_recflags_counterparty", columnList = "counterparty_id"),
        @Index(name = "idx_recflags_status", columnList = "status"),
        @Index(name = "idx_recflags_client_status", columnList = "client_id, status"),
        @Index(name = "idx_recflags_type", columnList = "flag_type")
    }
)
public class ReconciliationFlag {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counterparty_id", nullable = false)
    private Counterparty counterparty;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "flag_type", nullable = false, length = 30)
    private ReconciliationFlagType flagType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReconciliationFlagStatus status = ReconciliationFlagStatus.DETECTED;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "last_request_at")
    private Instant lastRequestAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "operation_count")
    private Integer operationCount;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "contract_ref", length = 100)
    private String contractRef;

    @Column(name = "details", length = 500)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        if (this.detectedAt == null) this.detectedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }
    public Counterparty getCounterparty() { return counterparty; }
    public void setCounterparty(Counterparty counterparty) { this.counterparty = counterparty; }
    public ReconciliationFlagType getFlagType() { return flagType; }
    public void setFlagType(ReconciliationFlagType flagType) { this.flagType = flagType; }
    public ReconciliationFlagStatus getStatus() { return status; }
    public void setStatus(ReconciliationFlagStatus status) { this.status = status; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
    public Instant getLastRequestAt() { return lastRequestAt; }
    public void setLastRequestAt(Instant lastRequestAt) { this.lastRequestAt = lastRequestAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Integer getOperationCount() { return operationCount; }
    public void setOperationCount(Integer operationCount) { this.operationCount = operationCount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public String getContractRef() { return contractRef; }
    public void setContractRef(String contractRef) { this.contractRef = contractRef; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
