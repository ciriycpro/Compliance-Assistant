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

import java.time.LocalDate;
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

    public StatementGapInspectorService(ClientRepository clientRepository,
                                        CounterpartyRepository counterpartyRepository,
                                        StatementRepository statementRepository,
                                        StatementGapRepository statementGapRepository) {
        this.clientRepository = clientRepository;
        this.counterpartyRepository = counterpartyRepository;
        this.statementRepository = statementRepository;
        this.statementGapRepository = statementGapRepository;
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

        List<Client> activeClients = clientRepository.findAll().stream()
                .filter(c -> c.getStatus() == ClientStatus.ACTIVE)
                .toList();

        for (Client client : activeClients) {
            clientsScanned++;
            ClientScanResult cr = scanClient(client.getId());
            totalGapsFound += cr.gapsFound();
            totalGapsCreated += cr.gapsCreated();
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("inspector full scan completed clients={} gaps_found={} gaps_created={} duration_ms={}",
                clientsScanned, totalGapsFound, totalGapsCreated, durationMs);

        return new ScanResult(clientsScanned, totalGapsFound, totalGapsCreated, durationMs);
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

        for (Counterparty bank : banks) {
            List<Statement> bankStatements = statementRepository
                    .findByClientIdAndBankIdOrderByPeriodStartAsc(clientId, bank.getId());

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

        log.info("client scan completed client_inn={} banks={} gaps_found={} gaps_created={}",
                client.getInn(), banks.size(), gapsFound, gapsCreated);
        return new ClientScanResult(client.getId(), banks.size(), gapsFound, gapsCreated);
    }

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
                    statementGapRepository.save(gap);
                    log.info("gap DETECTED client_inn={} bank={} period={}..{} ({} days)",
                            client.getInn(), bank.getName(), gapStart, gapEnd,
                            java.time.temporal.ChronoUnit.DAYS.between(gapStart, gapEnd) + 1);
                    return true;
                });
    }

    public record ScanResult(int clientsScanned, int gapsFound, int gapsCreated, long durationMs) {}
    public record ClientScanResult(UUID clientId, int banksScanned, int gapsFound, int gapsCreated) {}

    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String message) { super(message); }
    }
}
