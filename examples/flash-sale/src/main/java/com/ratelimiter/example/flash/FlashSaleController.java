package com.ratelimiter.example.flash;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The flash-sale endpoints. Rate limiting is enforced by the reference filter:
 * a purchase is limited per-user (rule {@code user}, fail-open); a payment is
 * limited by the shared {@code payment} rule, which the filter fails CLOSED on
 * (money path) per ADR-0001.
 */
@RestController
@RequestMapping("/api")
public class FlashSaleController {

    /**
     * A user attempts to buy. Per-user fairness key: the filter limits by
     * {@code user} rule (fail-open when the limiter is down).
     *
     * @param user the user id (from the {@code X-Api-Key} header)
     * @return a confirmation
     */
    @PostMapping("/purchase")
    public Map<String, String> purchase(@RequestHeader(value = "X-Api-Key", required = false) String user) {
        return Map.of("status", "ok", "user", user == null ? "anon" : user);
    }

    /**
     * A payment attempt. Fails CLOSED: if the limiter is down the request is
     * rejected (money path must not proceed unchecked).
     *
     * @return a confirmation
     */
    @PostMapping("/pay")
    public Map<String, String> pay() {
        return Map.of("status", "paid");
    }
}
