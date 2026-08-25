package com.ratelimiter.filter;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The reference integration: a servlet filter that asks the rate-limiter service
 * whether to allow each request.
 *
 * <p>It extracts the key and rule from configurable headers (or uses the
 * configured default rule), calls {@link RateLimitChecker}, short-circuits a
 * rejection as HTTP 429 with a {@code Retry-After} header, and applies the
 * configured {@link FailMode} when the limiter is unavailable. A Resilience4j
 * {@link CircuitBreaker} isolates the limiter: repeated failures open the breaker
 * and stop hammering an unhealthy service.
 *
 * <p>// RATIONALE: fail-open is the default because the common case is
 * availability bias; a money path can opt into fail-closed (ADR-0001). The
 * circuit breaker adds "don't keep calling a dead limiter", and the filter's
 * behaviour when it is open follows the same fail mode.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitChecker checker;
    private final FilterProperties properties;
    private final CircuitBreaker circuitBreaker;

    /**
     * @param checker the rate-limit decision source
     * @param properties the filter configuration
     * @param circuitBreaker isolates the limiter
     */
    public RateLimitFilter(RateLimitChecker checker, FilterProperties properties,
            CircuitBreaker circuitBreaker) {
        this.checker = checker;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader(properties.getKeyHeader());
        String rule = request.getHeader(properties.getRuleHeader());
        if (rule == null || rule.isBlank()) {
            rule = properties.getDefaultRule();
        }

        // No key or no rule means there is nothing to rate-limit: pass through
        // (fail-open). Clients that want to be limited set the headers.
        if (key == null || key.isBlank() || rule == null || rule.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (!circuitBreaker.tryAcquirePermission()) {
                applyFailMode(request, response, filterChain);
                return;
            }
            CheckResult result = checker.check(key, rule);
            circuitBreaker.onSuccess(0, TimeUnit.MILLISECONDS);
            if (result.allowed()) {
                filterChain.doFilter(request, response);
            } else {
                writeRejected(response, result.retryAfterSeconds());
            }
        } catch (Exception ex) {
            circuitBreaker.onError(0, TimeUnit.MILLISECONDS,
                    ex instanceof IOException ? ex : new RuntimeException(ex));
            applyFailMode(request, response, filterChain);
        }
    }

    private void applyFailMode(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        if (properties.getFailMode() == FailMode.CLOSED) {
            writeRejected(response, 0);
        } else {
            // RATIONALE: fail-open keeps the request flowing when the limiter is down.
            chain.doFilter(request, response);
        }
    }

    private void writeRejected(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
        response.getWriter().write("rate limit exceeded");
    }
}
