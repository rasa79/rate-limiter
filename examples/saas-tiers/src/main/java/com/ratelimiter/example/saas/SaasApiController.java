package com.ratelimiter.example.saas;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tenant-facing endpoint, protected by the {@code RateLimitFilter}.
 */
@RestController
@RequestMapping("/api")
public class SaasApiController {

    /**
     * @return a tiny payload; the filter (and thus the tenant's tier) is what
     *         gates this call
     */
    @GetMapping("/data")
    public Map<String, String> data() {
        return Map.of("data", "ok");
    }
}
