package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ciriycpro.compliance.registry.Client;
import ru.ciriycpro.compliance.registry.ClientRepository;
import ru.ciriycpro.compliance.registry.ClientStatus;
import ru.ciriycpro.compliance.registry.Counterparty;
import ru.ciriycpro.compliance.registry.CounterpartyRepository;
import ru.ciriycpro.compliance.registry.Statement;
import ru.ciriycpro.compliance.registry.StatementGap;
import ru.ciriycpro.compliance.registry.StatementGapRepository;
import ru.ciriycpro.compliance.registry.StatementGapStatus;
import ru.ciriycpro.compliance.registry.StatementRepository;
import ru.ciriycpro.compliance.registry.StatementCalendar;
import ru.ciriycpro.compliance.registry.StatementCalendarRepository;
import ru.ciriycpro.compliance.registry.StatementFrequency;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * StatementGapInspectorService — детектит пробелы в выписках клиента.
 *
 * Алгоритм v1.0:
 *  1. Для каждого ACTIVE клиента
 *  2. Для каждого банка (Counterparty), с которым у клиента есть Statement
 *  3. Отсортировать выписки этого банка по period_start
 *  4. Найти gaps между period_end[i] и period_start[i+1]:
 *     - Если period_start[i+1] - period_end[i] > 1 день → есть пробел
 *  5. Для каждого gap: findOrCreate StatementGap (idempotent по unique constraint)
 *
 * НЕ закрытые случаи v1.0 (отложено):
 *  - Календарь ожидаемых периодов (например ежемесячные выписки) — DEC-023 v1.5
 *  - Gap между concrete period и "ожидаемым последним" — нужен StatementCalendar
 *  - Auto-CLOSED при появлении покрывающей Statement (сделаем в коммите 4)
 *
 * См. DEC-023 строки 374-407.
 */
@Service
public class StatementGapInspectorService {

    private static final Logger log = LoggerFactory.getLogger(StatementGapInspectorService.class);

    private final ClientRepository clientRepository;
    private final CounterpartyRepository counterpartyRepository;
    private final StatementRepository statementRepository;
    private final StatementGapRepository statementGapRepository;
    private final StatementCalendarRepository statementCalendarRepository;

    public StatementGapInspectorService(ClientRepository clientRepository,
                                        CounterpartyRepository counterpartyRepository,
                                        StatementRepository statementRepository,
                                        StatementGapRepository statementGapRepository,
                                        StatementCalendarRepository statementCalendarRepository) {
        this.clientRepository = clientRepository;
        this.counterpartyRepository = counterpartyRepository;
        this.statementRepository = statementRepository;
        this.statementGapRepository = statementGapRepository;
        this.statementCalendarRepository = statementCalendarRepository;
    }

    /**
     * Полный скан всех ACTIVE клиентов. Используется планировщиком и через POST /admin/inspector/scan-now.
     */
    @Transactional
    public ScanResult scanAllActiveClients() {
        long startMs = System.currentTimeMillis();
        int clientsScanned = 0;
        int totalGapsFound = 0;
        int totalGapsCreated = 0;
        int totalGapsClosed = 0;

        List<Client> activeClients = clientRepository.findAll().stream()
                .filter(c -> c.getStatus() == ClientStatus.ACTIVE)
                .toList();

        for (Client client : activeClients) {
            clientsScanned++;
            ClientScanResult cr = scanClient(client.getId());
            totalGapsFound += cr.gapsFound();
            totalGapsCreated += cr.gapsCreated();
            totalGapsClosed += cr.gapsClosed();
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("inspector full scan completed clients={} gaps_found={} gaps_created={} gaps_closed={} duration_ms={}",
                clientsScanned, totalGapsFound, totalGapsCreated, totalGapsClosed, durationMs);

        return new ScanResult(clientsScanned, totalGapsFound, totalGapsCreated, totalGapsClosed, durationMs);
    }

    /**
     * Сканирование одного клиента — для ручного триггера через REST.
     */
    @Transactional
    public ClientScanResult scanClient(UUID clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));

        // Найти всех банков-counterparties этого клиента, у которых есть Statement
        List<Counterparty> banks = counterpartyRepository.findAll().stream()
                .filter(cp -> cp.getClient().getId().equals(clientId))
                .toList();

        int gapsFound = 0;
        int gapsCreated = 0;
        int gapsClosed = 0;

        for (Counterparty bank : banks) {
            List<Statement> bankStatements = statementRepository
                    .findByClientIdAndBankIdOrderByPeriodStartAsc(clientId, bank.getId());

            // Закрытие: любая открытая дыра банка, полностью покрытая выписками → CLOSED
            gapsClosed += closeCoveredGaps(client, bank, bankStatements);

            if (bankStatements.size() < 2) {
                continue;  // нужно минимум 2 statement чтобы между ними был gap
            }

            // Сортируем по period_start (на всякий случай — repository уже сортирует)
            bankStatements.sort(Comparator.comparing(Statement::getPeriodStart));

            // Находим gaps между соседними statement
            for (int i = 0; i < bankStatements.size() - 1; i++) {
                Statement current = bankStatements.get(i);
                Statement next = bankStatements.get(i + 1);

                LocalDate currentEnd = current.getPeriodEnd();
                LocalDate nextStart = next.getPeriodStart();

                // Если между ними больше 1 дня — есть пробел
                long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(currentEnd, nextStart);
                if (daysBetween > 1) {
                    LocalDate gapStart = currentEnd.plusDays(1);
                    LocalDate gapEnd = nextStart.minusDays(1);
                    gapsFound++;

                    // findOrCreate — idempotent по unique constraint (client+bank+gap_start+gap_end)
                    boolean wasCreated = createGapIfNotExists(client, bank, gapStart, gapEnd);
                    if (wasCreated) gapsCreated++;
                }
            }
        }

