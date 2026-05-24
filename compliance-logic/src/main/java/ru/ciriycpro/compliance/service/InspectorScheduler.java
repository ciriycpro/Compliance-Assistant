package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * InspectorScheduler — cron-job для автоматического запуска Inspector.
 *
 * Расписание (через application.properties):
 *  - inspector.statement-gaps.cron — cron expression (default: 0 0 10 * * * — каждый день в 10:00 МСК)
 *  - inspector.enabled — true/false (default: true)
 *
 * См. DEC-023 строки 380-407.
 */
@Component
public class InspectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(InspectorScheduler.class);

    private final StatementGapInspectorService inspectorService;

    public InspectorScheduler(StatementGapInspectorService inspectorService) {
        this.inspectorService = inspectorService;
    }

    @Scheduled(cron = "${inspector.statement-gaps.cron:0 0 10 * * *}")
    public void scheduledStatementGapsScan() {
        log.info("scheduled inspector run starting...");
        try {
            StatementGapInspectorService.ScanResult result = inspectorService.scanAllActiveClients();
            log.info("scheduled inspector run completed clients={} gaps_found={} gaps_created={} duration_ms={}",
                    result.clientsScanned(), result.gapsFound(), result.gapsCreated(), result.durationMs());
        } catch (Exception e) {
            log.error("scheduled inspector run failed", e);
        }
    }
}
