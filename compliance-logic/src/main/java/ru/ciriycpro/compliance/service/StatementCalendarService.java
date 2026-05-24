package ru.ciriycpro.compliance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ciriycpro.compliance.registry.Client;
import ru.ciriycpro.compliance.registry.ClientRepository;
import ru.ciriycpro.compliance.registry.Counterparty;
import ru.ciriycpro.compliance.registry.CounterpartyRepository;
import ru.ciriycpro.compliance.registry.StatementCalendar;
import ru.ciriycpro.compliance.registry.StatementCalendarRepository;
import ru.ciriycpro.compliance.registry.StatementFrequency;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class StatementCalendarService {

    private static final Logger log = LoggerFactory.getLogger(StatementCalendarService.class);

    private final StatementCalendarRepository repository;
    private final ClientRepository clientRepository;
    private final CounterpartyRepository counterpartyRepository;

    public StatementCalendarService(StatementCalendarRepository repository,
                                     ClientRepository clientRepository,
                                     CounterpartyRepository counterpartyRepository) {
        this.repository = repository;
        this.clientRepository = clientRepository;
        this.counterpartyRepository = counterpartyRepository;
    }

    @Transactional
    public StatementCalendar create(UUID clientId, UUID bankId, StatementFrequency freq, LocalDate startPeriod, boolean active) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException("client not found: " + clientId));
        Counterparty bank = counterpartyRepository.findById(bankId)
                .orElseThrow(() -> new BankNotFoundException("bank (counterparty) not found: " + bankId));

        repository.findByClientIdAndBankIdAndFrequency(clientId, bankId, freq)
                .ifPresent(existing -> {
                    throw new DuplicateCalendarException(
                        "calendar already exists: client=" + clientId + " bank=" + bankId + " freq=" + freq);
                });

        StatementCalendar cal = new StatementCalendar();
        cal.setClient(client);
        cal.setBank(bank);
        cal.setFrequency(freq);
        cal.setStartPeriod(startPeriod);
        cal.setActive(active);
        StatementCalendar saved = repository.save(cal);
        log.info("statement calendar created id={} client_inn={} bank={} freq={} start={}",
                saved.getId(), client.getInn(), bank.getName(), freq, startPeriod);
        return saved;
    }

    public List<StatementCalendar> listForClient(UUID clientId) {
        return repository.findByClientIdAndActiveTrue(clientId);
    }

    public StatementCalendar findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new CalendarNotFoundException("calendar not found: " + id));
    }

    @Transactional
    public void deactivate(UUID id) {
        StatementCalendar cal = findById(id);
        cal.setActive(false);
        repository.save(cal);
        log.info("statement calendar deactivated id={}", id);
    }

    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException(String message) { super(message); }
    }
    public static class BankNotFoundException extends RuntimeException {
        public BankNotFoundException(String message) { super(message); }
    }
    public static class DuplicateCalendarException extends RuntimeException {
        public DuplicateCalendarException(String message) { super(message); }
    }
    public static class CalendarNotFoundException extends RuntimeException {
        public CalendarNotFoundException(String message) { super(message); }
    }
}
