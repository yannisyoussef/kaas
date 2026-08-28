package com.kaas.api.shared;

import java.util.List;
import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<ProblemSupport.FieldError> errors;

    private ApiException(HttpStatus status, String code, String detail, List<ProblemSupport.FieldError> errors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource was not found.", List.of());
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail, List.of());
    }

    /** Admission control. The detail deliberately states no counts, capacities, or other tenants' usage. */
    public static ApiException tooManyRequests(String code, String detail) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, detail, List.of());
    }

    public static ApiException validation(String pointer, String detail) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "VALIDATION_FAILED",
                detail,
                List.of(new ProblemSupport.FieldError(pointer, "must be valid")));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ProblemSupport.FieldError> errors() {
        return errors;
    }
}
