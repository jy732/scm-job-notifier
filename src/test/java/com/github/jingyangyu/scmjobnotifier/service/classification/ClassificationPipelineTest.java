package com.github.jingyangyu.scmjobnotifier.service.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.service.PipelineMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassificationPipelineTest {

    private final JobClassifier classifier = mock(JobClassifier.class);
    private final PipelineMetrics metrics = mock(PipelineMetrics.class);
    private final ClassificationPipeline pipeline =
            new ClassificationPipeline(new JobTitleFilter(90), classifier, metrics);

    private static JobPosting job(String title, String description) {
        return JobPosting.builder()
                .company("x")
                .externalId(title)
                .title(title)
                .description(description)
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void routesThroughAllThreeStages() {
        JobPosting stage1 = job("Supply Chain Coordinator", null); // auto-classified
        JobPosting stage2 = job("Buyer", "Requires 8+ years experience"); // description infer
        JobPosting stage3 = job("Buyer", "A fun role"); // needs Gemini

        when(classifier.classify(List.of(stage3)))
                .thenReturn(new ClassificationResult(Map.of(stage3, "UNSURE"), List.of()));

        ClassificationPipeline.Result r = pipeline.classify(List.of(stage1, stage2, stage3));

        assertThat(r.stage1Count()).isEqualTo(1);
        assertThat(r.stage2Count()).isEqualTo(1);
        assertThat(r.stage3Count()).isEqualTo(1);
        assertThat(r.levelMap())
                .containsEntry(stage1, "ENTRY_LEVEL")
                .containsEntry(stage2, "OTHER")
                .containsEntry(stage3, "UNSURE");
        assertThat(r.geminiFailed()).isEmpty();
        verify(metrics).recordClassifyStage1(1);
        verify(metrics).recordClassifyStage2(1);
        verify(metrics).recordClassifyStage3(1);
    }

    @Test
    void skipsGeminiWhenAllResolvedEarly() {
        JobPosting j = job("Procurement Intern", null); // stage1 INTERNSHIP
        ClassificationPipeline.Result r = pipeline.classify(List.of(j));
        assertThat(r.stage3Count()).isZero();
        assertThat(r.levelMap()).containsEntry(j, "INTERNSHIP");
        // classifier.classify must not be invoked
        verify(classifier, org.mockito.Mockito.never())
                .classify(org.mockito.ArgumentMatchers.any());
    }
}
