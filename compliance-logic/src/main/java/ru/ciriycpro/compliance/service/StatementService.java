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
import ru.ciriycpro.compliance.registry.Document;
import ru.ciriycpro.compliance.registry.DocumentRepository;
import ru.ciriycpro.compliance.registry.DocumentType;
import ru.ciriycpro.compliance.registry.Statement;
import ru.ciriycpro.compliance.registry.StatementRepository;
import ru.ciriycpro.compliance.registry.StatementStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * StatementService — управление банковскими выписками.
 *
 * Бизнес-инварианты:
 *  - Связанный Document должен иметь type=STATEMENT (не Contract, не Act)
 *  - Один Document может иметь только одну Statement (uk_statements_document)
 *  - period_end >= period_start (валидация даты)
 *  - status: RECEIVED → PARSED → VERIFIED/FLAGGED (lifecycle)
 *
 * См. DEC-023 Registry v1.0.
 */
@Service
public class StatementService {

    private static final Logger log = LoggerFactory.getLogger(StatementService.class);

    private final StatementRepository statementRepository;
    private final DocumentRepository documentRepository;
    private final ClientRepository clientRepository;
    private final CounterpartyRepository counterpartyRepository;

    public StatementService(StatementRepository statementRepository,
                            DocumentRepository documentRepository,
                            ClientRepository clientRepository,
                            CounterpartyRepository counterpartyRepository) {
        this.statementRepository = statementRepository;
        this.documentRepository = documentRepository;
        this.clientRepository = clientRepository;
        this.counterpartyRepository = counterpartyRepository;
    }

    @Transactional
    public Statement create(UUID documentId, UUID clientId, UUID bankId, String bankName,
                            LocalDate periodStart, LocalDate periodEnd,
                            String sourceMessageId, BigDecimal amountTotal,
                            Integer operationCount, String currency) {

        if (periodStart != null && periodEnd != null && periodEnd.isBefore(periodStart)) {
            throw new InvalidPeriodException("period_end (" + periodEnd + ") is before period_start (" + periodStart + ")");
        }

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("document not found: " + documentId));

        if (document.getType() != DocumentType.STATEMENT) {
            throw new InvalidDocumentTypeException(
                    "document " + documentId + " has type=" + document.getType() + ", expected STATEMENT");
        }

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));

        Counterparty bank = counterpartyRepository.findById(bankId)
                .orElseThrow(() -> new BankNotFoundException("bank counterparty not found: " + bankId));

        if (statementRepository.findByDocumentId(documentId).isPresent()) {
            throw new DuplicateStatementException("statement already exists for document_id=" + documentId);
        }

        Statement st = new Statement();
        st.setDocument(document);
        st.setClient(client);
        st.setBank(bank);
        st.setBankName(bankName != null ? bankName : bank.getName());
        st.setPeriodStart(periodStart);
        st.setPeriodEnd(periodEnd);
        st.setSourceMessageId(sourceMessageId);
        st.setAmountTotal(amountTotal);
        st.setOperationCount(operationCount);
        st.setCurrency(currency != null ? currency : "RUB");

        Statement saved = statementRepository.save(st);
        log.info("statement created id={} client_inn={} bank={} period={}..{} amount_total={} ops={}",
                saved.getId(), client.getInn(), bank.getName(),
                saved.getPeriodStart(), saved.getPeriodEnd(),
                saved.getAmountTotal(), saved.getOperationCount());
        return saved;
    }

    public Optional<Statement> findById(UUID id) {
        return statementRepository.findById(id);
    }

    public Page<Statement> listForClient(UUID clientId, UUID bankId, Pageable pageable) {
        if (bankId != null) {
            return statementRepository.findByClientIdAndBankId(clientId, bankId, pageable);
        }
        return statementRepository.findByClientId(clientId, pageable);
    }

    @Transactional
    public Statement changeStatus(UUID id, StatementStatus newStatus) {
        Statement st = statementRepository.findById(id)
                .orElseThrow(() -> new StatementNotFoundException("statement not found: " + id));

        StatementStatus oldStatus = st.getStatus();
        st.setStatus(newStatus);
        Statement saved = statementRepository.save(st);

        log.info("statement status changed id={} from={} to={}", saved.getId(), oldStatus, newStatus);
        return saved;
    }

    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException(String message) { super(message); }
    }

    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String message) { super(message); }
    }

    public static class BankNotFoundException extends RuntimeException {
        public BankNotFoundException(String message) { super(message); }
    }

    public static class StatementNotFoundException extends RuntimeException {
        public StatementNotFoundException(String message) { super(message); }
    }

    public static class InvalidDocumentTypeException extends RuntimeException {
        public InvalidDocumentTypeException(String message) { super(message); }
    }

    public static class DuplicateStatementException extends RuntimeException {
        public DuplicateStatementException(String message) { super(message); }
    }

    public static class InvalidPeriodException extends RuntimeException {
        public InvalidPeriodException(String message) { super(message); }
    }
}
