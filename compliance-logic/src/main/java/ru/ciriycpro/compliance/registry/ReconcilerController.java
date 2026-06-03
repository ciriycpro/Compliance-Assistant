package ru.ciriycpro.compliance.registry;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ciriycpro.compliance.service.ReconcilerService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class ReconcilerController {

    private final ReconcilerService reconcilerService;

    public ReconcilerController(ReconcilerService reconcilerService) {
        this.reconcilerService = reconcilerService;
    }

    /** Запустить сверку для клиента. */
    @PostMapping("/clients/{clientId}/reconcile")
    public ResponseEntity<ReconcilerService.ReconcileResult> reconcile(@PathVariable UUID clientId) {
        return ResponseEntity.ok(reconcilerService.reconcile(clientId));
    }

    /** Реестр флагов клиента. ?all=true — включая закрытые (по умолчанию только открытые). */
    @GetMapping("/clients/{clientId}/reconciliation-flags")
    public ResponseEntity<List<FlagResponse>> flags(@PathVariable UUID clientId,
                                                    @RequestParam(defaultValue = "false") boolean all) {
        List<ReconciliationFlag> flags = all
                ? reconcilerService.allFlags(clientId)
                : reconcilerService.openFlags(clientId);
        return ResponseEntity.ok(flags.stream().map(ReconcilerController::toResponse).toList());
    }

    /** Ручной запуск re-scan (тот же, что в cron). */
    @PostMapping("/reconciliation/rescan")
    public ResponseEntity<ReconcilerService.RescanResult> rescan() {
        return ResponseEntity.ok(reconcilerService.rescanAll());
    }

    private static FlagResponse toResponse(ReconciliationFlag f) {
        return new FlagResponse(
                f.getId(), f.getClient().getId(), f.getCounterparty().getId(),
                f.getCounterparty().getInn(), f.getCounterparty().getName(),
                f.getFlagType().name(), f.getStatus().name(), f.getContractRef(),
                f.getOperationCount(), f.getTotalAmount(), f.getDetails(),
                f.getDetectedAt(), f.getResolvedAt());
    }

    public record FlagResponse(
            UUID id, UUID clientId, UUID counterpartyId, String counterpartyInn, String counterpartyName,
            String flagType, String status, String contractRef, Integer operationCount, BigDecimal totalAmount,
            String details, Instant detectedAt, Instant resolvedAt) {}
}
