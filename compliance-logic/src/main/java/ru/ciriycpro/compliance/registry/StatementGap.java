package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * StatementGap — обнаруженный пробел в банковских выписках клиента.
 *
 * Inspector детектит пробелы: для каждого (client_id, bank_id) сортирует
 * Statements по period_start и ищет gap между period_end[i] и period_start[i+1].
 *
 * Жизненный цикл:
 *  - DETECTED — Inspector обнаружил, ничего не сделано
 *  - REQUEST_SENT — Scheduler отправил запрос Таирову (коммит 6)
 *  - RECEIVED — клиент прислал выписку, gap частично закрыт
 *  - CLOSED — Statement полностью покрывает gap_start..gap_end
 *
 * См. DEC-023 строки 104-111 + Inspector v1.0.
 */
@Audited
@Entity
@Table(name = "statement_gaps",
    indexes = {
        @Index(name = "idx_statement_gaps_client", columnList = "client_id"),
        @Index(name = "idx_statement_gaps_bank", columnList = "counterparty_id"),
        @Index(name = "idx_statement_gaps_status", columnList = "status"),
        @Index(name = "idx_statement_gaps_detected_at", columnList = "detected_at"),
        @Index(name = "idx_statement_gaps_gap_period", columnList = "gap_start, gap_end")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_statement_gaps_client_bank_period",
                columnNames = {"client_id", "counterparty_id", "gap_start", "gap_end"})
    }
)
public class StatementGap {

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
    private Counterparty bank;

    @Column(name = "bank_name", nullable = false, length = 200)
    private String bankName;

    @NotNull
    @Column(name = "gap_start", nullable = false)
    private LocalDate gapStart;

    @NotNull
    @Column(name = "gap_end", nullable = false)
    private LocalDate gapEnd;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatementGapStatus status = StatementGapStatus.DETECTED;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Column(name = "last_request_at")
    private Instant lastRequestAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    // === поля петли алертов (DEC арх-догма: мульти-тенант/хай-лоад заранее) ===
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "reminder_no", nullable = false)
    private int reminderNo = 0;

    @Column(name = "next_action_at")
    private Instant nextActionAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "last_channel", length = 20)
    private String lastChannel;

    @Column(name = "resolved_by_statement_id")
    private UUID resolvedByStatementId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.detectedAt == null) this.detectedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }

    public Counterparty getBank() { return bank; }
    public void setBank(Counterparty bank) { this.bank = bank; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public LocalDate getGapStart() { return gapStart; }
    public void setGapStart(LocalDate gapStart) { this.gapStart = gapStart; }

    public LocalDate getGapEnd() { return gapEnd; }
    public void setGapEnd(LocalDate gapEnd) { this.gapEnd = gapEnd; }

    public StatementGapStatus getStatus() { return status; }
    public void setStatus(StatementGapStatus status) { this.status = status; }

    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }

    public Instant getLastRequestAt() { return lastRequestAt; }
    public void setLastRequestAt(Instant lastRequestAt) { this.lastRequestAt = lastRequestAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public int getReminderNo() { return reminderNo; }
    public void setReminderNo(int reminderNo) { this.reminderNo = reminderNo; }

    public Instant getNextActionAt() { return nextActionAt; }
    public void setNextActionAt(Instant nextActionAt) { this.nextActionAt = nextActionAt; }

    public Instant getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(Instant escalatedAt) { this.escalatedAt = escalatedAt; }

    public String getLastChannel() { return lastChannel; }
    public void setLastChannel(String lastChannel) { this.lastChannel = lastChannel; }

    public UUID getResolvedByStatementId() { return resolvedByStatementId; }
    public void setResolvedByStatementId(UUID resolvedByStatementId) { this.resolvedByStatementId = resolvedByStatementId; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
