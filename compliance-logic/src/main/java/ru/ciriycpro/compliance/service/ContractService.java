package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ciriycpro.compliance.registry.Client;
import ru.ciriycpro.compliance.registry.ClientRepository;
import ru.ciriycpro.compliance.registry.Contract;
import ru.ciriycpro.compliance.registry.ContractRepository;
import ru.ciriycpro.compliance.registry.Counterparty;
import ru.ciriycpro.compliance.registry.CounterpartyRepository;
import ru.ciriycpro.compliance.registry.Document;
import ru.ciriycpro.compliance.registry.DocumentRepository;
import ru.ciriycpro.compliance.registry.DocumentType;
import ru.ciriycpro.compliance.registry.SigningStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ContractService — управление договорами (DEC-023 Коммит 4).
 *
 * Инварианты:
 *  - связанный Document должен иметь type=CONTRACT
 *  - один Document = один Contract (uk_contracts_document)
 *  - valid_to >= valid_from
 * Метод findActiveByClientAndCounterparty используется Reconciler'ом.
 */
@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    private final ContractRepository contractRepository;
    private final DocumentRepository documentRepository;
    private final ClientRepository clientRepository;
    private final CounterpartyRepository counterpartyRepository;
    private final ReconcilerService reconcilerService;

    public ContractService(ContractRepository contractRepository,
                           DocumentRepository documentRepository,
                           ClientRepository clientRepository,
                           CounterpartyRepository counterpartyRepository,
                           ReconcilerService reconcilerService) {
        this.contractRepository = contractRepository;
        this.documentRepository = documentRepository;
        this.clientRepository = clientRepository;
        this.counterpartyRepository = counterpartyRepository;
        this.reconcilerService = reconcilerService;
    }

    @Transactional
    public Contract create(UUID documentId, UUID clientId, UUID counterpartyId,
                           String contractNumber, LocalDate contractDate,
                           LocalDate validFrom, LocalDate validTo,
                           String subject, String subjectCategory, BigDecimal amount,
                           SigningStatus signingStatus) {

        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            throw new InvalidPeriodException("valid_to (" + validTo + ") is before valid_from (" + validFrom + ")");
        }

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("document not found: " + documentId));

        if (document.getType() != DocumentType.CONTRACT) {
            throw new InvalidDocumentTypeException(
                    "document " + documentId + " has type=" + document.getType() + ", expected CONTRACT");
        }

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));

        Counterparty counterparty = counterpartyRepository.findById(counterpartyId)
                .orElseThrow(() -> new CounterpartyNotFoundException("counterparty not found: " + counterpartyId));

        if (contractRepository.findByDocumentId(documentId).isPresent()) {
            throw new DuplicateContractException("contract for document " + documentId + " already exists");
        }

        Contract c = new Contract();
        c.setDocument(document);
        c.setClient(client);
        c.setCounterparty(counterparty);
        c.setContractNumber(contractNumber);
        c.setContractDate(contractDate);
        c.setValidFrom(validFrom);
        c.setValidTo(validTo);
        c.setSubject(subject);
        c.setSubjectCategory(subjectCategory);
        c.setAmount(amount);
        c.setSigningStatus(signingStatus != null ? signingStatus : SigningStatus.DRAFT);

        Contract saved = contractRepository.save(c);
        log.info("contract created id={} client={} counterparty={} number={} signing={}",
                saved.getId(), clientId, counterpartyId, contractNumber, saved.getSigningStatus());

        // Post-ingest hook: моментальная линковка operations к новому contract (DEC-0028 #5)
        ReconcilerService.RescanResult rs = reconcilerService.rescanForContract(saved.getId());
        log.info("contract post-ingest reconcile contract_id={} flags_closed={} ops_linked={}",
                saved.getId(), rs.closed(), rs.opsLinked());

        return saved;
    }

    public Optional<Contract> findById(UUID id) {
        return contractRepository.findById(id);
    }

    public Page<Contract> listForClient(UUID clientId, UUID counterpartyId, Pageable pageable) {
        if (counterpartyId != null) {
            return contractRepository.findByClientIdAndCounterpartyId(clientId, counterpartyId, pageable);
        }
        return contractRepository.findByClientId(clientId, pageable);
    }

    /** Для Reconciler: все договоры клиента с данным контрагентом. */
    public List<Contract> findByClientAndCounterparty(UUID clientId, UUID counterpartyId) {
        return contractRepository.findByClientIdAndCounterpartyId(clientId, counterpartyId);
    }

    public static class ContractNotFoundException extends RuntimeException {
        public ContractNotFoundException(String m) { super(m); }
    }
    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException(String m) { super(m); }
    }
    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String m) { super(m); }
    }
    public static class CounterpartyNotFoundException extends RuntimeException {
        public CounterpartyNotFoundException(String m) { super(m); }
    }
    public static class DuplicateContractException extends RuntimeException {
        public DuplicateContractException(String m) { super(m); }
    }
    public static class InvalidDocumentTypeException extends RuntimeException {
        public InvalidDocumentTypeException(String m) { super(m); }
    }
    public static class InvalidPeriodException extends RuntimeException {
        public InvalidPeriodException(String m) { super(m); }
    }
}
