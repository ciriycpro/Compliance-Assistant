package ru.ciriycpro.compliance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ciriycpro.compliance.registry.Client;
import ru.ciriycpro.compliance.registry.ClientRepository;
import ru.ciriycpro.compliance.registry.Counterparty;
import ru.ciriycpro.compliance.registry.Document;
import ru.ciriycpro.compliance.registry.DocumentSource;
import ru.ciriycpro.compliance.registry.DocumentType;
import ru.ciriycpro.compliance.registry.OperationClass;
import ru.ciriycpro.compliance.registry.OperationDirection;
import ru.ciriycpro.compliance.registry.Statement;
import ru.ciriycpro.compliance.registry.StatementRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * StatementIngestService — синхронный приём распарсенной выписки (почтовый пылесос, п.5), вариант A.
 *
 * Тонкая КООРДИНАЦИЯ уже существующих сервисов (не новый ингестор):
 *   резолв клиента (счёт-первичный, имя-бутстрап) + банк -> DocumentService -> StatementService -> MoneyOperationService -> scanClient.
 *
 * Резолв клиента (DEC: счёт-первичный, имя-бутстрап):
 *   1) по account_number — если счёт уже встречался (точно, обученная связка);
 *   2) иначе по ФАМИЛИИ владельца -> Client.fullName (бутстрап невиданного счёта; счёт привязывается этой выпиской).
 * Синхронно (не через ComplianceEvent): clientInn у события NOT NULL, а ВТБ ИНН владельца не несёт —
 * резолв обязан произойти на сервере ДО появления INN.
 */
@Service
public class StatementIngestService {

    private static final Logger log = LoggerFactory.getLogger(StatementIngestService.class);

    private static final Map<String, String> BANK_INN = Map.of(
            "vtb", "7702070139",
            "alfa", "7728168971");

    private static final Set<String> NAME_SKIP = Set.of("ИНДИВИДУАЛЬНЫЙ", "ПРЕДПРИНИМАТЕЛЬ");
    private static final Pattern TOKEN = Pattern.compile("[А-ЯЁA-Z][А-ЯЁA-Zа-яёa-z]{3,}");
    private static final DateTimeFormatter RU = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final ObjectMapper mapper;
    private final StatementRepository statementRepository;
    private final ClientRepository clientRepository;
    private final CounterpartyService counterpartyService;
    private final DocumentService documentService;
    private final StatementService statementService;
    private final MoneyOperationService moneyOperationService;
    private final StatementGapInspectorService inspectorService;

    public StatementIngestService(ObjectMapper mapper,
                                  StatementRepository statementRepository,
                                  ClientRepository clientRepository,
                                  CounterpartyService counterpartyService,
                                  DocumentService documentService,
                                  StatementService statementService,
                                  MoneyOperationService moneyOperationService,
                                  StatementGapInspectorService inspectorService) {
        this.mapper = mapper;
        this.statementRepository = statementRepository;
        this.clientRepository = clientRepository;
        this.counterpartyService = counterpartyService;
        this.documentService = documentService;
        this.statementService = statementService;
        this.moneyOperationService = moneyOperationService;
        this.inspectorService = inspectorService;
    }

