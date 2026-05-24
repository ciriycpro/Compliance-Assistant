package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ComplianceEventRepository extends JpaRepository<ComplianceEvent, UUID> {

    Optional<ComplianceEvent> findByEventId(UUID eventId);

    boolean existsByEventId(UUID eventId);

    Page<ComplianceEvent> findByClientInn(String clientInn, Pageable pageable);

    Page<ComplianceEvent> findByClientInnAndEventType(String clientInn, String eventType, Pageable pageable);

    Page<ComplianceEvent> findByTraceId(UUID traceId, Pageable pageable);

    List<ComplianceEvent> findByProcessedAtIsNull();
}
