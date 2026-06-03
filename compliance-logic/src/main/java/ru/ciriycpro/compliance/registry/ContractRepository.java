package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractRepository extends JpaRepository<Contract, UUID> {

    Optional<Contract> findByDocumentId(UUID documentId);

    Page<Contract> findByClientId(UUID clientId, Pageable pageable);

    Page<Contract> findByClientIdAndCounterpartyId(UUID clientId, UUID counterpartyId, Pageable pageable);

    // KEY для Reconciler: «есть ли договор с этим контрагентом?»
    List<Contract> findByClientIdAndCounterpartyId(UUID clientId, UUID counterpartyId);
}