    @Transactional
    public IngestResult ingest(byte[] fileBytes, String filename, String metaJson) throws Exception {
        JsonNode meta = mapper.readTree(metaJson);
        String account = text(meta, "account");
        String bank = text(meta, "bank");
        String ownerName = text(meta, "owner_name");
        LocalDate ps = date(meta, "period_start");
        LocalDate pe = date(meta, "period_end");
        String srcMsgId = text(meta, "source_message_id");

        Client client = resolveClient(account, ownerName);

        String bankInn = bank == null ? null : BANK_INN.get(bank.toLowerCase());
        if (bankInn == null) {
            throw new IllegalArgumentException("unknown bank: '" + bank + "' (ожидается vtb|alfa)");
        }
        Counterparty bankCp = counterpartyService.findByClientAndInn(client.getId(), bankInn)
                .orElseThrow(() -> new IllegalStateException(
                        "bank counterparty не найден: client=" + client.getInn() + " bankInn=" + bankInn));

        Document doc = documentService.createFromBytes(client.getId(), filename, mimeFor(filename),
                fileBytes, DocumentType.STATEMENT, DocumentSource.EMAIL, false);
        if (doc == null) {
            log.info("ingest dedup: файл уже загружен client={} file={}", client.getInn(), filename);
            return new IngestResult(client.getInn(), null, account, 0, 0, 0, true);
        }

        JsonNode opsNode = meta.get("operations");
        int opCount = (opsNode != null && opsNode.isArray()) ? opsNode.size() : 0;
        Statement stmt = statementService.create(doc.getId(), client.getId(), bankCp.getId(), bankCp.getName(),
                ps, pe, srcMsgId, null, opCount, "RUB", account);

        int created = 0, skipped = 0;
        if (opsNode != null && opsNode.isArray()) {
            for (JsonNode o : opsNode) {
                try {
                    LocalDate od = date(o, "operation_date");
                    if (od == null) { skipped++; continue; }
                    BigDecimal amt = o.hasNonNull("amount") ? new BigDecimal(o.get("amount").asText()) : null;
                    if (amt == null) { skipped++; continue; }
                    amt = amt.abs();
                    if (amt.signum() <= 0) { skipped++; continue; }
                    OperationDirection dir = "credit".equalsIgnoreCase(text(o, "direction"))
                            ? OperationDirection.CREDIT : OperationDirection.DEBIT;
                    OperationClass oc = opClass(text(o, "_category"));
                    moneyOperationService.create(stmt.getId(), client.getId(), null, od, amt, dir,
                            text(o, "purpose"), text(o, "counterparty_inn_raw"), text(o, "counterparty_name_raw"),
                            null, null, null, null, null, null, null, null, null, oc);
                    created++;
                } catch (RuntimeException ex) {
                    skipped++;
                    log.warn("ingest: операция пропущена client={} stmt={} причина={}",
                            client.getInn(), stmt.getId(), ex.getMessage());
                }
            }
        }

        int gapsClosed = inspectorService.scanClient(client.getId()).gapsClosed();

        log.info("statement ingested client={} stmt={} acct={} bank={} period={}..{} ops_created={} ops_skipped={} gaps_closed={}",
                client.getInn(), stmt.getId(), account, bank, ps, pe, created, skipped, gapsClosed);
        return new IngestResult(client.getInn(), stmt.getId(), account, created, skipped, gapsClosed, false);
    }

    private Client resolveClient(String account, String ownerName) {
        if (account != null && !account.isBlank()) {
            List<Statement> seen = statementRepository.findByAccountNumber(account);
            if (!seen.isEmpty()) {
                Client c = seen.get(0).getClient();
                log.info("resolve по счёту {} -> client {}", account, c.getInn());
                return c;
            }
        }
        String surname = surname(ownerName);
        if (surname == null) {
            throw new IllegalStateException(
                    "клиент не резолвится: счёт не встречался и нет фамилии владельца (owner='" + ownerName + "')");
        }
        List<Client> matches = new ArrayList<>();
        for (Client c : clientRepository.findAll()) {
            String fn = c.getFullName() == null ? "" : c.getFullName().toUpperCase();
            if (fn.contains(surname)) matches.add(c);
        }
        if (matches.size() == 1) {
            Client c = matches.get(0);
            log.info("resolve по фамилии {} -> client {} (бутстрап; счёт {} привязан)",
                    surname, c.getInn(), account);
            return c;
        }
        throw new IllegalStateException(
                "клиент не резолвится однозначно: фамилия='" + surname + "' совпадений=" + matches.size());
    }

    private static String surname(String name) {
        if (name == null) return null;
        Matcher m = TOKEN.matcher(name.toUpperCase());
        while (m.find()) {
            String tok = m.group();
            if (!NAME_SKIP.contains(tok)) return tok;
        }
        return null;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static LocalDate date(JsonNode n, String f) {
        String s = text(n, f);
        if (s == null) return null;
        s = s.trim();
        try { return LocalDate.parse(s); } catch (Exception ignore) { }
        try { return LocalDate.parse(s, RU); } catch (Exception ignore) { }
        throw new IllegalArgumentException("плохая дата: " + s);
    }

    private static OperationClass opClass(String cat) {
        if (cat == null || cat.isBlank()) return null;
        try { return OperationClass.valueOf(cat.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static String mimeFor(String fn) {
        if (fn == null) return "application/octet-stream";
        String l = fn.toLowerCase();
        if (l.endsWith(".pdf")) return "application/pdf";
        if (l.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (l.endsWith(".xls")) return "application/vnd.ms-excel";
        return "application/octet-stream";
    }

    public record IngestResult(String clientInn, UUID statementId, String accountNumber,
                               int opsCreated, int opsSkipped, int gapsClosed, boolean alreadyIngested) {}
}
