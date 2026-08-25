package com.ratelimiter.service.health;

import com.ratelimiter.service.valkey.ScriptLoader;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness contributor for the external store, always registered so the
 * readiness health group can reference it unconditionally in {@code application.yml}.
 *
 * <p>When the {@code valkey} backend is active it reports DOWN if Valkey is
 * unreachable (a {@code PING} fails) or the Lua scripts are not loaded on the
 * connected server (a {@code SCRIPT FLUSH} was issued, or we failed over to a
 * replica that never saw them). When the in-memory backend is active there is no
 * external dependency, so it reports UP/not-applicable, leaving readiness UP.
 *
 * <p>{@code ObjectProvider} keeps the indicator dependenc-free until the valkey
 * beans actually exist, so it never forces a Lettuce connection in in-memory
 * mode. Liveness is unaffected — the JVM is alive even while Valkey is down.
 */
@Component("valkey")
public class ValkeyReadinessIndicator implements HealthIndicator {

    private final ObjectProvider<StatefulRedisConnection<String, String>> connectionProvider;
    private final ObjectProvider<ScriptLoader> scriptLoaderProvider;

    /**
     * @param connectionProvider supplies the Valkey connection when present
     * @param scriptLoaderProvider supplies the script loader when present
     */
    public ValkeyReadinessIndicator(
            ObjectProvider<StatefulRedisConnection<String, String>> connectionProvider,
            ObjectProvider<ScriptLoader> scriptLoaderProvider) {
        this.connectionProvider = connectionProvider;
        this.scriptLoaderProvider = scriptLoaderProvider;
    }

    @Override
    public Health health() {
        StatefulRedisConnection<String, String> connection = connectionProvider.getIfAvailable();
        ScriptLoader scriptLoader = scriptLoaderProvider.getIfAvailable();
        if (connection == null || scriptLoader == null) {
            // In-memory store: no external dependency, so readiness is UP.
            return Health.up().withDetail("valkey", "not-applicable (in-memory store)").build();
        }

        try {
            String pong = connection.sync().ping();
            List<Boolean> loaded = connection.sync().scriptExists(scriptLoader.tokenBucketSha());
            boolean scriptsLoaded = loaded != null && !loaded.isEmpty()
                    && Boolean.TRUE.equals(loaded.get(0));
            if ("PONG".equalsIgnoreCase(pong) && scriptsLoaded) {
                return Health.up()
                        .withDetail("valkey", "reachable")
                        .withDetail("luaScripts", "loaded")
                        .build();
            }
            return Health.down().withDetail("luaScriptsLoaded", scriptsLoaded).build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }
}
