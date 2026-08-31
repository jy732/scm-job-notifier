package com.github.jingyangyu.scmjobnotifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.repository.JobPostingRepository;
import com.github.jingyangyu.scmjobnotifier.service.ClassificationReplayService.ReplaySummary;
import com.github.jingyangyu.scmjobnotifier.service.classification.ClassificationPipeline;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationReplayServiceTest {

    private final JobPostingRepository repo = mock(JobPostingRepository.class);
    private final ClassificationPipeline pipeline = mock(ClassificationPipeline.class);
    private final ClassificationReplayService svc =
            new ClassificationReplayService(repo, pipeline, 90);

    private static JobPosting job(String id, String level, boolean notified) {
        return JobPosting.builder()
                .company("c")
                .externalId(id)
                .title("Buyer")
                .level(level)
                .notified(notified)
                .detectedAt(Instant.now())
                .build();
    }

    /** Stubs the pipeline to return the given new levels (all GEMINI provenance). */
    private void pipelineReturns(Map<JobPosting, String> newLevels) {
        Map<JobPosting, String> sources = new HashMap<>();
        newLevels.keySet().forEach(j -> sources.put(j, "GEMINI"));
        when(pipeline.classify(any()))
                .thenReturn(
                        new ClassificationPipeline.Result(newLevels, List.of(), 0, 0, 0, sources));
    }

    @Test
    void noSelectorReturnsError() {
        ReplaySummary r = svc.replay(null, null, null, 0, false);
        assertThat(r.error()).contains("no selector");
        assertThat(r.selected()).isZero();
        verify(pipeline, never()).classify(any());
    }

    @Test
    void requeuesMissedAndUpgradesButNotLateralOrDowngrade() {
        JobPosting missed = job("1", "OTHER", true); // OTHER -> ENTRY : missed, re-send
        JobPosting neverClassified = job("2", null, false); // null -> INTERNSHIP : re-send
        JobPosting upgrade = job("3", "UNSURE", true); // UNSURE -> ENTRY : upgrade, re-send
        JobPosting lateral = job("4", "UNSURE", true); // UNSURE -> UNSURE : no re-send
        JobPosting downgrade =
                job("5", "ENTRY_LEVEL", true); // ENTRY -> OTHER : correct, no re-send
        JobPosting unchanged = job("6", "ENTRY_LEVEL", true); // ENTRY -> ENTRY : no re-send
        List<JobPosting> all =
                List.of(missed, neverClassified, upgrade, lateral, downgrade, unchanged);
        when(repo.findByClassificationSourceIn(any())).thenReturn(all);
        Map<JobPosting, String> newLevels = new HashMap<>();
        newLevels.put(missed, "ENTRY_LEVEL");
        newLevels.put(neverClassified, "INTERNSHIP");
        newLevels.put(upgrade, "ENTRY_LEVEL");
        newLevels.put(lateral, "UNSURE");
        newLevels.put(downgrade, "OTHER");
        newLevels.put(unchanged, "ENTRY_LEVEL");
        pipelineReturns(newLevels);

        ReplaySummary r = svc.replay(Set.of("FALLBACK_UNSURE"), null, null, 0, false);

        assertThat(r.selected()).isEqualTo(6);
        assertThat(r.reclassified()).isEqualTo(6);
        assertThat(r.requeued()).isEqualTo(3);
        assertThat(r.byTransition())
                .containsEntry("OTHER->ENTRY_LEVEL", 1)
                .containsEntry("NULL->INTERNSHIP", 1)
                .containsEntry("UNSURE->ENTRY_LEVEL", 1)
                .containsEntry("UNSURE->UNSURE", 1)
                .containsEntry("ENTRY_LEVEL->OTHER", 1)
                .containsEntry("ENTRY_LEVEL->ENTRY_LEVEL", 1);
        // re-queued jobs got notified=false + updated level/source
        assertThat(missed.isNotified()).isFalse();
        assertThat(neverClassified.isNotified()).isFalse();
        assertThat(upgrade.isNotified()).isFalse();
        assertThat(missed.getClassificationSource()).isEqualTo("GEMINI");
        // non-requeue jobs keep notified but still get their level corrected
        assertThat(lateral.isNotified()).isTrue();
        assertThat(downgrade.isNotified()).isTrue();
        assertThat(downgrade.getLevel()).isEqualTo("OTHER");
        assertThat(unchanged.isNotified()).isTrue();
        verify(repo).saveAll(any());
    }

    @Test
    void dryRunReportsButWritesNothing() {
        JobPosting missed = job("1", "OTHER", true);
        when(repo.findByClassificationSourceIn(any())).thenReturn(List.of(missed));
        pipelineReturns(Map.of(missed, "ENTRY_LEVEL"));

        ReplaySummary r = svc.replay(Set.of("FALLBACK_UNSURE"), null, null, 0, true);

        assertThat(r.dryRun()).isTrue();
        assertThat(r.requeued()).isEqualTo(1); // would re-queue
        assertThat(missed.isNotified()).isTrue(); // but nothing written
        assertThat(missed.getLevel()).isEqualTo("OTHER");
        verify(repo, never()).saveAll(any());
    }

    @Test
    void windowSelectorUsedWhenNoSources() {
        JobPosting missed = job("1", "OTHER", true);
        when(repo.findByDetectedAtBetween(any(), any())).thenReturn(List.of(missed));
        pipelineReturns(Map.of(missed, "ENTRY_LEVEL"));

        Instant since = Instant.now().minus(3, ChronoUnit.DAYS);
        ReplaySummary r = svc.replay(null, since, Instant.now(), 0, false);

        assertThat(r.requeued()).isEqualTo(1);
        verify(repo).findByDetectedAtBetween(any(), any());
        verify(repo, never()).findByClassificationSourceIn(any());
    }

    @Test
    void skipsStaleJobsPastRetention() {
        JobPosting stale =
                JobPosting.builder()
                        .company("c")
                        .externalId("old")
                        .title("Buyer")
                        .level("OTHER")
                        .postedDate(Instant.now().minus(100, ChronoUnit.DAYS))
                        .detectedAt(Instant.now())
                        .build();
        JobPosting fresh = job("new", "OTHER", true);
        when(repo.findByClassificationSourceIn(any())).thenReturn(List.of(stale, fresh));
        pipelineReturns(Map.of(fresh, "ENTRY_LEVEL"));

        ReplaySummary r = svc.replay(Set.of("FALLBACK_UNSURE"), null, null, 0, false);

        assertThat(r.selected()).isEqualTo(2);
        assertThat(r.skippedStale()).isEqualTo(1);
        assertThat(r.reclassified()).isEqualTo(1);
    }

    @Test
    void limitCapsNumberOfTargets() {
        List<JobPosting> many =
                List.of(job("1", "OTHER", true), job("2", "OTHER", true), job("3", "OTHER", true));
        when(repo.findByClassificationSourceIn(any())).thenReturn(many);
        // pipeline only ever sees the first job (limit=1)
        pipelineReturns(Map.of(many.get(0), "ENTRY_LEVEL"));

        ReplaySummary r = svc.replay(Set.of("FALLBACK_UNSURE"), null, null, 1, false);

        assertThat(r.selected()).isEqualTo(3);
        assertThat(r.reclassified()).isEqualTo(1);
    }

    @Test
    void geminiStillUnavailableLeavesJobUntouched() {
        JobPosting job = job("1", "OTHER", true);
        when(repo.findByClassificationSourceIn(any())).thenReturn(List.of(job));
        pipelineReturns(Map.of()); // pipeline produced no level for the job

        ReplaySummary r = svc.replay(Set.of("FALLBACK_UNSURE"), null, null, 0, false);

        assertThat(r.geminiUnavailable()).isEqualTo(1);
        assertThat(r.reclassified()).isZero();
        assertThat(r.requeued()).isZero();
        assertThat(job.getLevel()).isEqualTo("OTHER"); // untouched
        verify(repo, never()).saveAll(any());
    }

    @Test
    void emptySelectionShortCircuits() {
        when(repo.findByClassificationSourceIn(any())).thenReturn(List.of());
        ReplaySummary r = svc.replay(Set.of("FALLBACK_UNSURE"), null, null, 0, false);
        assertThat(r.selected()).isZero();
        assertThat(r.reclassified()).isZero();
        verify(pipeline, never()).classify(any());
    }
}
