package ru.ciriycpro.compliance.registry;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "clients")
public class Client {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @NotBlank
    @Size(min = 10, max = 12)
    @Column(name = "inn", nullable = false, unique = true, length = 12)
    private String inn;

    @NotBlank
    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ClientStatus status = ClientStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @jakarta.persistence.Column(name = "monitoring_period_start")
    private java.time.LocalDate monitoringPeriodStart;

    // v1: единственный тенант. TODO: заменить резолвом при мультитенанте.
    public static final UUID SEED_TENANT_ID = UUID.fromString("7c1a9d2e-1f3b-4c5d-8e6f-0a1b2c3d4e5f");

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    // Куда писать клиенту (per-client). v1: оба ИП → контакт Таирова. {"tg_chat_id":..,"wa":..,"email":..}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contact")
    private String contact;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        if (this.tenantId == null) this.tenantId = SEED_TENANT_ID;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }

    public String getInn() { return inn; }
    public void setInn(String inn) { this.inn = inn; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public ClientStatus getStatus() { return status; }
    public void setStatus(ClientStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public java.time.LocalDate getMonitoringPeriodStart() { return monitoringPeriodStart; }
    public void setMonitoringPeriodStart(java.time.LocalDate v) { this.monitoringPeriodStart = v; }
}
