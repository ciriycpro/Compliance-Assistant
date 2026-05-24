package ru.ciriycpro.compliance.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ciriycpro.compliance.service.StatementCalendarService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
public class StatementCalendarController {

    private final StatementCalendarService service;

    public StatementCalendarController(StatementCalendarService service) {
        this.service = service;
    }

    @PostMapping("/clients/{clientId}/statement-calendars")
    public ResponseEntity<CalendarResponse> create(@PathVariable UUID clientId, @Valid @RequestBody CalendarRequest dto) {
        StatementCalendar created = service.create(clientId, dto.bankId(), dto.frequency(), dto.startPeriod(), dto.active());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping("/clients/{clientId}/statement-calendars")
    public ResponseEntity<List<CalendarResponse>> list(@PathVariable UUID clientId) {
        return ResponseEntity.ok(service.listForClient(clientId).stream().map(StatementCalendarController::toResponse).toList());
    }

    @GetMapping("/statement-calendars/{id}")
    public ResponseEntity<CalendarResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(toResponse(service.findById(id)));
    }

    @DeleteMapping("/statement-calendars/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    private static CalendarResponse toResponse(StatementCalendar c) {
        return new CalendarResponse(
                c.getId(), c.getClient().getId(), c.getBank().getId(),
                c.getFrequency().name(), c.getStartPeriod(), c.isActive(),
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    public record CalendarRequest(
            @NotNull UUID bankId,
            @NotNull StatementFrequency frequency,
            @NotNull LocalDate startPeriod,
            boolean active
    ) {}

    public record CalendarResponse(
            UUID id, UUID clientId, UUID bankId, String frequency, LocalDate startPeriod, boolean active,
            Instant createdAt, Instant updatedAt
    ) {}
}
