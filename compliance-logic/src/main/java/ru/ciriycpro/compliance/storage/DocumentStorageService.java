package ru.ciriycpro.compliance.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.ciriycpro.compliance.registry.DocumentType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Запись blob-файлов документов в filesystem coo.
 *
 * Структура: /var/lib/compliance-files/<client_inn>/<type_lowercase>/<uuid>_<original_filename>
 *
 * См. DEC-023 Document storage strategy + DEC-017 (152-ФЗ data residency).
 */
@Service
public class DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageService.class);

    private final Path baseDir;

    public DocumentStorageService(@Value("${compliance.storage.base-dir:/var/lib/compliance-files}") String baseDirPath) {
        this.baseDir = Path.of(baseDirPath);
        log.info("DocumentStorageService initialized with baseDir={}", baseDir);
    }

    public StorageResult store(String clientInn, DocumentType type, String originalFilename, byte[] content) throws IOException {
        String sha256 = computeSha256(content);
        String typeDir = type.name().toLowerCase(Locale.ROOT) + "s";
        Path clientDir = baseDir.resolve(clientInn).resolve(typeDir);
        Files.createDirectories(clientDir);

        String storedFilename = UUID.randomUUID() + "_" + sanitize(originalFilename);
        Path target = clientDir.resolve(storedFilename);
        Files.write(target, content, StandardOpenOption.CREATE_NEW);

        log.info("document stored client_inn={} type={} sha256={} path={} size={}", clientInn, type, sha256, target, content.length);

        return new StorageResult(target.toString(), sha256, (long) content.length);
    }

    public byte[] readBlob(String filePath) throws IOException {
        Path target = Path.of(filePath);
        if (!target.startsWith(baseDir)) {
            throw new SecurityException("path traversal attempt: " + filePath);
        }
        if (!Files.exists(target)) {
            throw new IOException("file not found: " + filePath);
        }
        return Files.readAllBytes(target);
    }

    public void deleteBlob(String filePath) {
        try {
            Path target = Path.of(filePath);
            if (!target.startsWith(baseDir)) {
                log.warn("delete refused: path outside baseDir filePath={}", filePath);
                return;
            }
            if (Files.deleteIfExists(target)) {
                log.info("deleted orphan blob filePath={}", filePath);
            }
        } catch (IOException e) {
            log.error("failed to delete blob filePath={}", filePath, e);
        }
    }

    public static String computeSha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) return "unnamed";
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public record StorageResult(String filePath, String sha256, long sizeBytes) {}
}
