package ru.ciriycpro.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ciriycpro.compliance.registry.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Оркестратор петли алертов. Детерминированный Java-сервис, без LLM. Канал: только WhatsApp.
 *
 * Two-phase (outbox), чтобы НЕ держать локи БД во время медленной отправки:
 *   Фаза 1 (короткая tx): claim дыр (SKIP LOCKED) -> notification(PENDING) -> advance дыр -> commit (замки отпущены).
 *   Фаза 2 (без локов):  по каждому staged-уведомлению -> WhatsApp -> короткая tx пометить SENT/FAILED.
 *
 * Конкурирующие реплики разводятся claim'ом дыр (SKIP LOCKED): один воркер -> один батч -> свой набор staged id.
 * Дыры advance'ятся в фазе 1 (решение «дёрнуть» принимается атомарно с claim); доставка асинхронна.
 * reminder>=max -> ESCALATED (только статус; НИКОМУ не шлём, оператор смотрит сам).
 */
@Service
public class GapAlertOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GapAlertOrchestrator.class);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final List<StatementGapStatus> OPEN =
            List.of(StatementGapStatus.DETECTED, StatementGapStatus.REQUEST_SENT);

    private final TenantRepository tenantRepository;
    private final StatementGapRepository statementGapRepository;
    private final NotificationRepository notificationRepository;
    private final CallerPort caller;
    private final TransactionTemplate tx;
    private final ObjectMapper om = new ObjectMapper();

    public GapAlertOrchestrator(TenantRepository tenantRepository,
                                StatementGapRepository statementGapRepository,
                                NotificationRepository notificationRepository,
                                CallerPort caller,
                                PlatformTransactionManager txManager) {
        this.tenantRepository = tenantRepository;
        this.statementGapRepository = statementGapRepository;
        this.notificationRepository = notificationRepository;
        this.caller = caller;
        this.tx = new TransactionTemplate(txManager);
    }

    public RunResult runOnce() {
        Instant now = Instant.now();
        String today = LocalDate.now().toString();

        // ФАЗА 1: claim + создать PENDING + advance дыр. Короткая транзакция, замки отпускаются на commit.
        StageResult st = tx.execute(s -> stage(now, today));

        // ФАЗА 2: отправка WhatsApp БЕЗ удержания локов. Пометка результата — отдельной короткой транзакцией.
        int sent = 0, failed = 0;
        for (UUID nid : st.dispatchIds) {
            Notification n = notificationRepository.findById(nid).orElse(null);
            if (n == null || !"PENDING".equals(n.getStatus())) continue;
            String waNumber = parse(n.getRecipient()).path("wa").asText("");
            String text = parse(n.getPayload()).path("text").asText("");
            if (waNumber.isBlank()) {
                tx.executeWithoutResult(s -> markFailed(nid, "no wa number in recipient"));
                failed++;
                continue;
            }
            try {
                String resp = caller.sendWhatsApp(waNumber, text);
                tx.executeWithoutResult(s -> markSent(nid, resp, Instant.now()));
                sent++;
            } catch (Exception ex) {
                tx.executeWithoutResult(s -> markFailed(nid, ex.getMessage()));
                failed++;
                log.error("dispatch failed nid={} : {}", nid, ex.getMessage());
            }
        }

        RunResult r = new RunResult(st.groups, st.created, sent, st.dryRun, failed, st.escalated);
        log.info("orchestrator runOnce: {}", r);
        return r;
    }

    /** Фаза 1 — внутри транзакции. */
    private StageResult stage(Instant now, String today) {
        int groups = 0, created = 0, dryRun = 0, escalated = 0;
        List<UUID> dispatchIds = new ArrayList<>();

        for (Tenant tenant : tenantRepository.findAll()) {
            if (!tenant.isActive()) continue;

            JsonNode policy = parse(tenant.getPolicy());
            boolean sending = policy.path("sending_enabled").asBoolean(false);
            int maxRem = policy.path("max_reminders").asInt(3);
            long intervalH = policy.path("reminder_interval_hours").asLong(72);
            String submissionEmail = policy.path("submission_email").asText("5458508@mail.ru");
            String format = policy.path("format").asText("xlsx или pdf");

            List<StatementGap> due = statementGapRepository.claimDue(tenant.getId(), OPEN, now);
            if (due.isEmpty()) continue;

            Map<String, List<StatementGap>> byGroup = due.stream().collect(Collectors.groupingBy(
                    g -> g.getClient().getId() + "|" + g.getBank().getId(),
                    LinkedHashMap::new, Collectors.toList()));

            for (List<StatementGap> gg : byGroup.values()) {
                groups++;
                gg.sort((a, b) -> a.getGapStart().compareTo(b.getGapStart()));
                Client client = gg.get(0).getClient();
                Counterparty bank = gg.get(0).getBank();
                String key = "gapnag|" + client.getId() + "|" + bank.getId() + "|wa|" + today;

                Notification notif = notificationRepository.findByIdempotencyKey(key).orElse(null);
                if (notif != null && "SENT".equals(notif.getStatus())) continue; // уже отправлено сегодня

                if (notif == null) {
                    int reminderNo = gg.stream().mapToInt(StatementGap::getReminderNo).max().orElse(0);
                    String text = composeText(client, bank, gg, submissionEmail, format);
                    notif = new Notification();
                    notif.setTenantId(tenant.getId());
                    notif.setGapId(gg.get(0).getId());          // primary; полный список — в payload
                    notif.setReminderNo(reminderNo);
                    notif.setChannel("wa");
                    notif.setRecipient(client.getContact());
                    notif.setPayload(buildPayload(gg, text));
                    notif.setIdempotencyKey(key);
                    notif.setStatus("PENDING");
                    notif = notificationRepository.save(notif);
                    created++;
                }

                if (!sending) {                  // КАНАРЕЙКА: PENDING создан, дыры НЕ трогаем, в dispatch НЕ кладём
                    dryRun++;
                    continue;
                }

                // advance дыр (атомарно с claim); доставка пойдёт в фазе 2
                for (StatementGap g : gg) {
                    int nr = g.getReminderNo() + 1;
                    g.setReminderNo(nr);
                    g.setLastRequestAt(now);
                    g.setLastChannel("wa");
                    if (nr >= maxRem) {
                        g.setStatus(StatementGapStatus.ESCALATED);   // только статус, никому не шлём
                        g.setEscalatedAt(now);
                        escalated++;
                    } else {
                        g.setStatus(StatementGapStatus.REQUEST_SENT);
                        g.setNextActionAt(now.plus(intervalH, ChronoUnit.HOURS));
                    }
                    statementGapRepository.save(g);
                }
                dispatchIds.add(notif.getId());
            }
        }
        return new StageResult(groups, created, dryRun, escalated, dispatchIds);
    }

    private void markSent(UUID nid, String resp, Instant now) {
        notificationRepository.findById(nid).ifPresent(n -> {
            n.setStatus("SENT");
            n.setSentAt(now);
            n.setCallerResponse(resp);
            notificationRepository.save(n);
        });
    }

    private void markFailed(UUID nid, String msg) {
        notificationRepository.findById(nid).ifPresent(n -> {
            n.setStatus("FAILED");
            n.setAttempts(n.getAttempts() + 1);
            n.setCallerResponse(jsonStr(msg));
            notificationRepository.save(n);
        });
    }

    private String composeText(Client client, Counterparty bank, List<StatementGap> gg,
                               String submissionEmail, String format) {
        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDEA8 В системе отсутствуют выписки.\n");
        sb.append("Банк: ").append(bank.getName()).append("\n");
        sb.append("ИП: ").append(client.getFullName()).append("\n");
        sb.append("Периоды:\n");
        for (StatementGap g : gg) {
            sb.append(" • с ").append(g.getGapStart().format(DMY))
              .append(" по ").append(g.getGapEnd().format(DMY)).append("\n");
        }
        sb.append("Срочно пришлите на ").append(submissionEmail)
          .append(" в формате ").append(format).append(".");
        return sb.toString();
    }

    private JsonNode parse(String json) {
        try {
            if (json == null || json.isBlank()) return om.createObjectNode();
            return om.readTree(json);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

    private String jsonStr(String s) {
        try {
            return om.writeValueAsString(s == null ? "" : s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private String buildPayload(List<StatementGap> gg, String text) {
        try {
            List<Map<String, String>> periods = gg.stream()
                    .map(g -> Map.of("from", g.getGapStart().toString(), "to", g.getGapEnd().toString()))
                    .collect(Collectors.toList());
            List<UUID> ids = gg.stream().map(StatementGap::getId).collect(Collectors.toList());
            return om.writeValueAsString(Map.of("text", text, "periods", periods, "gap_ids", ids));
        } catch (Exception e) {
            return "{}";
        }
    }

    private record StageResult(int groups, int created, int dryRun, int escalated, List<UUID> dispatchIds) {}

    public record RunResult(int groups, int notificationsCreated, int sent, int dryRun, int failed, int escalated) {}
}
