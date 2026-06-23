package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ciriycpro.compliance.registry.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReconcilerService — ядро сверки (DEC-023 Коммит 4).
 *
 * reconcile(client): группирует COUNTERPARTY-операции по контрагенту; если по контрагенту
 *   нет договора (Contract) — поднимает/обновляет флаг MISSING_CONTRACT (идемпотентно).
 * rescanAll(): по открытым флагам проверяет, не появился ли договор — авто-закрывает (RESOLVED).
 *
 * Открытые MISSING_CONTRACT = «реестр отсутствующих договоров».
 * Уточнение основание/предмет (счёт vs договор, материалы/разовое) — следующий шаг,
 *   требует разбора назначения платежа.
 */
@Service
public class ReconcilerService {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerService.class);

    private final MoneyOperationRepository moRepo;
    private final ContractRepository contractRepo;
    private final ReconciliationFlagRepository flagRepo;
    private final ClientRepository clientRepo;
    private final CounterpartyRepository counterpartyRepo;

    public ReconcilerService(MoneyOperationRepository moRepo, ContractRepository contractRepo,
                             ReconciliationFlagRepository flagRepo, ClientRepository clientRepo,
                             CounterpartyRepository counterpartyRepo) {
        this.moRepo = moRepo;
        this.contractRepo = contractRepo;
        this.flagRepo = flagRepo;
        this.clientRepo = clientRepo;
        this.counterpartyRepo = counterpartyRepo;
    }

    // Основание: номер договора из назначения платежа.
    // 1) "...договор[а] № X..."  2) "...договор[а] <цифра...>"
    private static final Pattern P_CONTRACT = Pattern.compile(
            "догов\\w*\\s*(?:№|n|#|no)\\s*([^\\s,;\"']+)" +
            "|догов\\w*\\s+(\\d[\\w\\-/.]*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    static String extractContractNumber(String purpose) {
        if (purpose == null) return null;
        Matcher m = P_CONTRACT.matcher(purpose);
        if (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            if (v != null) {
                v = v.replaceAll("[).,;]+$", "").trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    private static boolean numberMatches(String ref, String contractNumber) {
        if (ref == null || contractNumber == null) return false;
        return ref.replaceAll("\\s", "").equalsIgnoreCase(contractNumber.replaceAll("\\s", ""));
    }

    @Transactional
    public ReconcileResult reconcile(UUID clientId) {
        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));

        // 1. COUNTERPARTY-операции -> группы по (контрагент + основание)
        List<MoneyOperation> ops = moRepo.findByClientIdAndOperationClass(clientId, OperationClass.COUNTERPARTY);
        Map<String, Group> groups = new HashMap<>();
        for (MoneyOperation op : ops) {
            if (op.getCounterparty() == null) continue;
            UUID cpId = op.getCounterparty().getId();
            String ref = extractContractNumber(op.getPurpose());
            String key = cpId + "|" + (ref == null ? "" : ref);
            Group g = groups.computeIfAbsent(key, k -> new Group(cpId, ref));
            g.count++;
            if (op.getAmount() != null) g.sum = g.sum.add(op.getAmount());
        }

        // 2. карта открытых флагов клиента для идемпотентности
        Map<String, ReconciliationFlag> openByKey = new HashMap<>();
        for (ReconciliationFlag f : flagRepo.findByClientIdAndStatusNot(clientId, ReconciliationFlagStatus.RESOLVED)) {
            openByKey.put(f.getCounterparty().getId() + "|" + (f.getContractRef() == null ? "" : f.getContractRef()), f);
        }

        int scanned = groups.size(), created = 0, existing = 0, covered = 0;
        for (Group g : groups.values()) {
            List<Contract> contracts = contractRepo.findByClientIdAndCounterpartyId(clientId, g.cpId);

            boolean isCovered;
            if (g.ref != null) {
                // основание есть -> нужен договор именно с этим номером
                isCovered = contracts.stream().anyMatch(c -> numberMatches(g.ref, c.getContractNumber()));
            } else {
                // ссылки на договор нет -> достаточно хоть одного договора по контрагенту
                isCovered = !contracts.isEmpty();
            }
            if (isCovered) { covered++; continue; }

            String key = g.cpId + "|" + (g.ref == null ? "" : g.ref);
            ReconciliationFlag f = openByKey.get(key);
            String details = g.ref != null
                    ? "Платежи со ссылкой на договор № " + g.ref + ", но такого договора нет: " + g.count + " оп., сумма " + g.sum
                    : "Платежи без ссылки на договор и без договора по контрагенту: " + g.count + " оп., сумма " + g.sum;
            if (f != null) {
                f.setOperationCount(g.count);
                f.setTotalAmount(g.sum);
                f.setDetails(details);
                existing++;
                continue;
            }
            Counterparty cp = counterpartyRepo.findById(g.cpId)
                    .orElseThrow(() -> new CounterpartyNotFoundException("counterparty not found: " + g.cpId));
            f = new ReconciliationFlag();
            f.setClient(client);
            f.setCounterparty(cp);
            f.setFlagType(ReconciliationFlagType.MISSING_CONTRACT);
            f.setStatus(ReconciliationFlagStatus.DETECTED);
            f.setContractRef(g.ref);
            f.setOperationCount(g.count);
            f.setTotalAmount(g.sum);
            f.setDetails(details);
            flagRepo.save(f);
            created++;
        }
        log.info("reconcile client={} groups={} flags_created={} flags_existing={} covered={}",
                clientId, scanned, created, existing, covered);
        return new ReconcileResult(scanned, created, existing);
    }

    private static class Group {
        final UUID cpId; final String ref; int count = 0; BigDecimal sum = BigDecimal.ZERO;
        Group(UUID cpId, String ref) { this.cpId = cpId; this.ref = ref; }
    }

    /** Ежедневный re-scan: открытые флаги авто-закрываются при появлении договора + bulk-линковка operations. */
    @Transactional
    public RescanResult rescanAll() {
        List<ReconciliationFlag> open = flagRepo.findByStatusNot(ReconciliationFlagStatus.RESOLVED);
        int closed = 0;
        int opsLinked = 0;
        for (ReconciliationFlag f : open) {
            if (f.getFlagType() != ReconciliationFlagType.MISSING_CONTRACT) continue;
            List<Contract> contracts = contractRepo.findByClientIdAndCounterpartyId(
                    f.getClient().getId(), f.getCounterparty().getId());
            boolean resolved = f.getContractRef() != null
                    ? contracts.stream().anyMatch(c -> numberMatches(f.getContractRef(), c.getContractNumber()))
                    : !contracts.isEmpty();
            if (resolved) {
                f.setStatus(ReconciliationFlagStatus.RESOLVED);
                f.setResolvedAt(Instant.now());
                closed++;
                opsLinked += linkOperationsForGroup(
                        f.getClient().getId(), f.getCounterparty().getId(), f.getContractRef(), contracts);
            }
        }
        log.info("reconciler rescan open={} closed={} ops_linked={}", open.size(), closed, opsLinked);
        return new RescanResult(open.size(), closed, opsLinked);
    }

    /** Узкий rescan для одного contract (вызывается post-ingest из ContractService.create). DEC-0028 #5. */
    @Transactional
    public RescanResult rescanForContract(UUID contractId) {
        Contract contract = contractRepo.findById(contractId).orElse(null);
        if (contract == null) {
            log.warn("rescanForContract: contract not found id={}", contractId);
            return new RescanResult(0, 0, 0);
        }
        UUID clientId = contract.getClient().getId();
        UUID counterpartyId = contract.getCounterparty().getId();
        List<Contract> contracts = contractRepo.findByClientIdAndCounterpartyId(clientId, counterpartyId);

        List<ReconciliationFlag> open = flagRepo.findByClientIdAndStatusNot(clientId, ReconciliationFlagStatus.RESOLVED);
        int closed = 0, opsLinked = 0;
        for (ReconciliationFlag f : open) {
            if (f.getFlagType() != ReconciliationFlagType.MISSING_CONTRACT) continue;
            if (!f.getCounterparty().getId().equals(counterpartyId)) continue;
            boolean resolved = f.getContractRef() != null
                    ? contracts.stream().anyMatch(c -> numberMatches(f.getContractRef(), c.getContractNumber()))
                    : !contracts.isEmpty();
            if (resolved) {
                f.setStatus(ReconciliationFlagStatus.RESOLVED);
                f.setResolvedAt(Instant.now());
                closed++;
                opsLinked += linkOperationsForGroup(clientId, counterpartyId, f.getContractRef(), contracts);
            }
        }
        // Edge case: contract создан раньше operations → флага нет, но линковать orphan'ов всё равно надо
        if (closed == 0) {
            opsLinked += linkOperationsForGroup(clientId, counterpartyId, null, contracts);
        }
        log.info("rescan_for_contract id={} flags_closed={} ops_linked={}", contractId, closed, opsLinked);
        return new RescanResult(open.size(), closed, opsLinked);
    }

    /**
     * Bulk-линковка orphan-operations (client+counterparty без linked_contract_id) к подходящему contract.
     * Docker-friendly: saveAll → один tx-flush.
     * Secure-by-design: cross-client + cross-counterparty defense in depth.
     * MAX_LINK_BATCH=1000 — защита от взрывного roll-up (chunking в backlog).
     */
    private int linkOperationsForGroup(UUID clientId, UUID counterpartyId, String groupRef, List<Contract> contracts) {
        if (contracts == null || contracts.isEmpty()) return 0;

        final int MAX_LINK_BATCH = 1000;
        List<MoneyOperation> orphans = moRepo.findByClientIdAndCounterpartyIdAndLinkedContractIdIsNull(clientId, counterpartyId);
        if (orphans.size() > MAX_LINK_BATCH) {
            log.warn("link_group: orphans={} truncated to MAX_LINK_BATCH={}", orphans.size(), MAX_LINK_BATCH);
            orphans = orphans.subList(0, MAX_LINK_BATCH);
        }

        List<MoneyOperation> toSave = new ArrayList<>();
        for (MoneyOperation op : orphans) {
            // Defense in depth
            if (!op.getClient().getId().equals(clientId)) {
                log.warn("link_group: cross-client op={} client={} expected={}", op.getId(), op.getClient().getId(), clientId);
                continue;
            }
            if (op.getCounterparty() == null || !op.getCounterparty().getId().equals(counterpartyId)) {
                log.warn("link_group: counterparty mismatch op={}", op.getId());
                continue;
            }
            Contract target = pickBestContract(op, groupRef, contracts);
            if (target == null) continue;
            op.setLinkedContractId(target.getId());
            toSave.add(op);
        }
        if (toSave.isEmpty()) return 0;
        moRepo.saveAll(toSave);
        log.info("link_group client={} counterparty={} group_ref={} linked={}",
                clientId, counterpartyId, groupRef == null ? "<any>" : groupRef, toSave.size());
        return toSave.size();
    }

    /** Выбор подходящего contract для operation: точное совпадение ref → группа → первый. */
    private Contract pickBestContract(MoneyOperation op, String groupRef, List<Contract> contracts) {
        if (contracts.isEmpty()) return null;
        String opRef = extractContractNumber(op.getPurpose());
        if (opRef != null) {
            return contracts.stream()
                    .filter(c -> numberMatches(opRef, c.getContractNumber()))
                    .findFirst().orElse(null);
        }
        if (groupRef != null) {
            return contracts.stream()
                    .filter(c -> numberMatches(groupRef, c.getContractNumber()))
                    .findFirst().orElse(null);
        }
        return contracts.get(0);
    }

    public List<ReconciliationFlag> openFlags(UUID clientId) {
        return flagRepo.findOpenWithCounterparty(clientId, ReconciliationFlagStatus.RESOLVED);
    }

    public List<ReconciliationFlag> allFlags(UUID clientId) {
        return flagRepo.findAllWithCounterparty(clientId);
    }

    public record ReconcileResult(int counterpartiesScanned, int flagsCreated, int flagsExisting) {}
    public record RescanResult(int openScanned, int closed, int opsLinked) {}

    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String m) { super(m); }
    }
    public static class CounterpartyNotFoundException extends RuntimeException {
        public CounterpartyNotFoundException(String m) { super(m); }
    }
}
