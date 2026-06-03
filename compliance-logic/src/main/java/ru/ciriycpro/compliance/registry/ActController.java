package ru.ciriycpro.compliance.registry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ciriycpro.compliance.service.ActService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
public class ActController {

    private final ActService actService;

    public ActController(ActService actService) {
        this.actService = actService;
    }

    @PostMapping("/acts")
    public ResponseEntity<ActResponse> create(@Valid @RequestBody ActCreateRequest req) {
        Act saved = actService.create(req.documentId(), req.clientId(), req.counterpartyId(), req.contractId(),
                req.actNumber(), req.actDate(), req.amount(), req.subject(), req.signingStatus());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/acts/{id}")
    public ResponseEntity<ActResponse> getById(@PathVariable UUID id) {
        return actService.findById(id)
                .map(a -> ResponseEntity.ok(toResponse(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/clients/{clientId}/acts")
    public ResponseEntity<Page<ActResponse>> listForClient(
            @PathVariable UUID clientId,
            @RequestParam(required = false) UUID counterpartyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "actDate"));
        return ResponseEntity.ok(actService.listForClient(clientId, counterpartyId, pageable).map(ActController::toResponse));
    }

    private static ActResponse toResponse(Act a) {
        return new ActResponse(
                a.getId(), a.getDocument().getId(), a.getClient().getId(), a.getCounterparty().getId(),
                a.getContract() != null ? a.getContract().getId() : null,
                a.getActNumber(), a.getActDate(), a.getAmount(), a.getSubject(), a.getSigningStatus().name(),
                a.isClientSigned(), a.getClientSignedDate(), a.isCounterpartySigned(), a.getCounterpartySignedDate(),
                a.getSignatureConfidence(), a.getCreatedAt(), a.getUpdatedAt()
        );
    }

    public record ActCreateRequest(
            @NotNull UUID documentId,
            @NotNull UUID clientId,
            @NotNull UUID counterpartyId,
            UUID contractId,
            String actNumber,
            LocalDate actDate,
            BigDecimal amount,
            String subject,
            SigningStatus signingStatus
    ) {}

    public record ActResponse(
            UUID id, UUID documentId, UUID clientId, UUID counterpartyId, UUID contractId,
            String actNumber, LocalDate actDate, BigDecimal amount, String subject, String signingStatus,
            boolean clientSigned, LocalDate clientSignedDate, boolean counterpartySigned, LocalDate counterpartySignedDate,
            BigDecimal signatureConfidence, Instant createdAt, Instant updatedAt
    ) {}
}
