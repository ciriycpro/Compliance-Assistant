package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActRepository extends JpaRepository<Act, UUID> {

    Optional<Act> findByDocumentId(UUID documentId);

    Page<Act> findByClientId(UUID clientId, Pageable pageable);

    Page<Act> findByClientIdAndCounterpartyId(UUID clientId, UUID counterpartyId, Pageable pageable);

    // для Reconciler: акты клиента с контрагентом / под договором
    List<Act> findByClientIdAndCounterpartyId(UUID clientId, UUID counterpartyId);

    List<Act> findByContractId(UUID contractId);
}
