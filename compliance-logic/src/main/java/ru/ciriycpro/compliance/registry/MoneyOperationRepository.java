package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface MoneyOperationRepository extends JpaRepository<MoneyOperation, UUID> {

    Page<MoneyOperation> findByStatementId(UUID statementId, Pageable pageable);

    Page<MoneyOperation> findByClientId(UUID clientId, Pageable pageable);

    Page<MoneyOperation> findByClientIdAndCounterpartyId(UUID clientId, UUID counterpartyId, Pageable pageable);

    List<MoneyOperation> findByClientIdAndCounterpartyIdAndLinkedContractIdIsNull(UUID clientId, UUID counterpartyId);

    List<MoneyOperation> findByClientIdAndOperationDateBetween(UUID clientId, LocalDate from, LocalDate to);

    List<MoneyOperation> findByClientIdAndLinkedContractIdIsNull(UUID clientId);

    List<MoneyOperation> findByClientIdAndOperationClass(UUID clientId, OperationClass operationClass);

    /**
     * Агрегат COUNTERPARTY-операций клиента по контрагенту: [counterpartyId, count, sum(amount)].
     * Основа Reconciler'а — только реальные контрагенты, IS NOT NULL.
     */
    @Query("SELECT mo.counterparty.id, COUNT(mo), COALESCE(SUM(mo.amount), 0) " +
           "FROM MoneyOperation mo " +
           "WHERE mo.client.id = :clientId " +
           "AND mo.operationClass = ru.ciriycpro.compliance.registry.OperationClass.COUNTERPARTY " +
           "AND mo.counterparty IS NOT NULL " +
           "GROUP BY mo.counterparty.id")
    List<Object[]> aggregateCounterpartyOps(@Param("clientId") UUID clientId);
}
