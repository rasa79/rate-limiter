package com.ratelimiter.service.support;

import org.testcontainers.containers.GenericContainer;

/**
 * A {@link GenericContainer} for the Valkey store that exposes a fixed host port.
 *
 * <p>Testcontainers only exposes the arbitrary-port API publicly
 * ({@code addFixedExposedPort} is protected), so this small subclass is needed to
 * pin a stable host:port. A fixed port matters for the readiness test, which
 * stops/restarts the container while the application keeps pointing at the same
 * host:port; a randomized mapped port would leave the app targeting a stale port
 * after a restart.
 */
public class ValkeyContainer extends GenericContainer<ValkeyContainer> {

    /**
     * @param image the container image
     */
    public ValkeyContainer(String image) {
        super(image);
    }

    /**
     * Pins the given host port to the given container port.
     *
     * @param hostPort the host port to bind
     * @param containerPort the container port to expose
     * @return this container
     */
    public ValkeyContainer withFixedPort(int hostPort, int containerPort) {
        addFixedExposedPort(hostPort, containerPort);
        return this;
    }
}
