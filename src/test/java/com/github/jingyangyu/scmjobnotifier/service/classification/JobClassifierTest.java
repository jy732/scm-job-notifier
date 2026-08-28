package com.github.jingyangyu.scmjobnotifier.service.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.service.PipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobClassifierTest {

    private final GeminiClient gemini = mock(GeminiClient.class);
    private final PipelineMetrics metrics = new PipelineMetrics(new SimpleMeterRegistry());
    private final JobClassifier classifier = new JobClassifier(gemini, metrics);

    private static JobPosting job() {
        return JobPosting.builder()
                .company("acme")
                .externalId("1")
                .title("Buyer")
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void emptyReturnsEmpty() {
        assertThat(classifier.classify(List.of()).getLevelMap()).isEmpty();
    }

    @Test
    void notConfiguredMarksAllUnsure() {
        when(gemini.isConfigured()).thenReturn(false);
        JobPosting j = job();
        assertThat(classifier.classify(List.of(j)).getLevelMap()).containsEntry(j, "UNSURE");
    }

    @Test
    void classifiesAcrossMultipleBatches() {
        // 60 jobs => 2 batches of BATCH_SIZE (50)
        java.util.List<JobPosting> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            jobs.add(
                    JobPosting.builder()
                            .company("acme")
                            .externalId("j" + i)
                            .title("Buyer")
                            .detectedAt(Instant.now())
                            .build());
        }
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.classifyLevel(anyList()))
                .thenAnswer(
                        inv -> {
                            java.util.List<JobPosting> batch = inv.getArgument(0);
                            java.util.Map<JobPosting, String> m = new java.util.HashMap<>();
                            batch.forEach(j -> m.put(j, "OTHER"));
                            return m;
                        });
        var result = classifier.classify(jobs);
        assertThat(result.getLevelMap()).hasSize(60);
        verify(gemini, org.mockito.Mockito.times(2)).classifyLevel(anyList());
    }

    @Test
    void configuredUsesGeminiResult() {
        JobPosting j = job();
        when(gemini.isConfigured()).thenReturn(true);
        when(gemini.classifyLevel(anyList())).thenReturn(Map.of(j, "ENTRY_LEVEL"));
        var result = classifier.classify(List.of(j));
        assertThat(result.getLevelMap()).containsEntry(j, "ENTRY_LEVEL");
        assertThat(result.getFailed()).isEmpty();
        verify(gemini).classifyLevel(anyList());
    }
}
