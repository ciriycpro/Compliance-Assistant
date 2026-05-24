package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ciriycpro.compliance.service.StatementGapInspectorService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
public class AdminInspectorController {

    private final StatementGapInspectorService inspectorService;
    private final StatementGapRepository statementGapRepository;

    public AdminInspectorController(StatementGapInspectorService inspectorService,
                                    StatementGapRepository statementGapRepository) {
        this.inspectorService = inspectorService;
        this.statementGapRepository = statementGapRepository;
    }

    /**
     * POST /admin/inspector/scan-now — ручной триггер Inspector.
     * Если clientId не указан — сканирует всех ACTIVE клиентов.
     */
    @PostMapping("/admin/inspector/scan-now")
    public ResponseEntity<?> scanNow(@RequestParam(required = false) UUID clientId) {
        if (clientId != null) {
            StatementGapInspectorService.ClientScanResult result = inspectorService.scanClient(clientId);
            return ResponseEntity.ok(result);
        }
        StatementGapInspectorService.ScanResult result = inspectorService.scanAllActiveClients();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/clients/{clientId}/statement-gaps")
    public ResponseEntity<Page<StatementGapResponse>> listForClient(
            @PathVariable UUID clientId,
            @RequestParam(required = false) StatementGapStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "detectedAt"));
        Page<StatementGap> gaps = (status != null)
                ? statementGapRepository.findByClientIdAndStatus(clientId, status, pageable)
                : statementGapRepository.findByClientId(clientId, pageable);
        return ResponseEntity.ok(gaps.map(AdminInspectorController::toResponse));
    }

    @GetMapping("/statement-gaps/{id}")
    public ResponseEntity<StatementGapResponse> getById(@PathVariable UUID id) {
        return statementGapRepository.findById(id)
                .map(g -> ResponseEntity.ok(toResponse(g)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static StatementGapResponse toResponse(StatementGap g) {
        return new StatementGapResponse(
                g.getId(),
                g.getClient().getId(),
                g.getBank().getId(),
                g.getBankName(),
                g.getGapStart(),
                g.getGapEnd(),
                g.getStatus().name(),
                g.getDetectedAt(),
                g.getLastRequestAt(),
                g.getClosedAt(),
                g.getCreatedAt(),
                g.getUpdatedAt()
        );
    }

    public record StatementGapResponse(
            UUID id,
            UUID clientId,
            UUID bankId,
            String bankName,
            LocalDate gapStart,
            LocalDate gapEnd,
            String status,
            Instant detectedAt,
            Instant lastRequestAt,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
