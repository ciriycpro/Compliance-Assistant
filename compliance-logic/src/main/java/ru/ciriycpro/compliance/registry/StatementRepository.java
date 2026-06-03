package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StatementRepository extends JpaRepository<Statement, UUID> {

    Page<Statement> findByClientId(UUID clientId, Pageable pageable);

    Page<Statement> findByClientIdAndBankId(UUID clientId, UUID bankId, Pageable pageable);

    Optional<Statement> findByDocumentId(UUID documentId);

    List<Statement> findByAccountNumber(String accountNumber);

    List<Statement> findByClientIdAndBankIdOrderByPeriodStartAsc(UUID clientId, UUID bankId);

    List<Statement> findByClientIdAndPeriodStartGreaterThanEqualAndPeriodEndLessThanEqual(
            UUID clientId, LocalDate from, LocalDate to);
}
