package com.darshan.ledger.common.error;

import com.darshan.ledger.common.web.CorrelationIdFilter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final String ERRORS = "errors";

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<Object> handleLedgerException(LedgerException ex, WebRequest request) {
        log.info("Request rejected: {} {}", ex.getErrorCode().code(), ex.getMessage());
        HttpHeaders headers = new HttpHeaders();
        if (ex.getStatus() == HttpStatus.UNAUTHORIZED) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return createResponseEntity(ProblemDetails.of(ex), headers, ex.getStatus(), request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<Object> handleRateLimit(RateLimitExceededException ex, WebRequest request) {
        log.info("Request rejected: {} {}", ex.getErrorCode().code(), ex.getMessage());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        return createResponseEntity(ProblemDetails.of(ex), headers, ex.getStatus(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLocking(OptimisticLockingFailureException ex, WebRequest request) {
        log.info("Optimistic lock conflict: {}", ex.getMessage());
        return respond(problem(HttpStatus.CONFLICT, CommonErrorCode.CONFLICT,
                "The record was modified by another request, please retry"), request);
    }

    // A row lock was not obtained within the configured lock timeout (or, should lock ordering ever
    // be broken, this transaction was the deadlock victim). Nothing was written; the client can retry.
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<Object> handlePessimisticLocking(PessimisticLockingFailureException ex, WebRequest request) {
        log.warn("Row lock not acquired: {}", ex.getMostSpecificCause().getMessage());
        return respond(problem(HttpStatus.CONFLICT, CommonErrorCode.LOCK_TIMEOUT,
                "The account is busy with another operation, please retry"), request);
    }

    // Reached only when two requests race past a service-level existence check; the services
    // report the common case with a specific error code first. The database message is not
    // logged because it repeats the offending values, which may be personal data.
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        log.info("Request rejected by database constraint {}", constraintName(ex));
        return respond(problem(HttpStatus.CONFLICT, CommonErrorCode.CONFLICT,
                "The request conflicts with existing data"), request);
    }

    // Without this handler the catch-all below would turn method-security denials into 500s.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return respond(problem(HttpStatus.FORBIDDEN, CommonErrorCode.ACCESS_DENIED, "Access denied"), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, CommonErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred"), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, CommonErrorCode.VALIDATION_FAILED,
                "Request validation failed");
        body.setProperty(ERRORS, violations);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    // Every ProblemDetail leaves through here, including the ones the framework builds for
    // malformed JSON, unsupported methods and unknown paths, so this is where the correlation id
    // and a default error code are stamped on.
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            problem.setProperty(ProblemDetails.CORRELATION_ID, MDC.get(CorrelationIdFilter.MDC_KEY));
            if (problem.getProperties() == null || !problem.getProperties().containsKey(ProblemDetails.ERROR_CODE)) {
                problem.setProperty(ProblemDetails.ERROR_CODE, defaultErrorCode(statusCode).code());
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> respond(ProblemDetail problem, WebRequest request) {
        return createResponseEntity(problem, new HttpHeaders(), HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCode errorCode, String detail) {
        return ProblemDetails.of(status, errorCode, detail);
    }

    private static String constraintName(DataIntegrityViolationException ex) {
        if (ex.getCause() instanceof ConstraintViolationException violation
                && violation.getConstraintName() != null) {
            return violation.getConstraintName();
        }
        return "unknown";
    }

    private static ErrorCode defaultErrorCode(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return CommonErrorCode.RESOURCE_NOT_FOUND;
        }
        if (status.is4xxClientError()) {
            return CommonErrorCode.MALFORMED_REQUEST;
        }
        return CommonErrorCode.INTERNAL_ERROR;
    }
}
