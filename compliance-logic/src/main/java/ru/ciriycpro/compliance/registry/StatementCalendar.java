package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * StatementCalendar — ожидания по периодичности выписок (DEC-023 v1.5, SECURITY_DEBT #18).
 *
 * Inspector v2 использует это:
 *   - Получает active StatementCalendar клиента
 *   - Для каждого calendar генерирует expected periods от start_period до now()
 *   - Сравнивает с existing Statement по (client_id, bank_id, period_start, period_end)
 *   - Если expected period отсутствует → создаёт StatementGap reason=EXPECTED_BUT_MISSING
 */
@Audited
@Entity
@Table(name = "statement_calendars",
    indexes = {
        @Index(name = "idx_statement_cal_client", columnList = "client_id"),
        @Index(name = "idx_statement_cal_bank", columnList = "bank_id"),
        @Index(name = "idx_statement_cal_active", columnList = "active")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_statement_cal_client_bank_freq",
                          columnNames = {"client_id", "bank_id", "frequency"})
    }
)
public class StatementCalendar {

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
    @JoinColumn(name = "bank_id", nullable = false)
    private Counterparty bank;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private StatementFrequency frequency;

    @NotNull
    @Column(name = "start_period", nullable = false)
    private LocalDate startPeriod;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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
    public Counterparty getBank() { return bank; }
    public void setBank(Counterparty bank) { this.bank = bank; }
    public StatementFrequency getFrequency() { return frequency; }
    public void setFrequency(StatementFrequency frequency) { this.frequency = frequency; }
    public LocalDate getStartPeriod() { return startPeriod; }
    public void setStartPeriod(LocalDate startPeriod) { this.startPeriod = startPeriod; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
