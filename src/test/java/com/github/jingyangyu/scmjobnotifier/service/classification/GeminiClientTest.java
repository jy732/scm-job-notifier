package com.github.jingyangyu.scmjobnotifier.service.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
        return new GeminiClient(WebClientStubs.json(u -> "{}"), "k", "m");
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
    void configuredWhenApiKeyPresent() {
        GeminiClient c =
                new GeminiClient(WebClientStubs.json(u -> "{}"), "a-key", "gemini-2.5-flash");
        assertThat(c.isConfigured()).isTrue();
    }

    @Test
    void notConfiguredWhenApiKeyBlank() {
        GeminiClient c = new GeminiClient(WebClientStubs.json(u -> "{}"), "", "gemini-2.5-flash");
        assertThat(c.isConfigured()).isFalse();

        GeminiClient c2 = new GeminiClient(WebClientStubs.json(u -> "{}"), null, "m");
        assertThat(c2.isConfigured()).isFalse();
    }
}
