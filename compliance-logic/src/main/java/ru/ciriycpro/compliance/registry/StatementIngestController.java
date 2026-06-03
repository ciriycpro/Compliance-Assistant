package ru.ciriycpro.compliance.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.ciriycpro.compliance.service.StatementIngestService;

import java.util.Map;

/**
 * POST /statements/ingest — приём распарсенной выписки из почтового пылесоса (п.5), вариант A.
 * multipart/form-data: file (xlsx|pdf) + meta (JSON-секция из parser-service /parse-statement, одна statement).
 * Резолвит клиента/банк сам, создаёт Document+Statement+операции, закрывает дыры. Идемпотентность по sha256 файла.
 */
@RestController
public class StatementIngestController {

    private static final Logger log = LoggerFactory.getLogger(StatementIngestController.class);

    private final StatementIngestService ingestService;

    public StatementIngestController(StatementIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(value = "/statements/ingest", consumes = {"multipart/form-data"})
    public ResponseEntity<?> ingest(@RequestParam("file") MultipartFile file,
                                    @RequestParam("meta") String meta) {
        try {
            StatementIngestService.IngestResult r =
                    ingestService.ingest(file.getBytes(), file.getOriginalFilename(), meta);
            return ResponseEntity.ok(r);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("ingest отклонён: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            log.error("ingest упал: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
