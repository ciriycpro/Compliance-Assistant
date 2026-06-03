package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ciriycpro.compliance.registry.Client;
import ru.ciriycpro.compliance.registry.ClientRepository;
import ru.ciriycpro.compliance.registry.Counterparty;
import ru.ciriycpro.compliance.registry.CounterpartyRepository;
import ru.ciriycpro.compliance.registry.MoneyOperation;
import ru.ciriycpro.compliance.registry.MoneyOperationRepository;
import ru.ciriycpro.compliance.registry.OperationClass;
import ru.ciriycpro.compliance.registry.OperationDirection;
import ru.ciriycpro.compliance.registry.Statement;
import ru.ciriycpro.compliance.registry.StatementRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MoneyOperationService — управление операциями из банковских выписок.
 *
 * Бизнес-инварианты:
 *  - Связанный Statement и Client обязательны
 *  - Counterparty опциональный (может быть NULL если ИНН ещё не сматчен)
 *  - operation_date должен попадать в [statement.periodStart, statement.periodEnd]
 *  - amount > 0 (нулевые/отрицательные операции некорректны — direction задаёт знак)
 *  - parsing_confidence ∈ [0.0, 1.0]
 *
 * Methods for Reconciler (коммит 4):
 *  - linkToContract(operationId, contractId) — заполняет linked_contract_id
 *  - linkToAct(operationId, actId) — заполняет linked_act_id
 *  - findUnlinkedForClient(clientId) — ищет операции без linked_contract_id
 *
 * См. DEC-023 Registry v1.0.
 */
@Service
public class MoneyOperationService {

    private static final Logger log = LoggerFactory.getLogger(MoneyOperationService.class);

    private final MoneyOperationRepository moneyOperationRepository;
    private final StatementRepository statementRepository;
    private final ClientRepository clientRepository;
    private final CounterpartyRepository counterpartyRepository;

    public MoneyOperationService(MoneyOperationRepository moneyOperationRepository,
                                 StatementRepository statementRepository,
                                 ClientRepository clientRepository,
                                 CounterpartyRepository counterpartyRepository) {
        this.moneyOperationRepository = moneyOperationRepository;
        this.statementRepository = statementRepository;
        this.clientRepository = clientRepository;
        this.counterpartyRepository = counterpartyRepository;
    }

