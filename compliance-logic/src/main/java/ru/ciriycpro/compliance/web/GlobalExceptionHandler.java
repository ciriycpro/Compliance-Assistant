package ru.ciriycpro.compliance.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import ru.ciriycpro.compliance.service.ClientService;
import ru.ciriycpro.compliance.service.CounterpartyService;
import ru.ciriycpro.compliance.service.DocumentService;
import ru.ciriycpro.compliance.service.MoneyOperationService;
import ru.ciriycpro.compliance.service.StatementService;

import java.time.Instant;
import java.util.Map;

/**
 * Глобальный обработчик исключений от Service-слоя.
 *
 * Маппинг типизированных бизнес-исключений на HTTP коды:
 *  - 404: *NotFoundException
 *  - 409: Duplicate*Exception
 *  - 422: Invalid*Exception, *OutOfRangeException, *DocumentTypeException
 *  - 400: общий IllegalArgumentException
 *  - 500: RuntimeException (всё остальное)
 *
 * Тело ответа: { error, message, timestamp }
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({
            ClientService.ClientNotFoundException.class,
            CounterpartyService.CounterpartyNotFoundException.class,
            CounterpartyService.ClientNotFoundException.class,
            DocumentService.ClientNotFoundException.class,
            StatementService.DocumentNotFoundException.class,
            StatementService.ClientNotFoundException.class,
            StatementService.BankNotFoundException.class,
            StatementService.StatementNotFoundException.class,
            MoneyOperationService.StatementNotFoundException.class,
            MoneyOperationService.ClientNotFoundException.class,
            MoneyOperationService.CounterpartyNotFoundException.class,
            MoneyOperationService.MoneyOperationNotFoundException.class
    })
    public ResponseEntity<Map<String, Object>> handleNotFound(RuntimeException e) {
        log.warn("404 NotFound: {}", e.getMessage());
        return error(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler({
            ClientService.DuplicateClientException.class,
            CounterpartyService.DuplicateCounterpartyException.class,
            DocumentService.DuplicateDocumentException.class,
            StatementService.DuplicateStatementException.class
    })
    public ResponseEntity<Map<String, Object>> handleConflict(RuntimeException e) {
        log.warn("409 Conflict: {}", e.getMessage());
        return error(HttpStatus.CONFLICT, "conflict", e.getMessage());
    }

    @ExceptionHandler({
            StatementService.InvalidDocumentTypeException.class,
            StatementService.InvalidPeriodException.class,
            MoneyOperationService.InvalidAmountException.class,
            MoneyOperationService.InvalidConfidenceException.class,
            MoneyOperationService.OperationDateOutOfRangeException.class
    })
    public ResponseEntity<Map<String, Object>> handleUnprocessable(RuntimeException e) {
        log.warn("422 Unprocessable: {}", e.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable_entity", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        log.warn("400 BadRequest: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", code,
                "message", message != null ? message : "",
                "timestamp", Instant.now().toString()
        ));
    }
}
