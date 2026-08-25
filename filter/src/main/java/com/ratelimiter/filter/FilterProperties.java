package com.ratelimiter.filter;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the {@link RateLimitFilter}, bound from {@code ratelimiter.filter.*}.
 */
@ConfigurationProperties(prefix = "ratelimiter.filter")
public class FilterProperties {

    /** Base URL of the rate-limiter service, e.g. {@code http://localhost:8080}. */
    private String serviceUrl;

    private String checkPath = "/v1/check";

    /** Header carrying the rate-limit key (default {@code X-Api-Key}). */
    private String keyHeader = "X-Api-Key";

    /** Header carrying the rule name (default {@code X-Rate-Limit-Rule}). */
    private String ruleHeader = "X-Rate-Limit-Rule";

    /** Rule used when the rule header is absent/blank. */
    private String defaultRule;

    private int timeoutMillis = 1000;

    private FailMode failMode = FailMode.OPEN;

    /**
     * Rules that must fail CLOSED (reject) when the limiter is unavailable, even
     * though the default {@link #failMode} is OPEN (ADR-0001). Enables per-rule
     * asymmetry — e.g. a payment-gateway key fails closed, per-user keys fail open.
     */
    private List<String> failClosedRules = new ArrayList<>();

    private int circuitBreakerFailureRateThreshold = 50;
    private int circuitBreakerSlidingWindowSize = 10;
    private int circuitBreakerWaitDurationMillis = 10000;

    public String getServiceUrl() {
        return serviceUrl;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public String getCheckPath() {
        return checkPath;
    }

    public void setCheckPath(String checkPath) {
        this.checkPath = checkPath;
    }

    public String getKeyHeader() {
        return keyHeader;
    }

    public void setKeyHeader(String keyHeader) {
        this.keyHeader = keyHeader;
    }

    public String getRuleHeader() {
        return ruleHeader;
    }

    public void setRuleHeader(String ruleHeader) {
        this.ruleHeader = ruleHeader;
    }

    public String getDefaultRule() {
        return defaultRule;
    }

    public void setDefaultRule(String defaultRule) {
        this.defaultRule = defaultRule;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public FailMode getFailMode() {
        return failMode;
    }

    public void setFailMode(FailMode failMode) {
        this.failMode = failMode;
    }

    public List<String> getFailClosedRules() {
        return failClosedRules;
    }

    public void setFailClosedRules(List<String> failClosedRules) {
        this.failClosedRules = failClosedRules;
    }

    public int getCircuitBreakerFailureRateThreshold() {
        return circuitBreakerFailureRateThreshold;
    }

    public void setCircuitBreakerFailureRateThreshold(int circuitBreakerFailureRateThreshold) {
        this.circuitBreakerFailureRateThreshold = circuitBreakerFailureRateThreshold;
    }

    public int getCircuitBreakerSlidingWindowSize() {
        return circuitBreakerSlidingWindowSize;
    }

    public void setCircuitBreakerSlidingWindowSize(int circuitBreakerSlidingWindowSize) {
        this.circuitBreakerSlidingWindowSize = circuitBreakerSlidingWindowSize;
    }

    public int getCircuitBreakerWaitDurationMillis() {
        return circuitBreakerWaitDurationMillis;
    }

    public void setCircuitBreakerWaitDurationMillis(int circuitBreakerWaitDurationMillis) {
        this.circuitBreakerWaitDurationMillis = circuitBreakerWaitDurationMillis;
    }
}
