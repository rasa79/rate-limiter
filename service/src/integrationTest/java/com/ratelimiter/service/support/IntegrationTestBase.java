package com.ratelimiter.service.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * Shared base for the end-to-end integration tests.
 *
 * <p>It boots the full application on a random port (real HTTP) and keeps a real
 * Testcontainers Valkey container across the whole suite.
 *
 * <p>// RATIONALE: the container is a SINGLETON started once, not a
 * {@code @Container static} restarted per test class. Spring caches the
 * application context by configuration, so several tests share one context whose
 * Lettuce connection is bound to ONE address; if the container were restarted on
 * a new random port per class, that cached connection would point at a stale port
 * (connection reset). One stable container for the whole JVM keeps the address
 * constant. Testcontainers' Ryuk resource-reaper cleans it up when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig.class)
public abstract class IntegrationTestBase {

    /**
     * The store image, parameterized so the M14 CI matrix can swap in {@code redis:8}.
     */
    protected static final String STORE_IMAGE =
            System.getenv().getOrDefault("RATE_LIMITER_IMAGE", "valkey/valkey:8");

    /** Single suite-wide Valkey container, started once. */
    protected static final GenericContainer<?> valkey = startValkey();

    /** The port the application is running on (randomly assigned). */
    @LocalServerPort
    protected int port;

    /** A blocking REST client pointing at the running server. */
    protected RestClient http;

    private static GenericContainer<?> startValkey() {
        GenericContainer<?> container = new GenericContainer<>(STORE_IMAGE).withExposedPorts(6379);
        if (DockerClientFactory.instance().isDockerAvailable()) {
            container.start();
        }
        return container;
    }

    /** Sets up the REST client bound to the random port. */
    @BeforeEach
    void initHttpClient() {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }
}
