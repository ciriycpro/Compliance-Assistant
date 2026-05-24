package ru.ciriycpro.compliance.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ciriycpro.compliance.service.CounterpartyService;

import java.time.Instant;
import java.util.UUID;

@RestController
public class CounterpartyController {

    private final CounterpartyService counterpartyService;

    public CounterpartyController(CounterpartyService counterpartyService) {
        this.counterpartyService = counterpartyService;
    }

    @PostMapping("/counterparties")
    public ResponseEntity<CounterpartyResponse> create(@Valid @RequestBody CounterpartyCreateRequest req) {
        Counterparty saved = counterpartyService.create(req.clientId(), req.inn(), req.name(), req.trustLevel(), req.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/counterparties/{id}")
    public ResponseEntity<CounterpartyResponse> getById(@PathVariable UUID id) {
        return counterpartyService.findById(id)
                .map(c -> ResponseEntity.ok(toResponse(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/clients/{clientId}/counterparties")
    public ResponseEntity<Page<CounterpartyResponse>> listForClient(
            @PathVariable UUID clientId,
            @RequestParam(required = false) CounterpartyTrustLevel trustLevel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "name"));
        return ResponseEntity.ok(counterpartyService.listForClient(clientId, trustLevel, pageable).map(CounterpartyController::toResponse));
    }

    private static CounterpartyResponse toResponse(Counterparty c) {
        return new CounterpartyResponse(
                c.getId(), c.getClient().getId(), c.getInn(), c.getName(),
                c.getTrustLevel().name(), c.getNotes(), c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    public record CounterpartyCreateRequest(
            @NotNull UUID clientId,
            @NotBlank @Size(min = 10, max = 12) String inn,
            @NotBlank String name,
            CounterpartyTrustLevel trustLevel,
            String notes
    ) {}

    public record CounterpartyResponse(
            UUID id, UUID clientId, String inn, String name,
            String trustLevel, String notes, Instant createdAt, Instant updatedAt
    ) {}
}
