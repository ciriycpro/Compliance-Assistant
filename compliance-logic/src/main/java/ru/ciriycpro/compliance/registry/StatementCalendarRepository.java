package ru.ciriycpro.compliance.registry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StatementCalendarRepository extends JpaRepository<StatementCalendar, UUID> {

    List<StatementCalendar> findByClientIdAndActiveTrue(UUID clientId);

    Optional<StatementCalendar> findByClientIdAndBankIdAndFrequency(UUID clientId, UUID bankId, StatementFrequency frequency);

    List<StatementCalendar> findByClientIdAndBankId(UUID clientId, UUID bankId);
}
