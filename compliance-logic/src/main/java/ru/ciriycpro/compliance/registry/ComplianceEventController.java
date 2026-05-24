package ru.ciriycpro.compliance.registry;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ciriycpro.compliance.service.ComplianceEventService;

import java.time.Instant;
import java.util.UUID;

@RestController
public class ComplianceEventController {

    private final ComplianceEventService complianceEventService;

    public ComplianceEventController(ComplianceEventService complianceEventService) {
        this.complianceEventService = complianceEventService;
    }

    /**
     * POST /compliance-event — приём события от orchestrator.
     *
     * Идемпотентность: повторная отправка того же event_id возвращает существующее событие
     * с HTTP 200 (вместо 201) — клиент должен проверять флаг already_existed.
     */
    @PostMapping("/compliance-event")
    public ResponseEntity<ComplianceEventResponse> ingest(@Valid @RequestBody ComplianceEventDTO dto) {
        ComplianceEventService.IngestResult result = complianceEventService.ingest(
                dto.eventId(), dto.traceId(), dto.occurredAt(),
                dto.eventType(), dto.clientInn(),
                dto.source(), dto.historic(),
                dto.payload()
        );

        HttpStatus status = result.alreadyExisted() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(toResponse(result.event(), result.alreadyExisted()));
    }

    @GetMapping("/compliance-events/{eventId}")
    public ResponseEntity<ComplianceEventResponse> getByEventId(@PathVariable UUID eventId) {
        return complianceEventService.findByEventId(eventId)
                .map(e -> ResponseEntity.ok(toResponse(e, false)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/clients/{clientInn}/compliance-events")
    public ResponseEntity<Page<ComplianceEventResponse>> listForClient(
            @PathVariable String clientInn,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt"));
        return ResponseEntity.ok(
                complianceEventService.listForClient(clientInn, eventType, pageable)
                        .map(e -> toResponse(e, false))
        );
    }

    private static ComplianceEventResponse toResponse(ComplianceEvent e, boolean alreadyExisted) {
        return new ComplianceEventResponse(
                e.getId(),
                e.getEventId(),
                e.getTraceId(),
                e.getEventType(),
                e.getClientInn(),
                e.getSource().name(),
                e.isHistoric(),
                e.getOccurredAt(),
                e.getPayload(),
                e.getReceivedAt(),
                e.getProcessedAt(),
                e.getProcessingError(),
                alreadyExisted
        );
    }

    public record ComplianceEventDTO(
            @NotNull UUID eventId,
            @NotNull UUID traceId,
            @NotNull Instant occurredAt,
            @NotBlank String eventType,
            @NotBlank String clientInn,
            @NotNull EventSource source,
            boolean historic,
            JsonNode payload
    ) {}

    public record ComplianceEventResponse(
            UUID id,
            UUID eventId,
            UUID traceId,
            String eventType,
            String clientInn,
            String source,
            boolean historic,
            Instant occurredAt,
            JsonNode payload,
            Instant receivedAt,
            Instant processedAt,
            String processingError,
            boolean alreadyExisted
    ) {}
}
