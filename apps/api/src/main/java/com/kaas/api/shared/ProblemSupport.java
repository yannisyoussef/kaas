package com.kaas.api.shared;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ProblemSupport {
    public static final String REQUEST_ID_ATTRIBUTE = ProblemSupport.class.getName() + ".requestId";

    private final ObjectMapper objectMapper;

    public ProblemSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProblemDetail create(
            HttpServletRequest request, HttpStatus status, String code, String detail, List<FieldError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:kaas:problem:" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title(status));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("requestId", requestId(request));
        if (!errors.isEmpty() || "VALIDATION_FAILED".equals(code)) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        objectMapper.writeValue(response.getOutputStream(), create(request, status, code, detail, List.of()));
    }

    public static String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unavailable" : requestId.toString();
    }

    private static String title(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST, UNPROCESSABLE_ENTITY -> "Request validation failed";
            case UNAUTHORIZED -> "Authentication required";
            case FORBIDDEN -> "Access denied";
            case NOT_FOUND -> "Resource not found";
            case CONFLICT -> "Request conflict";
            case CONTENT_TOO_LARGE -> "Payload too large";
            default -> "Request failed";
        };
    }

    public record FieldError(String pointer, String message) {}
}
