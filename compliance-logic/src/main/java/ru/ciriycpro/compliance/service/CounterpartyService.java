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
import ru.ciriycpro.compliance.registry.CounterpartyTrustLevel;

import java.util.Optional;
import java.util.UUID;

/**
 * CounterpartyService — управление контрагентами клиента.
 *
 * Бизнес-инварианты:
 *  - Один контрагент на клиента уникален по ИНН (uk_counterparties_client_inn)
 *  - Trust level по умолчанию NEUTRAL
 *  - changeTrustLevel логирует переход (важно для аудита через Envers)
 *
 * См. DEC-023 Registry v1.0.
 */
@Service
public class CounterpartyService {

    private static final Logger log = LoggerFactory.getLogger(CounterpartyService.class);

    private final CounterpartyRepository counterpartyRepository;
    private final ClientRepository clientRepository;

    public CounterpartyService(CounterpartyRepository counterpartyRepository,
                               ClientRepository clientRepository) {
        this.counterpartyRepository = counterpartyRepository;
        this.clientRepository = clientRepository;
    }

    @Transactional
    public Counterparty create(UUID clientId, String inn, String name,
                               CounterpartyTrustLevel trustLevel, String notes) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));

        if (counterpartyRepository.existsByClientIdAndInn(clientId, inn)) {
            throw new DuplicateCounterpartyException(
                    "counterparty with inn=" + inn + " already exists for client=" + clientId);
        }

        Counterparty cp = new Counterparty();
        cp.setClient(client);
        cp.setInn(inn);
        cp.setName(name);
        cp.setTrustLevel(trustLevel != null ? trustLevel : CounterpartyTrustLevel.NEUTRAL);
        cp.setNotes(notes);

        Counterparty saved = counterpartyRepository.save(cp);
        log.info("counterparty created id={} client_id={} inn={} name={} trust={}",
                saved.getId(), client.getId(), saved.getInn(), saved.getName(), saved.getTrustLevel());
        return saved;
    }

    public Optional<Counterparty> findById(UUID id) {
        return counterpartyRepository.findById(id);
    }

    public Optional<Counterparty> findByClientAndInn(UUID clientId, String inn) {
        return counterpartyRepository.findByClientIdAndInn(clientId, inn);
    }

    public Page<Counterparty> listForClient(UUID clientId, CounterpartyTrustLevel trustLevel, Pageable pageable) {
        if (trustLevel != null) {
            return counterpartyRepository.findByClientIdAndTrustLevel(clientId, trustLevel, pageable);
        }
        return counterpartyRepository.findByClientId(clientId, pageable);
    }

    @Transactional
    public Counterparty changeTrustLevel(UUID id, CounterpartyTrustLevel newTrust, String reason) {
        Counterparty cp = counterpartyRepository.findById(id)
                .orElseThrow(() -> new CounterpartyNotFoundException("counterparty not found: " + id));

        CounterpartyTrustLevel oldTrust = cp.getTrustLevel();
        cp.setTrustLevel(newTrust);
        if (reason != null && !reason.isBlank()) {
            String existing = cp.getNotes() != null ? cp.getNotes() : "";
            cp.setNotes(existing + "\n[trust changed " + oldTrust + "→" + newTrust + "]: " + reason);
        }
        Counterparty saved = counterpartyRepository.save(cp);

        log.info("counterparty trust changed id={} inn={} from={} to={} reason={}",
                saved.getId(), saved.getInn(), oldTrust, newTrust, reason);
        return saved;
    }

    public static class CounterpartyNotFoundException extends RuntimeException {
        public CounterpartyNotFoundException(String message) { super(message); }
    }

    public static class DuplicateCounterpartyException extends RuntimeException {
        public DuplicateCounterpartyException(String message) { super(message); }
    }

    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String message) { super(message); }
    }
}
