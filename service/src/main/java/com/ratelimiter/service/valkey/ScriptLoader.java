package com.ratelimiter.service.valkey;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Loads and executes the rate-limit Lua scripts, using {@code EVALSHA} first and
 * falling back to {@code EVAL} when the server reports {@code NOSCRIPT}.
 *
 * <p>// RATIONALE: {@code EVALSHA} avoids repeatedly uploading the script body on
 * every request (it sends only the 40-char SHA). But the SHA is server-side: a
 * {@code SCRIPT FLUSH} or a failover to a replica that never saw the script would
 * return {@code NOSCRIPT}. The fallback to {@code EVAL} (and re-registering the
 * SHA) makes that transparent, so the service never depends on the script being
 * pre-loaded on the server it happens to connect to.
 */
@Component
@ConditionalOnProperty(name = "ratelimiter.store", havingValue = "valkey")
public class ScriptLoader {

    private static final String TOKEN_BUCKET_PATH = "/lua/token_bucket.lua";
    private static final String SLIDING_WINDOW_PATH = "/lua/sliding_window.lua";

    private final StatefulRedisConnection<String, String> connection;
    private final String tokenBucketSha;
    private final String slidingWindowSha;

    /**
     * Loads all scripts at startup and records their SHAs.
     *
     * @param connection the shared connection to Valkey
     */
    public ScriptLoader(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
        this.tokenBucketSha = connection.sync().scriptLoad(load(TOKEN_BUCKET_PATH));
        this.slidingWindowSha = connection.sync().scriptLoad(load(SLIDING_WINDOW_PATH));
    }

    /**
     * Runs the token-bucket script atomically.
     *
     * @param key the rate-limit key
     * @param capacity the bucket capacity
     * @param refillPerSecond the refill rate (tokens/second)
     * @param requestedTokens the units this request requires
     * @param nowMillis the injected current time
     * @return {@code [allowed, remaining, resetAtMillis, retryAfterSeconds]} as strings
     */
    public List<String> tokenBucket(String key, double capacity, double refillPerSecond,
            double requestedTokens, long nowMillis) {
        return eval(tokenBucketSha, TOKEN_BUCKET_PATH,
                new String[] { "rl:tc:" + key },
                new String[] {
                        String.valueOf(capacity),
                        String.valueOf(refillPerSecond),
                        String.valueOf(requestedTokens),
                        String.valueOf(nowMillis)
                });
    }

    /**
     * Runs the sliding-window script atomically.
     *
     * @param key the rate-limit key
     * @param limit the max events per window
     * @param windowMillis the rolling window length
     * @param nowMillis the injected current time
     * @return {@code [allowed, remaining, resetAtMillis, retryAfterSeconds]} as strings
     */
    public List<String> slidingWindow(String key, double limit, long windowMillis, long nowMillis) {
        return eval(slidingWindowSha, SLIDING_WINDOW_PATH,
                new String[] { "rl:sw:" + key, "rl:sw:" + key + ":seq" },
                new String[] {
                        String.valueOf(limit),
                        String.valueOf(windowMillis),
                        String.valueOf(nowMillis)
                });
    }

    /**
     * @return the SHA of the token-bucket script (used by the readiness probe)
     */
    public String tokenBucketSha() {
        return tokenBucketSha;
    }

    /**
     * @return the SHA of the sliding-window script
     */
    public String slidingWindowSha() {
        return slidingWindowSha;
    }

    /**
     * Executes a script with EVALSHA, falling back to EVAL (and re-registering
     * the script) on a {@code NOSCRIPT} response.
     *
     * @param sha the cached SHA of the script
     * @param path the classpath path of the script source
     * @param keys the script keys
     * @param args the script arguments
     * @return the script result (MULTI output)
     */
    private List<String> eval(String sha, String path, String[] keys, String[] args) {
        RedisCommands<String, String> commands = connection.sync();
        try {
            return commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
        } catch (RedisNoScriptException noscript) {
            // Re-register so the next call uses EVALSHA again (SCRIPT FLUSH or a
            // failover to a replica that never saw the script).
            String script = load(path);
            commands.scriptLoad(script);
            return commands.eval(script, ScriptOutputType.MULTI, keys, args);
        }
    }

    /**
     * Loads a classpath resource into a String.
     *
     * @param path the resource path (leading slash)
     * @return the resource text
     * @throws IllegalStateException if the resource is missing or unreadable
     */
    private static String load(String path) {
        try (InputStream in = ScriptLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("script resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("could not read script: " + path, ex);
        }
    }
}
