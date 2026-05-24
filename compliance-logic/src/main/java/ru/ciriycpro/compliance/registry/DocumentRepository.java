package ru.ciriycpro.compliance.registry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByClientId(UUID clientId, Pageable pageable);

    Page<Document> findByClientIdAndType(UUID clientId, DocumentType type, Pageable pageable);

    Optional<Document> findByClientIdAndSha256(UUID clientId, String sha256);

    boolean existsByClientIdAndSha256(UUID clientId, String sha256);
}
