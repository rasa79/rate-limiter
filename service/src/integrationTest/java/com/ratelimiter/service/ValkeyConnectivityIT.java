package com.ratelimiter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ratelimiter.service.support.IntegrationTestBase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Smoke test proving the Testcontainers + Valkey harness works before M4 wires
 * the real store into the service.
 *
 * <p>It sends a RESP {@code PING} to the container's mapped port and expects a
 * {@code PONG}. This exercises the container as a real network peer — no mock,
 * no client library yet — which is exactly the harness the Valkey backend in M4
 * will build on.
 */
class ValkeyConnectivityIT extends IntegrationTestBase {

    @Test
    void containerIsReachable() {
        int mappedPort = valkey.getMappedPort(6379);
        String reply = ping(new InetSocketAddress("localhost", mappedPort));
        assertThat(reply).contains("PONG");
    }

    /**
     * Talks minimal RESP to the server: writes {@code PING} and reads the reply.
     *
     * @param address the container address/port
     * @return the raw reply text
     */
    private String ping(InetSocketAddress address) {
        try (Socket socket = new Socket()) {
            socket.connect(address, 5000);
            socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[64];
            int read = socket.getInputStream().read(buffer);
            return new String(buffer, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
