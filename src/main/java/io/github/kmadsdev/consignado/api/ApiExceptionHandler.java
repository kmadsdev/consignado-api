package io.github.kmadsdev.consignado.api;

import io.github.kmadsdev.consignado.application.ApplicationException;
import io.github.kmadsdev.consignado.application.ConflictException;
import io.github.kmadsdev.consignado.application.IdempotencyKeyReuseException;
import io.github.kmadsdev.consignado.application.ResourceNotFoundException;
import io.github.kmadsdev.consignado.application.VersionMismatchException;
import io.github.kmadsdev.consignado.domain.BusinessRuleViolationException;
import io.github.kmadsdev.consignado.domain.IllegalStatusTransitionException;
import io.github.kmadsdev.consignado.domain.IncomeBelowCommittedMarginException;
import io.github.kmadsdev.consignado.domain.MarginExceededException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves as RFC 9457 (formerly 7807) problem details with a stable
 * {@code code}. Messages are written for clients: no stack traces, SQL or personal data.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String TYPE_PREFIX = "urn:consignado:problem:";
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail notFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.code(), "Resource not found", ex.getMessage());
    }

    @ExceptionHandler(MarginExceededException.class)
    ProblemDetail marginExceeded(MarginExceededException ex) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), "Margin exceeded", ex.getMessage());
        problem.setProperty("availableMargin", ex.availableMargin());
        problem.setProperty("installment", ex.installment());
        return problem;
    }

    @ExceptionHandler(IncomeBelowCommittedMarginException.class)
    ProblemDetail incomeBelowCommittedMargin(IncomeBelowCommittedMarginException ex) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), "Income below committed margin",
                ex.getMessage());
        problem.setProperty("marginLimit", ex.marginLimit());
        problem.setProperty("committedInstallments", ex.committedInstallments());
        return problem;
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ProblemDetail businessRule(BusinessRuleViolationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), "Business rule violated", ex.getMessage());
    }

    @ExceptionHandler(IllegalStatusTransitionException.class)
    ProblemDetail illegalTransition(IllegalStatusTransitionException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, ex.code(), "Illegal status transition", ex.getMessage());
        problem.setProperty("currentStatus", ex.from());
        problem.setProperty("requestedStatus", ex.to());
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, ex.code(), "Conflict", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail idempotencyKeyReused(ApplicationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.code(), "Idempotency-Key reused", ex.getMessage());
    }

    @ExceptionHandler(VersionMismatchException.class)
    ProblemDetail versionMismatch(VersionMismatchException ex) {
        return problem(HttpStatus.PRECONDITION_FAILED, ex.code(), "Precondition failed", ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "concurrent-update", "Concurrent update",
                "The resource changed while this request ran. Read it again and retry.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataIntegrity(DataIntegrityViolationException ex) {
        // The driver message can contain column values; log the class only, never echo it.
        log.warn("Data integrity violation: {}", ex.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "data-conflict", "Conflict",
                "The request conflicts with existing data");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "Unexpected error. Quote the correlationId when you report it.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException ex, @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "One or more fields are not valid");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            @NonNull HandlerMethodValidationException ex, @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        // When a handler has constraints on plain parameters (the Idempotency-Key header),
        // Spring validates the whole method and reports @Valid body errors here as well.
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result instanceof ParameterErrors bodyErrors
                        ? bodyErrors.getFieldErrors().stream().map(error -> Map.of(
                                "field", error.getField(),
                                "message", String.valueOf(error.getDefaultMessage())))
                        : result.getResolvableErrors().stream().map(error -> Map.of(
                                "field", String.valueOf(result.getMethodParameter().getParameterName()),
                                "message", String.valueOf(error.getDefaultMessage()))))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "One or more parameters are not valid");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Adds the correlation id to the problems that Spring MVC builds itself (400, 404, 405...). */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, @NonNull HttpHeaders headers, @NonNull HttpStatusCode statusCode, @NonNull WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            addCorrelationId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setTitle(title);
        problem.setProperty("code", code);
        addCorrelationId(problem);
        return problem;
    }

    private static void addCorrelationId(ProblemDetail problem) {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }
}
