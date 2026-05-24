package ru.ciriycpro.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ciriycpro.compliance.registry.ComplianceEvent;
import ru.ciriycpro.compliance.registry.ComplianceEventRepository;
import ru.ciriycpro.compliance.registry.EventSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * ComplianceEventService — приём и хранение событий от orchestrator.
 *
 * Бизнес-инварианты:
 *  - event_id уникален (idempotency) — повторная отправка с тем же event_id → возврат существующего
 *  - eventType, clientInn, source — обязательны (бизнес-валидация в дополнение к Bean Validation)
 *  - payload JSONB — гибкая структура (List<ParsedAttachment>, tags, meta)
 *  - processed_at — заполняется когда событие обработано в registry
 *  - processing_error — если processing упал, для debugging
 *
 * Idempotency pattern: если event_id уже существует — возвращаем существующий ComplianceEvent
 * вместо exception. Это позволяет orchestrator безопасно retry'ить отправку.
 *
 * См. DEC-023 строки 256-275 (Endpoint /compliance-event).
 */
@Service
public class ComplianceEventService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceEventService.class);

    private final ComplianceEventRepository complianceEventRepository;

    public ComplianceEventService(ComplianceEventRepository complianceEventRepository) {
        this.complianceEventRepository = complianceEventRepository;
    }

    public record IngestResult(ComplianceEvent event, boolean alreadyExisted) {}

    /**
     * Приём события от orchestrator.
     * @return IngestResult — содержит event и флаг alreadyExisted (idempotency hit)
     */
    @Transactional
    public IngestResult ingest(UUID eventId, UUID traceId, Instant occurredAt,
                               String eventType, String clientInn,
                               EventSource source, boolean historic,
                               JsonNode payload) {

        // Idempotency check — если event_id уже обработан, возвращаем существующий
        Optional<ComplianceEvent> existing = complianceEventRepository.findByEventId(eventId);
        if (existing.isPresent()) {
            log.info("event idempotency hit event_id={} returning existing event", eventId);
            return new IngestResult(existing.get(), true);
        }

        ComplianceEvent event = new ComplianceEvent();
        event.setEventId(eventId);
        event.setTraceId(traceId);
        event.setOccurredAt(occurredAt);
        event.setEventType(eventType);
        event.setClientInn(clientInn);
        event.setSource(source);
        event.setHistoric(historic);
        event.setPayload(payload);

        ComplianceEvent saved = complianceEventRepository.save(event);
        log.info("event ingested event_id={} trace_id={} type={} client_inn={} source={} historic={}",
                saved.getEventId(), saved.getTraceId(), saved.getEventType(),
                saved.getClientInn(), saved.getSource(), saved.isHistoric());
        return new IngestResult(saved, false);
    }

    /**
     * Отметить событие как обработанное (вызывается после успешного processing в registry).
     */
    @Transactional
    public ComplianceEvent markProcessed(UUID eventId) {
        ComplianceEvent event = complianceEventRepository.findByEventId(eventId)
                .orElseThrow(() -> new EventNotFoundException("event not found: " + eventId));
        event.setProcessedAt(Instant.now());
        event.setProcessingError(null);
        ComplianceEvent saved = complianceEventRepository.save(event);
        log.info("event marked processed event_id={} processed_at={}", eventId, saved.getProcessedAt());
        return saved;
    }

    /**
     * Отметить событие как failed (для retry tracking).
     */
    @Transactional
    public ComplianceEvent markFailed(UUID eventId, String errorMessage) {
        ComplianceEvent event = complianceEventRepository.findByEventId(eventId)
                .orElseThrow(() -> new EventNotFoundException("event not found: " + eventId));
        event.setProcessingError(errorMessage);
        ComplianceEvent saved = complianceEventRepository.save(event);
        log.warn("event marked failed event_id={} error={}", eventId, errorMessage);
        return saved;
    }

    public Optional<ComplianceEvent> findByEventId(UUID eventId) {
        return complianceEventRepository.findByEventId(eventId);
    }

    public Page<ComplianceEvent> listForClient(String clientInn, String eventType, Pageable pageable) {
        if (eventType != null) {
            return complianceEventRepository.findByClientInnAndEventType(clientInn, eventType, pageable);
        }
        return complianceEventRepository.findByClientInn(clientInn, pageable);
    }

    public static class EventNotFoundException extends RuntimeException {
        public EventNotFoundException(String message) { super(message); }
    }
}
