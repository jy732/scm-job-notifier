package com.github.jingyangyu.scmjobnotifier.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.service.ClassificationReplayService;
import com.github.jingyangyu.scmjobnotifier.service.ClassificationReplayService.ReplaySummary;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClassificationReplayControllerTest {

    private final ClassificationReplayService service = mock(ClassificationReplayService.class);
    private final ClassificationReplayController controller =
            new ClassificationReplayController(service);

    private static ReplaySummary ok() {
        return new ReplaySummary(0, 0, 0, 0, 0, Map.of(), true, null);
    }

    @Test
    void defaultsToFallbackSourcesWhenNothingSpecified() {
        when(service.replay(any(), isNull(), isNull(), eq(0), eq(true))).thenReturn(ok());
        controller.replayClassification(null, null, null, 0, true).block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> sources = ArgumentCaptor.forClass(Set.class);
        verify(service).replay(sources.capture(), isNull(), isNull(), eq(0), eq(true));
        assertThat(sources.getValue())
                .containsExactlyInAnyOrder("FALLBACK_UNSURE", "AUTO_APPROVED");
    }

    @Test
    void parsesCommaSeparatedSources() {
        when(service.replay(any(), any(), any(), any(Integer.class), any(Boolean.class)))
                .thenReturn(ok());
        controller
                .replayClassification(" FALLBACK_UNSURE , AUTO_APPROVED ,", null, null, 5, false)
                .block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> sources = ArgumentCaptor.forClass(Set.class);
        verify(service).replay(sources.capture(), isNull(), isNull(), eq(5), eq(false));
        assertThat(sources.getValue())
                .containsExactlyInAnyOrder("FALLBACK_UNSURE", "AUTO_APPROVED");
    }

    @Test
    void parsesTimeWindow() {
        when(service.replay(any(), any(), any(), any(Integer.class), any(Boolean.class)))
                .thenReturn(ok());
        controller
                .replayClassification(null, "2026-08-28T00:00:00Z", "2026-08-31T00:00:00Z", 0, true)
                .block();
        verify(service)
                .replay(
                        isNull(),
                        eq(Instant.parse("2026-08-28T00:00:00Z")),
                        eq(Instant.parse("2026-08-31T00:00:00Z")),
                        eq(0),
                        eq(true));
    }

    @Test
    void invalidInstantReturnsErrorWithoutCallingService() {
        ReplaySummary r =
                controller.replayClassification(null, "not-a-date", null, 0, true).block();
        assertThat(r).isNotNull();
        assertThat(r.error()).contains("ISO-8601");
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void blankSourcesTreatedAsAbsent() {
        when(service.replay(any(), any(), any(), any(Integer.class), any(Boolean.class)))
                .thenReturn(ok());
        // blank sources + a window -> sources null, window used (not defaulted)
        controller
                .replayClassification("  ", "2026-08-28T00:00:00Z", "2026-08-31T00:00:00Z", 0, true)
                .block();
        verify(service).replay(isNull(), any(Instant.class), any(Instant.class), eq(0), eq(true));
    }
}
