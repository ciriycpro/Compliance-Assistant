package ru.ciriycpro.compliance.registry;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * ComplianceEvent — лог входящих событий от orchestrator.
 *
 * Архитектура (DEC-023):
 *  - orchestrator пушит события через POST /compliance-event
 *  - idempotency: event_id уникальный — повторная отправка → 409
 *  - trace_id — для distributed tracing через всю pipeline
 *  - payload JSONB — гибкая структура (ParsedAttachment, tags, meta)
 *  - historic=true — Backfill-режим, Inspector НЕ создаёт OutboxMessage
 *
 * Lifecycle: received_at → processed_at (после обработки в registry)
 *
 * См. DEC-023 строки 154-162 и 256-275.
 */
@Audited
@Entity
@Table(name = "compliance_events",
    indexes = {
        @Index(name = "idx_compliance_events_event_id", columnList = "event_id"),
        @Index(name = "idx_compliance_events_trace_id", columnList = "trace_id"),
        @Index(name = "idx_compliance_events_client_inn", columnList = "client_inn"),
        @Index(name = "idx_compliance_events_event_type", columnList = "event_type"),
        @Index(name = "idx_compliance_events_source", columnList = "source"),
        @Index(name = "idx_compliance_events_received_at", columnList = "received_at"),
        @Index(name = "idx_compliance_events_processed_at", columnList = "processed_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_compliance_events_event_id", columnNames = {"event_id"})
    }
)
public class ComplianceEvent {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @NotNull
    @Column(name = "event_id", nullable = false, columnDefinition = "uuid")
    private UUID eventId;

    @NotNull
    @Column(name = "trace_id", nullable = false, columnDefinition = "uuid")
    private UUID traceId;

    @NotBlank
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @NotBlank
    @Column(name = "client_inn", nullable = false, length = 12)
    private String clientInn;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private EventSource source;

    @Column(name = "historic", nullable = false)
    private boolean historic = false;

    @NotNull
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "processing_error", length = 2000)
    private String processingError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.receivedAt == null) this.receivedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public UUID getTraceId() { return traceId; }
    public void setTraceId(UUID traceId) { this.traceId = traceId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getClientInn() { return clientInn; }
    public void setClientInn(String clientInn) { this.clientInn = clientInn; }

    public EventSource getSource() { return source; }
    public void setSource(EventSource source) { this.source = source; }

    public boolean isHistoric() { return historic; }
    public void setHistoric(boolean historic) { this.historic = historic; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }

    public String getProcessingError() { return processingError; }
    public void setProcessingError(String processingError) { this.processingError = processingError; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