        // Calendar-based scan: expected periods из StatementCalendar
        CalendarScanResult calRes = scanCalendarsForClient(client);
        gapsFound += calRes.expectedPeriodsFound();
        gapsCreated += calRes.gapsCreated();

        log.info("client scan completed client_inn={} banks={} calendars={} expected={} gaps_found={} gaps_created={} gaps_closed={}",
                client.getInn(), banks.size(), calRes.calendarsScanned(), calRes.expectedPeriodsFound(), gapsFound, gapsCreated, gapsClosed);
        return new ClientScanResult(client.getId(), banks.size(), gapsFound, gapsCreated, gapsClosed);
    }

    /**
     * Calendar-based scan: для каждого active StatementCalendar клиента
     * генерирует expected periods, сравнивает с existing Statements,
     * создаёт gaps для отсутствующих ожидаемых периодов.
     *
     * Lower bound: max(client.monitoring_period_start, calendar.start_period, 2025-01-01 floor)
     * Upper bound: today
     *
     * Шаг между периодами зависит от frequency:
     *   MONTHLY → 1 month
     *   QUARTERLY → 3 months
     *   ANNUAL → 1 year
     */
    private CalendarScanResult scanCalendarsForClient(Client client) {
        List<StatementCalendar> calendars = statementCalendarRepository.findByClientIdAndActiveTrue(client.getId());
        if (calendars.isEmpty()) {
            return new CalendarScanResult(0, 0, 0);
        }

        LocalDate today = LocalDate.now();
        LocalDate lookbackFloor = LocalDate.of(2025, 1, 1); // глобальный пол мониторинга: раньше 2025-01-01 не смотрим
        LocalDate monitoringStart = client.getMonitoringPeriodStart();

        int calendarsScanned = 0;
        int expectedPeriodsFound = 0;
        int gapsCreated = 0;

        for (StatementCalendar cal : calendars) {
            calendarsScanned++;
            Counterparty bank = cal.getBank();

            // effective start = max из трёх дат
            LocalDate effectiveStart = cal.getStartPeriod();
            if (monitoringStart != null && monitoringStart.isAfter(effectiveStart)) {
                effectiveStart = monitoringStart;
            }
            if (lookbackFloor.isAfter(effectiveStart)) {
                effectiveStart = lookbackFloor;
            }

            // Список existing Statement для (client, bank) — нужен для матчинга
            List<Statement> existingStatements = statementRepository
                    .findByClientIdAndBankIdOrderByPeriodStartAsc(client.getId(), bank.getId());

            // Склеиваем смежные выписки в интервалы покрытия (хелпер buildCoverage, см. ниже).
            java.util.List<LocalDate[]> coverage = buildCoverage(existingStatements);

            // Генерируем expected periods
            LocalDate periodStart = normalizeToStart(effectiveStart, cal.getFrequency());
            while (!periodStart.isAfter(today)) {
                LocalDate periodEnd = computePeriodEnd(periodStart, cal.getFrequency());

                // Если period_end в будущем — пропускаем (период ещё не закончился)
                if (periodEnd.isAfter(today)) {
                    break;
                }

                // Кламп: период, начинающийся раньше нижней границы мониторинга, не флагуем
                if (periodStart.isBefore(effectiveStart)) {
                    periodStart = advancePeriod(periodStart, cal.getFrequency());
                    continue;
                }

                expectedPeriodsFound++;

                // Покрыт ли expected period склеенными интервалами выписок?
                LocalDate ps = periodStart;
                LocalDate pe = periodEnd;
                boolean covered = coverage.stream().anyMatch(iv ->
                    !iv[0].isAfter(ps) && !iv[1].isBefore(pe)
                );

                if (!covered) {
                    boolean created = createGapIfNotExists(client, bank, periodStart, periodEnd);
                    if (created) gapsCreated++;
                }

                periodStart = advancePeriod(periodStart, cal.getFrequency());
            }
        }

        return new CalendarScanResult(calendarsScanned, expectedPeriodsFound, gapsCreated);
    }

    /** Нормализация даты к началу периода (для MONTHLY — 1-е число месяца). */
    private LocalDate normalizeToStart(LocalDate d, StatementFrequency f) {
        return switch (f) {
            case WEEKLY -> d.with(DayOfWeek.MONDAY);
            case MONTHLY -> d.withDayOfMonth(1);
            case QUARTERLY -> {
                int qStartMonth = ((d.getMonthValue() - 1) / 3) * 3 + 1;
                yield LocalDate.of(d.getYear(), qStartMonth, 1);
            }
            case ANNUAL -> LocalDate.of(d.getYear(), 1, 1);
        };
    }

    /** Конец периода: для MONTHLY — последнее число того же месяца. */
    private LocalDate computePeriodEnd(LocalDate start, StatementFrequency f) {
        return switch (f) {
            case WEEKLY -> start.plusDays(6);
            case MONTHLY -> start.withDayOfMonth(start.lengthOfMonth());
            case QUARTERLY -> start.plusMonths(3).minusDays(1);
            case ANNUAL -> start.plusYears(1).minusDays(1);
        };
    }

    /** Следующий период: для MONTHLY — +1 месяц. */
    private LocalDate advancePeriod(LocalDate start, StatementFrequency f) {
        return switch (f) {
            case WEEKLY -> start.plusWeeks(1);
            case MONTHLY -> start.plusMonths(1);
            case QUARTERLY -> start.plusMonths(3);
            case ANNUAL -> start.plusYears(1);
        };
    }

    public record CalendarScanResult(int calendarsScanned, int expectedPeriodsFound, int gapsCreated) {}
    private boolean createGapIfNotExists(Client client, Counterparty bank, LocalDate gapStart, LocalDate gapEnd) {
        return statementGapRepository
                .findByClientIdAndBankIdAndGapStartAndGapEnd(client.getId(), bank.getId(), gapStart, gapEnd)
                .map(existing -> {
                    log.debug("gap already exists: client_inn={} bank={} period={}..{}",
                            client.getInn(), bank.getName(), gapStart, gapEnd);
                    return false;
                })
                .orElseGet(() -> {
                    StatementGap gap = new StatementGap();
                    gap.setClient(client);
                    gap.setBank(bank);
                    gap.setBankName(bank.getName());
                    gap.setGapStart(gapStart);
                    gap.setGapEnd(gapEnd);
                    gap.setStatus(StatementGapStatus.DETECTED);
                    gap.setTenantId(client.getTenantId());
                    gap.setNextActionAt(java.time.Instant.now());
                    statementGapRepository.save(gap);
                    log.info("gap DETECTED client_inn={} bank={} period={}..{} ({} days)",
                            client.getInn(), bank.getName(), gapStart, gapEnd,
                            java.time.temporal.ChronoUnit.DAYS.between(gapStart, gapEnd) + 1);
                    return true;
                });
    }

    /** Склейка выписок в непрерывные интервалы покрытия [start,end]; statements отсортированы по period_start. */
    private java.util.List<LocalDate[]> buildCoverage(List<Statement> statements) {
        java.util.List<LocalDate[]> coverage = new java.util.ArrayList<>();
        for (Statement s : statements) {
            LocalDate st = s.getPeriodStart();
            LocalDate en = s.getPeriodEnd();
            if (!coverage.isEmpty()
                    && !st.isAfter(coverage.get(coverage.size() - 1)[1].plusDays(1))) {
                LocalDate[] last = coverage.get(coverage.size() - 1);
                if (en.isAfter(last[1])) last[1] = en;
            } else {
                coverage.add(new LocalDate[]{st, en});
            }
        }
        return coverage;
    }

    /** Закрывает открытые дыры банка, полностью покрытые выписками. resolved_by = выписка, накрывшая gapEnd. */
    private int closeCoveredGaps(Client client, Counterparty bank, List<Statement> bankStatements) {
        if (bankStatements.isEmpty()) return 0;
        List<Statement> sorted = bankStatements.stream()
                .sorted(Comparator.comparing(Statement::getPeriodStart)).toList();
        java.util.List<LocalDate[]> coverage = buildCoverage(sorted);
        int closed = 0;
        for (StatementGap gap : statementGapRepository.findByClientIdAndBankId(client.getId(), bank.getId())) {
            if (gap.getStatus() == StatementGapStatus.CLOSED) continue;
            LocalDate gs = gap.getGapStart();
            LocalDate ge = gap.getGapEnd();
            boolean fully = coverage.stream().anyMatch(iv -> !iv[0].isAfter(gs) && !iv[1].isBefore(ge));
            if (!fully) continue;
            UUID resolver = sorted.stream()
                    .filter(s -> !s.getPeriodStart().isAfter(ge) && !s.getPeriodEnd().isBefore(ge))
                    .map(Statement::getId).findFirst().orElse(null);
            gap.setStatus(StatementGapStatus.CLOSED);
            gap.setClosedAt(java.time.Instant.now());
            gap.setResolvedByStatementId(resolver);
            statementGapRepository.save(gap);
            closed++;
            log.info("gap CLOSED client_inn={} bank={} period={}..{} by_statement={}",
                    client.getInn(), bank.getName(), gs, ge, resolver);
        }
        return closed;
    }

    public record ScanResult(int clientsScanned, int gapsFound, int gapsCreated, int gapsClosed, long durationMs) {}
    public record ClientScanResult(UUID clientId, int banksScanned, int gapsFound, int gapsCreated, int gapsClosed) {}

    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String message) { super(message); }
    }
}
