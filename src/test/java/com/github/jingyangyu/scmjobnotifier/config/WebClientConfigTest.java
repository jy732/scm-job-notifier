package com.github.jingyangyu.scmjobnotifier.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Exercises the configured {@link WebClient.Builder}, including the {@code doOnConnected} handler
 * wiring — which only runs once a real connection is established, so the test serves one response
 * from a loopback socket.
 */
class WebClientConfigTest {

    @Test
    void builderIssuesRequestsThroughTheConfiguredHttpClient() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread responder =
                    new Thread(
                            () -> {
                                try (var socket = server.accept();
                                        var out = socket.getOutputStream()) {
                                    socket.getInputStream().read(new byte[1024]);
                                    out.write(
                                            ("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
                                                            + "Connection: close\r\n\r\nok")
                                                    .getBytes());
                                    out.flush();
                                } catch (IOException ignored) {
                                    // the assertion below reports the real failure
                                }
                            });
            responder.setDaemon(true);
            responder.start();

            WebClient client =
                    new WebClientConfig()
                            .webClientBuilder()
                            .baseUrl("http://127.0.0.1:" + server.getLocalPort())
                            .build();
            String body = client.get().uri("/").retrieve().bodyToMono(String.class).block();
            assertThat(body).isEqualTo("ok");
        }
    }
}
