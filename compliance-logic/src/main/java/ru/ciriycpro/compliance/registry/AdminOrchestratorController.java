package ru.ciriycpro.compliance.registry;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ciriycpro.compliance.service.GapAlertOrchestrator;

/**
 * Ручной триггер оркестратора петли алертов.
 * v1: авто-@Scheduled НЕ включён (канарейка под контролем). При sending_enabled=false — dry-run.
 */
@RestController
public class AdminOrchestratorController {

    private final GapAlertOrchestrator orchestrator;

    public AdminOrchestratorController(GapAlertOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/admin/orchestrator/run-once")
    public ResponseEntity<?> runOnce() {
        return ResponseEntity.ok(orchestrator.runOnce());
    }
}
