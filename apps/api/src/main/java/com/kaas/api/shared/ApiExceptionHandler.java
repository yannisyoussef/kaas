package com.kaas.api.shared;

import com.kaas.api.controlplane.domain.SourcePolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.transaction.TransactionException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ProblemSupport problems;

    public ApiExceptionHandler(ProblemSupport problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException exception, HttpServletRequest request) {
        return response(request, exception.status(), exception.code(), exception.getMessage(), exception.errors());
    }

    @ExceptionHandler(SourcePolicy.SourceTooLargeException.class)
    ResponseEntity<ProblemDetail> sourceTooLarge(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.CONTENT_TOO_LARGE,
                "PAYLOAD_TOO_LARGE",
                "The feature source exceeds 524288 UTF-8 bytes.",
                List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> beanValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ProblemSupport.FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::safeFieldError)
                .sorted(Comparator.comparing(ProblemSupport.FieldError::pointer))
                .toList();
        return response(
                request,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "VALIDATION_FAILED",
                "One or more request fields are invalid.",
                errors);
    }

    @ExceptionHandler({
        ConstraintViolationException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class,
        HttpMediaTypeNotSupportedException.class
    })
    ResponseEntity<ProblemDetail> requestValidation(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.UNPROCESSABLE_CONTENT,
                "VALIDATION_FAILED",
                "The request is invalid.",
                List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> malformedJson(HttpMessageNotReadableException exception, HttpServletRequest request) {
        if (hasCause(exception, RequestTooLargeException.class)) {
            return response(
                    request,
                    HttpStatus.CONTENT_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE",
                    "The request body exceeds 1048576 bytes.",
                    List.of());
        }
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "The JSON request body is malformed.",
                List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> databaseConflict(
            HttpServletRequest request, DataIntegrityViolationException exception) {
        // Logged, because a 409 whose server side records nothing is unoperable: every database invariant in
        // this system surfaces here as the same opaque response, and "the request conflicts with existing
        // data" is true of a duplicate key, a lifecycle guard, and a tenant-ownership constraint alike.
        //
        // The CONSTRAINT NAME and SQLSTATE only — never the driver's message. PostgreSQL includes the failing
        // row's values in constraint-violation detail, so logging that text would put tenant data in the logs
        // as a side effect of a diagnostic.
        // SQLSTATE and the exception type, and nothing else. The JDBC driver is a runtime-only dependency, so
        // the PostgreSQL-specific accessor that would name the constraint is deliberately not on the compile
        // classpath — and the driver's message text, which does name it, also embeds the failing row's values.
        // SQLSTATE still separates a check violation (23514) from a duplicate key (23505) from a foreign-key
        // failure (23503), which is the distinction an operator needs first.
        Throwable cause = exception.getMostSpecificCause();
        String sqlState = cause instanceof SQLException sql ? sql.getSQLState() : null;
        LOGGER.atWarn()
                .addKeyValue("event", "DATABASE_CONSTRAINT_VIOLATED")
                .addKeyValue("sqlState", sqlState == null ? "UNKNOWN" : sqlState)
                .addKeyValue("exceptionType", cause.getClass().getName())
                .log("A database constraint refused the request");
        return response(
                request,
                HttpStatus.CONFLICT,
                "CONFLICT",
                "The request conflicts with existing data.",
                List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedOperation(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.METHOD_NOT_ALLOWED,
                "UNSUPPORTED_OPERATION",
                "The HTTP method is not supported for this resource.",
                List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> unknownResource(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "The requested resource was not found.",
                List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof DataAccessException || exception instanceof TransactionException) {
            // Spring composes these messages as "<task>; SQL [<statement>]; <server message>", so attaching the
            // cause would put the statement and the PostgreSQL trigger text into the log. Record the SQLSTATE
            // instead: it is diagnostic without disclosing schema internals.
            LOGGER.atError()
                    .addKeyValue("exceptionType", exception.getClass().getName())
                    .addKeyValue("sqlState", sqlState(exception))
                    .log("Database request failure");
            return response(
                    request,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR",
                    "The server could not complete the request.",
                    List.of());
        }
        LOGGER.atError()
                .addKeyValue("exceptionType", exception.getClass().getName())
                .setCause(exception)
                .log("Unhandled request failure");
        return response(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The server could not complete the request.",
                List.of());
    }

    private static String sqlState(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return "unknown";
    }

    private ResponseEntity<ProblemDetail> response(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String detail,
            List<ProblemSupport.FieldError> errors) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(problems.create(request, status, code, detail, errors));
    }

    private static ProblemSupport.FieldError safeFieldError(FieldError error) {
        return new ProblemSupport.FieldError("/" + error.getField().replace('.', '/'), "must be valid");
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> expected) {
        Throwable current = throwable;
        while (current != null) {
            if (expected.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
