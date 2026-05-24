package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ciriycpro.compliance.registry.Client;
import ru.ciriycpro.compliance.registry.ClientRepository;
import ru.ciriycpro.compliance.registry.ClientStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * ClientService — бизнес-сервис управления клиентами compliance-системы.
 *
 * Архитектурно: слой между Controller (HTTP) и Repository (JPA). Содержит
 * бизнес-инварианты, проверки, транзакционность. Controller только мапит DTO ↔ entity.
 *
 * См. DEC-023 Spring Boot tier, паттерн ReestrService.
 */
@Service
public class ClientService {

    private static final Logger log = LoggerFactory.getLogger(ClientService.class);

    private final ClientRepository clientRepository;

    public ClientService(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    @Transactional
    public Client create(String inn, String fullName, String phone) {
        if (clientRepository.existsByInn(inn)) {
            throw new DuplicateClientException("client with inn=" + inn + " already exists");
        }

        Client client = new Client();
        client.setInn(inn);
        client.setFullName(fullName);
        client.setPhone(phone);
        client.setStatus(ClientStatus.ACTIVE);

        Client saved = clientRepository.save(client);
        log.info("client created id={} inn={} status={}", saved.getId(), saved.getInn(), saved.getStatus());
        return saved;
    }

    public Optional<Client> findById(UUID id) {
        return clientRepository.findById(id);
    }

    public Optional<Client> findByInn(String inn) {
        return clientRepository.findByInn(inn);
    }

    @Transactional
    public Client updateStatus(UUID id, ClientStatus newStatus) {
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + id));

        ClientStatus oldStatus = client.getStatus();
        client.setStatus(newStatus);
        Client saved = clientRepository.save(client);

        log.info("client status changed id={} inn={} from={} to={}",
                saved.getId(), saved.getInn(), oldStatus, newStatus);
        return saved;
    }

    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String message) { super(message); }
    }

    public static class DuplicateClientException extends RuntimeException {
        public DuplicateClientException(String message) { super(message); }
    }
}
