package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ReconcilerScheduler — ежедневный re-scan флагов (авто-закрытие при появлении договора).
 * Расписание: reconciler.rescan.cron (default 0 30 10 * * * — 10:30 ежедневно). DEC-023 Коммит 4.
 */
@Component
public class ReconcilerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerScheduler.class);

    private final ReconcilerService reconcilerService;

    public ReconcilerScheduler(ReconcilerService reconcilerService) {
        this.reconcilerService = reconcilerService;
    }

    @Scheduled(cron = "${reconciler.rescan.cron:0 30 10 * * *}")
    public void scheduledRescan() {
        log.info("scheduled reconciler rescan starting...");
        try {
            ReconcilerService.RescanResult r = reconcilerService.rescanAll();
            log.info("scheduled reconciler rescan done open={} closed={}", r.openScanned(), r.closed());
        } catch (Exception e) {
            log.error("scheduled reconciler rescan failed", e);
        }
    }
}
