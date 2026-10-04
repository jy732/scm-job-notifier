package com.github.jingyangyu.scmjobnotifier.service.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class GeminiClientTest {

    private static JobPosting job(String id) {
        return JobPosting.builder()
                .company("c")
                .externalId(id)
                .title("Buyer")
                .detectedAt(Instant.now())
                .build();
    }

    private GeminiClient client() {
        return new GeminiClient(WebClientStubs.json(u -> "{}"), "k", "m", "https://unused.test");
    }

    @Test
    void parseLevelResponseMapsIndexedLines() {
        JobPosting j1 = job("1");
        JobPosting j2 = job("2");
        Map<String, Object> response =
                Map.of(
                        "candidates",
                        List.of(
                                Map.of(
                                        "content",
                                        Map.of(
                                                "parts",
                                                List.of(
                                                        Map.of(
                                                                "text",
                                                                "1: ENTRY_LEVEL\n2: OTHER"))))));
        Map<JobPosting, String> levels = client().parseLevelResponse(response, List.of(j1, j2));
        assertThat(levels).containsEntry(j1, "ENTRY_LEVEL").containsEntry(j2, "OTHER");
    }

    @Test
    void parseLevelResponseHandlesNullAndEmpty() {
        assertThat(client().parseLevelResponse(null, List.of(job("1")))).isNull();
        assertThat(client().parseLevelResponse(Map.of("candidates", List.of()), List.of(job("1"))))
                .isNull();
    }

    @Test
    void classifyLevelEndToEndViaStubbedApi() {
        String apiResponse =
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"1: ENTRY_LEVEL\"}]}}]}";
        GeminiClient c =
                new GeminiClient(
                        WebClientStubs.json(u -> apiResponse),
                        "key",
                        "model",
                        "https://unused.test");
        JobPosting j = job("1");
        Map<JobPosting, String> levels = c.classifyLevel(List.of(j));
        assertThat(levels).containsEntry(j, "ENTRY_LEVEL");
    }

    @Test
    void parseLevelResponseReturnsNullOnMalformedShape() {
        // candidate missing "content" -> NPE inside try -> caught -> null
        Map<String, Object> response = Map.of("candidates", List.of(Map.of("noContent", "x")));
        assertThat(client().parseLevelResponse(response, List.of(job("1")))).isNull();
    }

    @Test
    void buildPromptCarriesTitleAndJobDetails() {
        JobPosting withDetails =
                JobPosting.builder()
                        .company("c")
                        .externalId("1")
                        .title("New Grad Supply Chain Analyst")
                        .description(
                                "About us. Responsibilities: run daily cycle counts. "
                                        + "Qualifications: 0-2 years experience.")
                        .detectedAt(Instant.now())
                        .build();
        String prompt = client().buildPrompt(List.of(withDetails, job("2")));
        assertThat(prompt).contains("Title:").contains("Details:");
        // the section, not the company blurb, is what reaches the model
        assertThat(prompt).contains("run daily cycle counts").doesNotContain("About us");
    }

    /** A posting with no description is the only remaining case of a title-only prompt. */
    @Test
    void buildPromptMarksDescriptionlessJobsAsNone() {
        assertThat(client().buildPrompt(List.of(job("9")))).contains("Details: (none)");
    }

    @Test
    void configuredWhenApiKeyPresent() {
        GeminiClient c =
                new GeminiClient(
                        WebClientStubs.json(u -> "{}"),
                        "a-key",
                        "gemini-2.5-flash",
                        "https://unused.test");
        assertThat(c.isConfigured()).isTrue();
    }

    @Test
    void notConfiguredWhenApiKeyBlank() {
        GeminiClient c =
                new GeminiClient(
                        WebClientStubs.json(u -> "{}"),
                        "",
                        "gemini-2.5-flash",
                        "https://unused.test");
        assertThat(c.isConfigured()).isFalse();

        GeminiClient c2 =
                new GeminiClient(WebClientStubs.json(u -> "{}"), null, "m", "https://unused.test");
        assertThat(c2.isConfigured()).isFalse();
    }

    /**
     * The dedicated Gemini timeout is wired through a real {@code clientConnector}, so the stub
     * exchange functions used elsewhere bypass it entirely. This drives one real request over
     * loopback so the connector and its doOnConnected handler actually run.
     */
    @Test
    void usesItsOwnHttpClientForRealRequests() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            Thread responder =
                    new Thread(
                            () -> {
                                try (java.net.Socket socket = server.accept();
                                        java.io.OutputStream out = socket.getOutputStream()) {
                                    socket.getInputStream().read(new byte[8192]);
                                    String json =
                                            "{\"candidates\":[{\"content\":{\"parts\":"
                                                    + "[{\"text\":\"1:ENTRY_LEVEL\"}]}}]}";
                                    out.write(
                                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                                            + "Content-Length: "
                                                            + json.length()
                                                            + "\r\nConnection: close\r\n\r\n"
                                                            + json)
                                                    .getBytes());
                                    out.flush();
                                } catch (java.io.IOException ignored) {
                                    // the assertion below reports the real failure
                                }
                            });
            responder.setDaemon(true);
            responder.start();

            GeminiClient client =
                    new GeminiClient(
                            WebClient.builder(),
                            "k",
                            "m",
                            "http://127.0.0.1:" + server.getLocalPort());
            JobPosting posting = job("1"); // one instance: result map keys on identity
            assertThat(client.classifyLevel(List.of(posting)))
                    .containsEntry(posting, "ENTRY_LEVEL");
        }
    }
}
