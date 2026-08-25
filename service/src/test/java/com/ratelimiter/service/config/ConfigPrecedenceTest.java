package com.ratelimiter.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.api.AdminController;
import com.ratelimiter.service.api.RuleUpdateRequest;
import com.ratelimiter.service.rules.Algorithm;
import com.ratelimiter.service.rules.RuleCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Pins the config precedence contract: file &lt; env &lt; admin API.
 *
 * <p>The {@code file} value for {@code strict.limit} is {@code 5} (see
 * {@code application.yml}). This test overrides it to {@code 3} via a property
 * source (the {@code env} tier) and asserts that value is bootstrapped; then an
 * admin write of {@code 1} wins over both.
 */
@SpringBootTest
@TestPropertySource(properties = "ratelimiter.rules.strict.limit=3.0")
class ConfigPrecedenceTest {

    @Autowired
    private RuleCache cache;

    @Autowired
    private AdminController adminController;

    @Test
    void fileThenEnvThenAdminApi() {
        // env (3) overrides file (5).
        assertThat(cache.resolve("strict")).isPresent();
        assertThat(cache.resolve("strict").get().limit()).isEqualTo(3.0);

        // admin (1) overrides both file and env.
        adminController.put("strict", new RuleUpdateRequest(Algorithm.TOKEN_BUCKET, 1.0, 1.0, 0));
        assertThat(cache.resolve("strict").get().limit()).isEqualTo(1.0);
    }
}
