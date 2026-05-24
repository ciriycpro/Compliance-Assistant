package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface MoneyOperationRepository extends JpaRepository<MoneyOperation, UUID> {

    Page<MoneyOperation> findByStatementId(UUID statementId, Pageable pageable);

    Page<MoneyOperation> findByClientId(UUID clientId, Pageable pageable);

    Page<MoneyOperation> findByClientIdAndCounterpartyId(UUID clientId, UUID counterpartyId, Pageable pageable);

    List<MoneyOperation> findByClientIdAndOperationDateBetween(UUID clientId, LocalDate from, LocalDate to);

    List<MoneyOperation> findByClientIdAndLinkedContractIdIsNull(UUID clientId);
}
