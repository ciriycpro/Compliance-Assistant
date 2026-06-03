package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StatementGapRepository extends JpaRepository<StatementGap, UUID> {

    /**
     * Claim просроченных дыр под обработку оркестратором.
     * FOR UPDATE SKIP LOCKED (lock.timeout=-2): N реплик не возьмут одну дыру дважды.
     * Должен вызываться внутри @Transactional.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT g FROM StatementGap g WHERE g.tenantId = :tenantId AND g.status IN :statuses AND g.nextActionAt <= :now ORDER BY g.nextActionAt")
    List<StatementGap> claimDue(@Param("tenantId") UUID tenantId,
                                @Param("statuses") List<StatementGapStatus> statuses,
                                @Param("now") Instant now);

    Page<StatementGap> findByClientId(UUID clientId, Pageable pageable);

    Page<StatementGap> findByClientIdAndStatus(UUID clientId, StatementGapStatus status, Pageable pageable);

    List<StatementGap> findByClientIdAndBankId(UUID clientId, UUID bankId);

    Optional<StatementGap> findByClientIdAndBankIdAndGapStartAndGapEnd(UUID clientId, UUID bankId, LocalDate gapStart, LocalDate gapEnd);

    List<StatementGap> findByStatus(StatementGapStatus status);
}
