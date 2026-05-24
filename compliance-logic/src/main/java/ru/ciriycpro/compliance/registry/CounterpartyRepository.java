package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CounterpartyRepository extends JpaRepository<Counterparty, UUID> {

    Page<Counterparty> findByClientId(UUID clientId, Pageable pageable);

    Page<Counterparty> findByClientIdAndTrustLevel(UUID clientId, CounterpartyTrustLevel trustLevel, Pageable pageable);

    Optional<Counterparty> findByClientIdAndInn(UUID clientId, String inn);

    boolean existsByClientIdAndInn(UUID clientId, String inn);
}
