package com.ratelimiter.example.flash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Example 2: a flash-sale app. Per-user fairness key + a shared payment key, with
 * the reference filter failing CLOSED only for the payment rule (money path) and
 * OPEN for per-user keys.
 */
@SpringBootApplication
public class FlashSaleApplication {

    /**
     * Boots the example.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(FlashSaleApplication.class, args);
    }
}
