package com.ratelimiter.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the M1 actuator contract: liveness and readiness are independent,
 * individually addressable probes that both report UP for a healthy skeleton.
 *
 * <p>These tests exercise the mapped HTTP paths (rather than the health
 * aggregate view) because the probe URLs and their independent status are part
 * of the promised deployability contract (Kubernetes, nginx health checks and
 * autoheal all rely on exactly these two endpoints).
 *
 * <p>MockMvc dispatches in-process against the full application context, so the
 * same actuator auto-configuration a real deployment uses handles the requests.
 * We build it manually from the {@link WebApplicationContext} (instead of the
 * {@code @AutoConfigureMockMvc} shortcut) because it keeps the test on the
 * dependencies already provided by {@code spring-boot-starter-test} alone.
 */
@SpringBootTest
class HealthEndpointsTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /**
     * Liveness reports UP whenever the JVM is alive; it must not depend on any
     * external component, so it can never be dragged DOWN by a store outage.
     */
    @Test
    void livenessIsUpWhenJvmAlive() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * Readiness reports UP in M1 because no external dependency is wired yet.
     * Later milestones pin this group to Valkey reachability (and Lua scripts
     * being loaded): the probe path stays the same, only its contributors change.
     */
    @Test
    void readinessIsUpWithNoExternalDependenciesYet() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
