package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ciriycpro.compliance.registry.Act;
import ru.ciriycpro.compliance.registry.ActRepository;
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
 * ActService — управление актами (DEC-023 Коммит 4).
 * Инварианты: Document.type=ACT; один Document = один Act; contract (если задан) существует.
 */
@Service
public class ActService {

    private static final Logger log = LoggerFactory.getLogger(ActService.class);

    private final ActRepository actRepository;
    private final DocumentRepository documentRepository;
    private final ClientRepository clientRepository;
    private final CounterpartyRepository counterpartyRepository;
    private final ContractRepository contractRepository;

    public ActService(ActRepository actRepository,
                      DocumentRepository documentRepository,
                      ClientRepository clientRepository,
                      CounterpartyRepository counterpartyRepository,
                      ContractRepository contractRepository) {
        this.actRepository = actRepository;
        this.documentRepository = documentRepository;
        this.clientRepository = clientRepository;
        this.counterpartyRepository = counterpartyRepository;
        this.contractRepository = contractRepository;
    }

    @Transactional
    public Act create(UUID documentId, UUID clientId, UUID counterpartyId, UUID contractId,
                      String actNumber, LocalDate actDate, BigDecimal amount, String subject,
                      SigningStatus signingStatus) {

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("document not found: " + documentId));

        if (document.getType() != DocumentType.ACT) {
            throw new InvalidDocumentTypeException(
                    "document " + documentId + " has type=" + document.getType() + ", expected ACT");
        }

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));

        Counterparty counterparty = counterpartyRepository.findById(counterpartyId)
                .orElseThrow(() -> new CounterpartyNotFoundException("counterparty not found: " + counterpartyId));

        Contract contract = null;
        if (contractId != null) {
            contract = contractRepository.findById(contractId)
                    .orElseThrow(() -> new ContractNotFoundException("contract not found: " + contractId));
        }

        if (actRepository.findByDocumentId(documentId).isPresent()) {
            throw new DuplicateActException("act for document " + documentId + " already exists");
        }

        Act a = new Act();
        a.setDocument(document);
        a.setClient(client);
        a.setCounterparty(counterparty);
        a.setContract(contract);
        a.setActNumber(actNumber);
        a.setActDate(actDate);
        a.setAmount(amount);
        a.setSubject(subject);
        a.setSigningStatus(signingStatus != null ? signingStatus : SigningStatus.DRAFT);

        Act saved = actRepository.save(a);
        log.info("act created id={} client={} counterparty={} contract={} number={}",
                saved.getId(), clientId, counterpartyId, contractId, actNumber);
        return saved;
    }

    public Optional<Act> findById(UUID id) {
        return actRepository.findById(id);
    }

    public Page<Act> listForClient(UUID clientId, UUID counterpartyId, Pageable pageable) {
        if (counterpartyId != null) {
            return actRepository.findByClientIdAndCounterpartyId(clientId, counterpartyId, pageable);
        }
        return actRepository.findByClientId(clientId, pageable);
    }

    /** Для Reconciler: акты клиента с данным контрагентом. */
    public List<Act> findByClientAndCounterparty(UUID clientId, UUID counterpartyId) {
        return actRepository.findByClientIdAndCounterpartyId(clientId, counterpartyId);
    }

    public static class ActNotFoundException extends RuntimeException {
        public ActNotFoundException(String m) { super(m); }
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
    public static class ContractNotFoundException extends RuntimeException {
        public ContractNotFoundException(String m) { super(m); }
    }
    public static class DuplicateActException extends RuntimeException {
        public DuplicateActException(String m) { super(m); }
    }
    public static class InvalidDocumentTypeException extends RuntimeException {
        public InvalidDocumentTypeException(String m) { super(m); }
    }
}
