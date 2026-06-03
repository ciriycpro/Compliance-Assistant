package ru.ciriycpro.compliance.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ciriycpro.compliance.service.ContractService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping("/contracts")
    public ResponseEntity<ContractResponse> create(@Valid @RequestBody ContractCreateRequest req) {
        Contract saved = contractService.create(req.documentId(), req.clientId(), req.counterpartyId(),
                req.contractNumber(), req.contractDate(), req.validFrom(), req.validTo(),
                req.subject(), req.subjectCategory(), req.amount(), req.signingStatus());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/contracts/{id}")
    public ResponseEntity<ContractResponse> getById(@PathVariable UUID id) {
        return contractService.findById(id)
                .map(c -> ResponseEntity.ok(toResponse(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/clients/{clientId}/contracts")
    public ResponseEntity<Page<ContractResponse>> listForClient(
            @PathVariable UUID clientId,
            @RequestParam(required = false) UUID counterpartyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "contractDate"));
        return ResponseEntity.ok(contractService.listForClient(clientId, counterpartyId, pageable).map(ContractController::toResponse));
    }

    private static ContractResponse toResponse(Contract c) {
        return new ContractResponse(
                c.getId(), c.getDocument().getId(), c.getClient().getId(), c.getCounterparty().getId(),
                c.getContractNumber(), c.getContractDate(), c.getValidFrom(), c.getValidTo(),
                c.getSubject(), c.getSubjectCategory(), c.getAmount(), c.getSigningStatus().name(),
                c.isClientSigned(), c.getClientSignedDate(), c.isCounterpartySigned(), c.getCounterpartySignedDate(),
                c.getSignatureConfidence(), c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    public record ContractCreateRequest(
            @NotNull UUID documentId,
            @NotNull UUID clientId,
            @NotNull UUID counterpartyId,
            String contractNumber,
            LocalDate contractDate,
            LocalDate validFrom,
            LocalDate validTo,
            String subject,
            String subjectCategory,
            BigDecimal amount,
            SigningStatus signingStatus
    ) {}

    public record ContractResponse(
            UUID id, UUID documentId, UUID clientId, UUID counterpartyId,
            String contractNumber, LocalDate contractDate, LocalDate validFrom, LocalDate validTo,
            String subject, String subjectCategory, BigDecimal amount, String signingStatus,
            boolean clientSigned, LocalDate clientSignedDate, boolean counterpartySigned, LocalDate counterpartySignedDate,
            BigDecimal signatureConfidence, Instant createdAt, Instant updatedAt
    ) {}
}
