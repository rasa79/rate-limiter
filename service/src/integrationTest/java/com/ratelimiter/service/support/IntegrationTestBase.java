package com.ratelimiter.service.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared base for the end-to-end integration tests.
 *
 * <p>It boots the full application on a random port (real HTTP), keeps a real
 * Testcontainers Valkey container across the suite (so the harness is proven
 * now and the Valkey backend in M4 has a container ready), and pins the
 * application clock to a fixed instant so the check-path behaviour is
 * deterministic.
 *
 * <p>// RATIONALE: tests run against a REAL container and a REAL HTTP server,
 * never a mock of the store — this is what makes the integration suite prove the
 * wiring (M4 proves the Lua script) rather than the test double.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig.class)
public abstract class IntegrationTestBase {

    /**
     * The store image, parameterized so the M14 CI matrix can swap in {@code redis:8}.
     */
    protected static final String STORE_IMAGE =
            System.getenv().getOrDefault("RATE_LIMITER_IMAGE", "valkey/valkey:8");

    /** Shared Valkey container, exposed on 6379 (the default port). */
    @Container
    protected static GenericContainer<?> valkey =
            new GenericContainer<>(STORE_IMAGE).withExposedPorts(6379);

    /** The port the application is running on (randomly assigned). */
    @LocalServerPort
    protected int port;

    /** A blocking REST client pointing at the running server. */
    protected RestClient http;

    /** Sets up the REST client bound to the random port. */
    @BeforeEach
    void initHttpClient() {
        this.http = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }
}
