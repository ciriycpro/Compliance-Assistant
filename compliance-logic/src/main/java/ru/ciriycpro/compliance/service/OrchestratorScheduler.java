package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OrchestratorScheduler — самоход петли алертов.
 *
 * Расписание (через application.properties):
 *  - orchestrator.alert.cron — cron (default: 0 0 9,21 * * * — 2 раза в день, 09:00 и 21:00 МСК)
 *
 * Каданс напоминаний задаётся policy.reminder_interval_hours (12ч) + next_action_at на дыре:
 * прогон в 09:00 берёт дыры с next_action<=now, шлёт, ставит next_action+12ч -> следующий прогон 21:00 берёт их снова.
 * Стоп — когда дыра закрыта (пылесос 5458508) либо reminder>=max -> ESCALATED.
 *
 * v1 одна инстанция: ок. Под N реплик — single-leader (ShedLock), hardening-проход.
 */
@Component
public class OrchestratorScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorScheduler.class);

    private final GapAlertOrchestrator orchestrator;

    public OrchestratorScheduler(GapAlertOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(cron = "${orchestrator.alert.cron:0 0 9,21 * * *}", zone = "${orchestrator.alert.zone:Europe/Moscow}")
    public void scheduledAlertRun() {
        log.info("scheduled orchestrator run starting...");
        try {
            GapAlertOrchestrator.RunResult r = orchestrator.runOnce();
            log.info("scheduled orchestrator run completed: {}", r);
        } catch (Exception e) {
            log.error("scheduled orchestrator run failed", e);
        }
    }
}
