package com.github.jingyangyu.scmjobnotifier.service.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import org.junit.jupiter.api.Test;

class GeminiClientTest {

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
