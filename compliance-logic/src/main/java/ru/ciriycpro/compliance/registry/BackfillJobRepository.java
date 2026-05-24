package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BackfillJobRepository extends JpaRepository<BackfillJob, UUID> {

    Page<BackfillJob> findByClientId(UUID clientId, Pageable pageable);

    Page<BackfillJob> findByClientIdAndStatus(UUID clientId, BackfillJobStatus status, Pageable pageable);

    List<BackfillJob> findByStatus(BackfillJobStatus status);
}
