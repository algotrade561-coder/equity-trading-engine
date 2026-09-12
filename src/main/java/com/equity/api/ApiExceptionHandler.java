package com.equity.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns predictable bad input into a 400 that says what was wrong.
 *
 * <p>Without this, a malformed or missing user id reached {@code UUID.fromString} and surfaced as a
 * bare 500 with no message — the console could only report "HTTP 500", which says nothing about
 * whether the server is broken or the request was. An operator debugging a trading engine at 09:20
 * should not have to read a stack trace to learn that a field was empty.</p>
 *
 * <p>Only {@link IllegalArgumentException} and a controller's own
 * {@link org.springframework.web.server.ResponseStatusException} are mapped, deliberately. The first
 * is what the value types here throw for input they cannot accept; the second is a controller
 * choosing a status and saying why. Anything else genuinely is a server fault and keeps its 500,
 * because dressing an unexpected failure up as a client error is how real bugs get ignored.</p>
 *
 * <p>The second mapping exists because Spring's default body for a {@code ResponseStatusException}
 * omits the reason unless {@code server.error.include-message} is turned on globally — and turning
 * it on globally would also put stack-trace-adjacent detail from genuine 500s into API responses.
 * Rendering it here keeps the reason for the errors a controller wrote on purpose, and nothing else.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad request");
        body.put("message", describe(e));
        log.debug("rejected a request: {}", e.toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> withReason(org.springframework.web.server.ResponseStatusException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getStatusCode().toString());
        body.put("message", e.getReason() == null ? "request refused" : e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }

    /**
     * {@code UUID.fromString} throws "Invalid UUID string: ", which is accurate and useless. Say what
     * the caller actually has to do.
     */
    private static String describe(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.startsWith("Invalid UUID string")) {
            return "userId must be a UUID. Pick or generate one in the console first.";
        }
        if (message.isBlank()) {
            return "the request could not be understood";
        }
        return message;
    }
}
