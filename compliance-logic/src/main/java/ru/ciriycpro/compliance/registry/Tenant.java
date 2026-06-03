package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Тенант — комплаенс-фирма (над client). v1: одна строка (СИРИУС ПРО).
 * policy / escalation_contact хранятся как jsonb, читаются как сырой JSON-текст и парсятся в сервисе.
 * Не @Audited — конфиг-таблица.
 */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "escalation_contact")
    private String escalationContact;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy")
    private String policy;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEscalationContact() { return escalationContact; }
    public void setEscalationContact(String escalationContact) { this.escalationContact = escalationContact; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
