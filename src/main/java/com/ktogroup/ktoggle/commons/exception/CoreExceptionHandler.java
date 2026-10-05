package com.ktogroup.ktoggle.commons.exception;

import com.ktogroup.ktoggle.commons.exception.zdto.ErrorResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class CoreExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(KtoggleException.class)
    public ResponseEntity<ErrorResponse> handleKtoggleException(KtoggleException e) {
        if (e.status().is5xxServerError()) {
            log.error("{} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
        } else {
            log.warn("{} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.getMessage(), e.getMessageCode(), e.getData()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("Optimistic lock failure: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("Entity was modified concurrently, reload and retry", MessageCode.CONCURRENT_MODIFICATION));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("An unexpected error occurred.", MessageCode.UNEXPECTED_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        log.warn("Request validation failed: {}", errors);
        return ResponseEntity.badRequest().body(new ErrorResponse("Invalid request", MessageCode.VALIDATION_ERROR, errors));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of("Malformed request body", MessageCode.VALIDATION_ERROR));
    }
}
