package ru.ciriycpro.compliance.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ciriycpro.compliance.service.StatementService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @PostMapping("/statements")
    public ResponseEntity<StatementResponse> create(@Valid @RequestBody StatementCreateRequest req) {
        Statement saved = statementService.create(req.documentId(), req.clientId(), req.bankId(), req.bankName(),
                req.periodStart(), req.periodEnd(), req.sourceMessageId(),
                req.amountTotal(), req.operationCount(), req.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/statements/{id}")
    public ResponseEntity<StatementResponse> getById(@PathVariable UUID id) {
        return statementService.findById(id)
                .map(s -> ResponseEntity.ok(toResponse(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/clients/{clientId}/statements")
    public ResponseEntity<Page<StatementResponse>> listForClient(
            @PathVariable UUID clientId,
            @RequestParam(required = false) UUID bankId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "periodStart"));
        return ResponseEntity.ok(statementService.listForClient(clientId, bankId, pageable).map(StatementController::toResponse));
    }

    private static StatementResponse toResponse(Statement s) {
        return new StatementResponse(
                s.getId(), s.getDocument().getId(), s.getClient().getId(), s.getBank().getId(),
                s.getBankName(), s.getPeriodStart(), s.getPeriodEnd(), s.getSourceMessageId(),
                s.getAmountTotal(), s.getOperationCount(), s.getCurrency(),
                s.getStatus().name(), s.getCreatedAt(), s.getUpdatedAt()
        );
    }

    public record StatementCreateRequest(
            @NotNull UUID documentId,
            @NotNull UUID clientId,
            @NotNull UUID bankId,
            String bankName,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            String sourceMessageId,
            BigDecimal amountTotal,
            Integer operationCount,
            String currency
    ) {}

    public record StatementResponse(
            UUID id, UUID documentId, UUID clientId, UUID bankId,
            String bankName, LocalDate periodStart, LocalDate periodEnd, String sourceMessageId,
            BigDecimal amountTotal, Integer operationCount, String currency,
            String status, Instant createdAt, Instant updatedAt
    ) {}
}
