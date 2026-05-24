package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "counterparties",
    indexes = {
        @Index(name = "idx_counterparties_client", columnList = "client_id"),
        @Index(name = "idx_counterparties_inn", columnList = "inn"),
        @Index(name = "idx_counterparties_trust_level", columnList = "trust_level")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_counterparties_client_inn", columnNames = {"client_id", "inn"})
    }
)
public class Counterparty {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @NotBlank
    @Size(min = 10, max = 12)
    @Column(name = "inn", nullable = false, length = 12)
    private String inn;

    @NotBlank
    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false, length = 20)
    private CounterpartyTrustLevel trustLevel = CounterpartyTrustLevel.NEUTRAL;

    @Column(name = "notes", length = 2000)
    private String notes;

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

    public String getInn() { return inn; }
    public void setInn(String inn) { this.inn = inn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public CounterpartyTrustLevel getTrustLevel() { return trustLevel; }
    public void setTrustLevel(CounterpartyTrustLevel trustLevel) { this.trustLevel = trustLevel; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