    @Transactional
    public MoneyOperation create(UUID statementId, UUID clientId, UUID counterpartyId,
                                 LocalDate operationDate, BigDecimal amount, OperationDirection direction,
                                 String purpose, String counterpartyInnRaw, String counterpartyNameRaw,
                                 String parsedContractNumber, LocalDate parsedContractDate,
                                 String parsedInvoiceNumber, String parsedSubject,
                                 String parsedSubjectCategory, BigDecimal parsedQuantity,
                                 String parsedUnit, BigDecimal parsedVatAmount,
                                 BigDecimal parsingConfidence, OperationClass operationClass) {

        if (amount == null || amount.signum() <= 0) {
            throw new InvalidAmountException("amount must be positive, got: " + amount);
        }
        if (parsingConfidence != null && (parsingConfidence.signum() < 0 || parsingConfidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new InvalidConfidenceException("parsing_confidence must be in [0.0, 1.0], got: " + parsingConfidence);
        }

        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new StatementNotFoundException("statement not found: " + statementId));

        if (operationDate.isBefore(statement.getPeriodStart()) || operationDate.isAfter(statement.getPeriodEnd())) {
            throw new OperationDateOutOfRangeException(
                    "operation_date " + operationDate + " is outside statement period [" +
                    statement.getPeriodStart() + "..." + statement.getPeriodEnd() + "]");
        }

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));

        Counterparty counterparty = null;
        if (counterpartyId != null) {
            counterparty = counterpartyRepository.findById(counterpartyId)
                    .orElseThrow(() -> new CounterpartyNotFoundException("counterparty not found: " + counterpartyId));
        }

        MoneyOperation op = new MoneyOperation();
        op.setStatement(statement);
        op.setClient(client);
        op.setCounterparty(counterparty);
        op.setOperationDate(operationDate);
        op.setAmount(amount);
        op.setDirection(direction);
        op.setPurpose(purpose);
        op.setCounterpartyInnRaw(counterpartyInnRaw);
        op.setCounterpartyNameRaw(counterpartyNameRaw);
        op.setParsedContractNumber(parsedContractNumber);
        op.setParsedContractDate(parsedContractDate);
        op.setParsedInvoiceNumber(parsedInvoiceNumber);
        op.setParsedSubject(parsedSubject);
        op.setParsedSubjectCategory(parsedSubjectCategory);
        op.setParsedQuantity(parsedQuantity);
        op.setParsedUnit(parsedUnit);
        op.setParsedVatAmount(parsedVatAmount);
        op.setParsingConfidence(parsingConfidence);
        op.setOperationClass(operationClass);

        MoneyOperation saved = moneyOperationRepository.save(op);
        log.info("money_operation created id={} client_inn={} stmt={} date={} dir={} amount={} category={} contract={}",
                saved.getId(), client.getInn(), statement.getId(), saved.getOperationDate(),
                saved.getDirection(), saved.getAmount(), saved.getParsedSubjectCategory(), saved.getParsedContractNumber());
        return saved;
    }

    public Optional<MoneyOperation> findById(UUID id) {
        return moneyOperationRepository.findById(id);
    }

    public Page<MoneyOperation> listForStatement(UUID statementId, Pageable pageable) {
        return moneyOperationRepository.findByStatementId(statementId, pageable);
    }

    public Page<MoneyOperation> listForClient(UUID clientId, UUID counterpartyId, Pageable pageable) {
        if (counterpartyId != null) {
            return moneyOperationRepository.findByClientIdAndCounterpartyId(clientId, counterpartyId, pageable);
        }
        return moneyOperationRepository.findByClientId(clientId, pageable);
    }

    public List<MoneyOperation> findUnlinkedForClient(UUID clientId) {
        return moneyOperationRepository.findByClientIdAndLinkedContractIdIsNull(clientId);
    }

    @Transactional
    public MoneyOperation linkToContract(UUID operationId, UUID contractId) {
        MoneyOperation op = moneyOperationRepository.findById(operationId)
                .orElseThrow(() -> new MoneyOperationNotFoundException("operation not found: " + operationId));

        UUID oldContractId = op.getLinkedContractId();
        op.setLinkedContractId(contractId);
        MoneyOperation saved = moneyOperationRepository.save(op);

        log.info("money_operation linked to contract id={} from={} to={}", saved.getId(), oldContractId, contractId);
        return saved;
    }

    @Transactional
    public MoneyOperation linkToAct(UUID operationId, UUID actId) {
        MoneyOperation op = moneyOperationRepository.findById(operationId)
                .orElseThrow(() -> new MoneyOperationNotFoundException("operation not found: " + operationId));

        UUID oldActId = op.getLinkedActId();
        op.setLinkedActId(actId);
        MoneyOperation saved = moneyOperationRepository.save(op);

        log.info("money_operation linked to act id={} from={} to={}", saved.getId(), oldActId, actId);
        return saved;
    }

    public static class StatementNotFoundException extends RuntimeException {
        public StatementNotFoundException(String message) { super(message); }
    }

    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String message) { super(message); }
    }

    public static class CounterpartyNotFoundException extends RuntimeException {
        public CounterpartyNotFoundException(String message) { super(message); }
    }

    public static class MoneyOperationNotFoundException extends RuntimeException {
        public MoneyOperationNotFoundException(String message) { super(message); }
    }

    public static class InvalidAmountException extends RuntimeException {
        public InvalidAmountException(String message) { super(message); }
    }

    public static class InvalidConfidenceException extends RuntimeException {
        public InvalidConfidenceException(String message) { super(message); }
    }

    public static class OperationDateOutOfRangeException extends RuntimeException {
        public OperationDateOutOfRangeException(String message) { super(message); }
    }
}
