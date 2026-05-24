package ru.ciriycpro.compliance.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ciriycpro.compliance.service.MoneyOperationService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
public class MoneyOperationController {

    private final MoneyOperationService moneyOperationService;

    public MoneyOperationController(MoneyOperationService moneyOperationService) {
        this.moneyOperationService = moneyOperationService;
    }

    @PostMapping("/money-operations")
    public ResponseEntity<MoneyOperationResponse> create(@Valid @RequestBody MoneyOperationCreateRequest req) {
        MoneyOperation saved = moneyOperationService.create(
                req.statementId(), req.clientId(), req.counterpartyId(),
                req.operationDate(), req.amount(), req.direction(),
                req.purpose(), req.counterpartyInnRaw(), req.counterpartyNameRaw(),
                req.parsedContractNumber(), req.parsedContractDate(), req.parsedInvoiceNumber(),
                req.parsedSubject(), req.parsedSubjectCategory(),
                req.parsedQuantity(), req.parsedUnit(), req.parsedVatAmount(), req.parsingConfidence()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/money-operations/{id}")
    public ResponseEntity<MoneyOperationResponse> getById(@PathVariable UUID id) {
        return moneyOperationService.findById(id)
                .map(op -> ResponseEntity.ok(toResponse(op)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/statements/{statementId}/money-operations")
    public ResponseEntity<Page<MoneyOperationResponse>> listForStatement(
            @PathVariable UUID statementId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "operationDate"));
        return ResponseEntity.ok(moneyOperationService.listForStatement(statementId, pageable).map(MoneyOperationController::toResponse));
    }

    @GetMapping("/clients/{clientId}/money-operations")
    public ResponseEntity<Page<MoneyOperationResponse>> listForClient(
            @PathVariable UUID clientId,
            @RequestParam(required = false) UUID counterpartyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "operationDate"));
        return ResponseEntity.ok(moneyOperationService.listForClient(clientId, counterpartyId, pageable).map(MoneyOperationController::toResponse));
    }

    private static MoneyOperationResponse toResponse(MoneyOperation op) {
        return new MoneyOperationResponse(
                op.getId(), op.getStatement().getId(), op.getClient().getId(),
                op.getCounterparty() != null ? op.getCounterparty().getId() : null,
                op.getOperationDate(), op.getAmount(), op.getDirection().name(), op.getPurpose(),
                op.getCounterpartyInnRaw(), op.getCounterpartyNameRaw(),
                op.getParsedContractNumber(), op.getParsedContractDate(), op.getParsedInvoiceNumber(),
                op.getParsedSubject(), op.getParsedSubjectCategory(),
                op.getParsedQuantity(), op.getParsedUnit(), op.getParsedVatAmount(), op.getParsingConfidence(),
                op.getLinkedContractId(), op.getLinkedActId(),
                op.getCreatedAt(), op.getUpdatedAt()
        );
    }

    public record MoneyOperationCreateRequest(
            @NotNull UUID statementId,
            @NotNull UUID clientId,
            UUID counterpartyId,
            @NotNull LocalDate operationDate,
            @NotNull BigDecimal amount,
            @NotNull OperationDirection direction,
            String purpose,
            String counterpartyInnRaw,
            String counterpartyNameRaw,
            String parsedContractNumber,
            LocalDate parsedContractDate,
            String parsedInvoiceNumber,
            String parsedSubject,
            String parsedSubjectCategory,
            BigDecimal parsedQuantity,
            String parsedUnit,
            BigDecimal parsedVatAmount,
            BigDecimal parsingConfidence
    ) {}

    public record MoneyOperationResponse(
            UUID id, UUID statementId, UUID clientId, UUID counterpartyId,
            LocalDate operationDate, BigDecimal amount, String direction, String purpose,
            String counterpartyInnRaw, String counterpartyNameRaw,
            String parsedContractNumber, LocalDate parsedContractDate, String parsedInvoiceNumber,
            String parsedSubject, String parsedSubjectCategory,
            BigDecimal parsedQuantity, String parsedUnit, BigDecimal parsedVatAmount, BigDecimal parsingConfidence,
            UUID linkedContractId, UUID linkedActId,
            Instant createdAt, Instant updatedAt
    ) {}
}
