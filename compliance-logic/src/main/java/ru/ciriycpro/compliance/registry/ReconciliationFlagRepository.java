package ru.ciriycpro.compliance.registry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationFlagRepository extends JpaRepository<ReconciliationFlag, UUID> {

    List<ReconciliationFlag> findByClientId(UUID clientId);

    List<ReconciliationFlag> findByClientIdAndStatusNot(UUID clientId, ReconciliationFlagStatus status);

    List<ReconciliationFlag> findByStatusNot(ReconciliationFlagStatus status);

    Optional<ReconciliationFlag> findFirstByClientIdAndCounterpartyIdAndFlagTypeAndStatusNot(
            UUID clientId, UUID counterpartyId, ReconciliationFlagType flagType, ReconciliationFlagStatus status);

    // JOIN FETCH контрагента для реестра (иначе LazyInitializationException при сериализации)
    @Query("SELECT f FROM ReconciliationFlag f JOIN FETCH f.counterparty " +
           "WHERE f.client.id = :clientId AND f.status <> :status ORDER BY f.detectedAt DESC")
    List<ReconciliationFlag> findOpenWithCounterparty(@Param("clientId") UUID clientId,
                                                      @Param("status") ReconciliationFlagStatus status);

    @Query("SELECT f FROM ReconciliationFlag f JOIN FETCH f.counterparty " +
           "WHERE f.client.id = :clientId ORDER BY f.detectedAt DESC")
    List<ReconciliationFlag> findAllWithCounterparty(@Param("clientId") UUID clientId);
}
