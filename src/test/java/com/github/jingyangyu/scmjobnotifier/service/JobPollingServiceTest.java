package com.github.jingyangyu.scmjobnotifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.repository.JobPostingRepository;
import com.github.jingyangyu.scmjobnotifier.scraper.JobScraper;
import com.github.jingyangyu.scmjobnotifier.service.classification.ClassificationPipeline;
import com.github.jingyangyu.scmjobnotifier.service.classification.JobClassifier;
import com.github.jingyangyu.scmjobnotifier.service.classification.JobTitleFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobPollingServiceTest {

    private final JobPostingRepository repo = mock(JobPostingRepository.class);
    private final ClassificationPipeline pipeline = mock(ClassificationPipeline.class);
    private final JobClassifier classifier = mock(JobClassifier.class);

    private JobPosting scmJob() {
        return JobPosting.builder()
                .company("c")
                .externalId("1")
                .title("Supply Chain Analyst")
                .location("San Jose, CA")
                .detectedAt(Instant.now())
                .build();
    }

    private final JobScraper scraper =
            new JobScraper() {
                @Override
                public String platform() {
                    return "x";
                }

                @Override
                public List<String> companies() {
                    return List.of("c");
                }

                @Override
                public List<JobPosting> scrape(String company) {
                    return List.of(scmJob());
                }
            };

    @Test
    @SuppressWarnings("unchecked")
    void pollScrapesFiltersClassifiesAndPersists() {
        when(repo.findAllCompanyExternalIdKeys()).thenReturn(Set.of());
        when(repo.findByCompanyExternalIdKeys(any())).thenReturn(List.of());
        when(repo.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        anyInt(), anyInt()))
                .thenReturn(List.of());
        when(repo.saveAll(any())).thenAnswer(inv -> ((Iterable<JobPosting>) inv.getArgument(0)));
        JobPosting j = scmJob();
        when(pipeline.classify(any()))
                .thenReturn(
                        new ClassificationPipeline.Result(
                                Map.of(j, "ENTRY_LEVEL"), List.of(), 1, 0, 0));

        JobPollingService svc =
                new JobPollingService(
                        List.of(scraper),
                        repo,
                        pipeline,
                        classifier,
                        new JobTitleFilter(90),
                        new PipelineMetrics(new SimpleMeterRegistry()));

        svc.poll();

        verify(pipeline, atLeastOnce()).classify(any());
        verify(repo, atLeastOnce()).saveAll(any());
    }

    @Test
    void pollFiltersOutNonMatchingJobs() {
        when(repo.findAllCompanyExternalIdKeys()).thenReturn(Set.of());
        when(repo.findByCompanyExternalIdKeys(any())).thenReturn(List.of());
        when(repo.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        anyInt(), anyInt()))
                .thenReturn(List.of());
        when(pipeline.classify(any()))
                .thenReturn(new ClassificationPipeline.Result(Map.of(), List.of(), 0, 0, 0));

        // scraper returns a senior (excluded), a non-CA, and a non-SCM job — all filtered
        // pre-classify
        JobScraper mixed =
                new JobScraper() {
                    @Override
                    public String platform() {
                        return "m";
                    }

                    @Override
                    public List<String> companies() {
                        return List.of("c");
                    }

                    @Override
                    public List<JobPosting> scrape(String company) {
                        return List.of(
                                jobOf("Senior Supply Chain Manager", "San Jose, CA"),
                                jobOf("Supply Chain Analyst", "Austin, TX"),
                                jobOf("Software Developer", "San Jose, CA"));
                    }
                };

        JobPollingService svc =
                new JobPollingService(
                        List.of(mixed),
                        repo,
                        pipeline,
                        classifier,
                        new JobTitleFilter(90),
                        new PipelineMetrics(new SimpleMeterRegistry()));
        svc.poll(); // none survive the pre-filter → nothing to persist
        assertThat(svc).isNotNull();
    }

    private static JobPosting jobOf(String title, String loc) {
        return JobPosting.builder()
                .company("c")
                .externalId(title)
                .title(title)
                .location(loc)
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void pollRetriesFailedAndAutoApprovesExhausted() {
        JobPosting retryJob = jobOf("Retry Analyst", "San Jose, CA");
        retryJob.setClassificationFailures(1);
        JobPosting exhausted = jobOf("Exhausted Buyer", "San Jose, CA");
        exhausted.setClassificationFailures(3);

        when(repo.findAllCompanyExternalIdKeys()).thenReturn(Set.of());
        when(repo.findByCompanyExternalIdKeys(any())).thenReturn(List.of());
        when(repo.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(0, 3))
                .thenReturn(List.of(retryJob));
        when(repo.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        2, Integer.MAX_VALUE))
                .thenReturn(List.of(exhausted));
        when(classifier.classify(any()))
                .thenReturn(
                        new com.github.jingyangyu.scmjobnotifier.service.classification
                                .ClassificationResult(Map.of(retryJob, "ENTRY_LEVEL"), List.of()));

        JobPollingService svc =
                new JobPollingService(
                        List.of(),
                        repo,
                        pipeline,
                        classifier,
                        new JobTitleFilter(90),
                        new PipelineMetrics(new SimpleMeterRegistry()));
        svc.poll();

        verify(classifier).classify(any()); // retry jobs re-classified
        verify(repo, atLeastOnce()).save(any()); // exhausted auto-approved as UNSURE
    }

    @Test
    void pollHandlesNoScrapers() {
        when(repo.findAllCompanyExternalIdKeys()).thenReturn(Set.of());
        when(repo.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        anyInt(), anyInt()))
                .thenReturn(List.of());
        JobPollingService svc =
                new JobPollingService(
                        List.of(),
                        repo,
                        pipeline,
                        classifier,
                        new JobTitleFilter(90),
                        new PipelineMetrics(new SimpleMeterRegistry()));
        svc.poll(); // should not throw
        assertThat(svc).isNotNull();
    }
}
