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
public interface StatementGapRepository extends JpaRepository<StatementGap, UUID> {

    Page<StatementGap> findByClientId(UUID clientId, Pageable pageable);

    Page<StatementGap> findByClientIdAndStatus(UUID clientId, StatementGapStatus status, Pageable pageable);

    List<StatementGap> findByClientIdAndBankId(UUID clientId, UUID bankId);

    Optional<StatementGap> findByClientIdAndBankIdAndGapStartAndGapEnd(UUID clientId, UUID bankId, LocalDate gapStart, LocalDate gapEnd);

    List<StatementGap> findByStatus(StatementGapStatus status);
}
