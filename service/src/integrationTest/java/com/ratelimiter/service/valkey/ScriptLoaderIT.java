package com.ratelimiter.service.valkey;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.ratelimiter.service.support.ValkeyIntegrationTestBase;

/**
 * Proves the EVALSHA-with-EVAL-fallback contract: after a {@code SCRIPT FLUSH},
 * a call must transparently fall back to {@code EVAL} and re-register the script
 * so the next call uses {@code EVALSHA} again (and readiness sees it loaded).
 */
class ScriptLoaderIT extends ValkeyIntegrationTestBase {

    @Autowired
    private ScriptLoader scriptLoader;

    @Autowired
    private StatefulRedisConnection<String, String> connection;

    private List<String> evalSha(String key) {
        return scriptLoader.tokenBucket(key, 5, 1, 1.0, 1_800_000_000_000L);
    }

    @Test
    void noscriptFallsBackToEvalAndRecovers() {
        String key = "noscript:" + UUID.randomUUID();

        // First call: script was loaded at startup, so EVALSHA works.
        assertThat(evalSha(key).get(0)).isEqualTo("1");

        // Wipe the server-side script cache.
        connection.sync().scriptFlush();

        // EVALSHA now returns NOSCRIPT; the fallback must still produce a valid
        // decision and re-register the SHA.
        List<String> recovered = evalSha(key);
        assertThat(recovered.get(0)).isEqualTo("1");
        assertThat(recovered.get(1)).isNotBlank();

        // Re-registered: the SHA is present again, so readiness remains valid.
        List<Boolean> exists = connection.sync().scriptExists(scriptLoader.tokenBucketSha());
        assertThat(exists.get(0)).isTrue();
    }
}
