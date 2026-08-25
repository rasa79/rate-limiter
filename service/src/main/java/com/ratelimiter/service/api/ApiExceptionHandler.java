package com.ratelimiter.service.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps service exceptions to the HTTP contract surface.
 *
 * <ul>
 *   <li>{@link RateLimitExceededException} → <b>429</b> with a {@code Retry-After}
 *       header and the same body shape as a 200 (allowed=false).</li>
 *   <li>{@link UnknownRuleException} → <b>400</b>.</li>
 *   <li>Malformed body ({@link HttpMessageNotReadableException}) and
 *       {@link IllegalArgumentException} → <b>400</b>.</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Emits the 429 rejection response, including the RFC 7231 {@code Retry-After}
     * header so a client can back off without parsing the body.
     *
     * @param ex the rejection exception
     * @return a 429 response with the decision body and Retry-After header
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<CheckResponse> handleRateLimitExceeded(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.decision().retryAfterSeconds()))
                .body(CheckResponse.from(ex.decision()));
    }

    /**
     * @param ex the unknown-rule exception
     * @return a 400 response naming the unknown rule
     */
    @ExceptionHandler(UnknownRuleException.class)
    public ResponseEntity<ErrorResponse> handleUnknownRule(UnknownRuleException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("unknown_rule", ex.getMessage()));
    }

    /**
     * @param ex the not-found exception (admin API reads of a missing rule)
     * @return a 404 response
     */
    @ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRuleNotFound(RuleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("rule_not_found", ex.getMessage()));
    }

    /**
     * @param ex a malformed-body or validation failure
     * @return a 400 response with the message
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("bad_request", ex.getMessage()));
    }
}
