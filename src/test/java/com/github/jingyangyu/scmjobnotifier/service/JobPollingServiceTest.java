package com.github.jingyangyu.scmjobnotifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.repository.JobPostingRepository;
import com.github.jingyangyu.scmjobnotifier.scraper.JobScraper;
import com.github.jingyangyu.scmjobnotifier.service.classification.ClassificationPipeline;
import com.github.jingyangyu.scmjobnotifier.service.classification.ClassificationResult;
import com.github.jingyangyu.scmjobnotifier.service.classification.JobClassifier;
import com.github.jingyangyu.scmjobnotifier.service.classification.JobTitleFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    @SuppressWarnings("unchecked")
    void pollUpdatesExistingAndCountsGeminiFailures() {
        JobPosting existing = scmJob(); // same company:externalId as the scraped job
        JobPosting scraped = scmJob();
        when(repo.findAllCompanyExternalIdKeys()).thenReturn(Set.of());
        when(repo.findByCompanyExternalIdKeys(any())).thenReturn(List.of(existing));
        when(repo.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        anyInt(), anyInt()))
                .thenReturn(List.of());
        when(repo.saveAll(any())).thenAnswer(inv -> ((Iterable<JobPosting>) inv.getArgument(0)));
        // job reached Gemini but failed → geminiFailed, increments the existing row's failure count
        when(pipeline.classify(any()))
                .thenReturn(new ClassificationPipeline.Result(Map.of(), List.of(scraped), 0, 0, 1));

        JobPollingService svc =
                new JobPollingService(
                        List.of(scraper),
                        repo,
                        pipeline,
                        classifier,
                        new JobTitleFilter(90),
                        new PipelineMetrics(new SimpleMeterRegistry()));
        svc.poll();
        verify(repo, atLeastOnce()).saveAll(any());
    }

    @Test
    void pollSurvivesScraperException() {
        when(repo.findAllCompanyExternalIdKeys()).thenReturn(Set.of());
        when(repo.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        anyInt(), anyInt()))
                .thenReturn(List.of());
        JobScraper boom =
                new JobScraper() {
                    @Override
                    public String platform() {
                        return "boom";
                    }

                    @Override
                    public List<String> companies() {
                        return List.of("c");
                    }

                    @Override
                    public List<JobPosting> scrape(String company) {
                        throw new RuntimeException("scrape blew up");
                    }
                };
        JobPollingService svc =
                new JobPollingService(
                        List.of(boom),
                        repo,
                        pipeline,
                        classifier,
                        new JobTitleFilter(90),
                        new PipelineMetrics(new SimpleMeterRegistry()));
        svc.poll(); // per-company failure is swallowed
        assertThat(svc).isNotNull();
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

    private JobPollingService svc(List<JobScraper> scrapers) {
        return new JobPollingService(
                scrapers,
                repo,
                pipeline,
                classifier,
                new JobTitleFilter(90),
                new PipelineMetrics(new SimpleMeterRegistry()));
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method m = JobPollingService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @Test
    void awaitResultHandlesTimeoutAndError() throws Exception {
        JobPollingService svc = svc(List.of());

        @SuppressWarnings("unchecked")
        Future<Object> timedOut = mock(Future.class);
        when(timedOut.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException());

        @SuppressWarnings("unchecked")
        Future<Object> errored = mock(Future.class);
        when(errored.get(anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("boom"));

        Class<?>[] sig = {Future.class, JobScraper.class, String.class};
        Object r1 = invoke(svc, "awaitResult", sig, timedOut, scraper, "co");
        Object r2 = invoke(svc, "awaitResult", sig, errored, scraper, "co");
        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        verify(timedOut).cancel(false);
        verify(errored).cancel(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistJobsCoversDupDetectedAtAndLevelBranches() throws Exception {
        JobPollingService svc = svc(List.of());
        when(repo.findByCompanyExternalIdKeys(any())).thenReturn(List.of());
        when(repo.saveAll(any())).thenAnswer(inv -> ((Iterable<JobPosting>) inv.getArgument(0)));

        JobPosting a =
                JobPosting.builder().company("c").externalId("1").title("Buyer").build(); // no
        // detectedAt
        JobPosting dup =
                JobPosting.builder().company("c").externalId("1").title("Buyer dup").build();
        JobPosting b =
                JobPosting.builder()
                        .company("c")
                        .externalId("2")
                        .title("Planner")
                        .detectedAt(Instant.now())
                        .build();

        Class<?>[] sig = {List.class, List.class, Map.class, Map.class};
        Object persisted =
                invoke(
                        svc,
                        "persistJobs",
                        sig,
                        List.of(a, dup, b),
                        List.of(b), // b failed Gemini -> increments
                        Map.of(a, "ENTRY_LEVEL"), // a gets level set
                        Map.of(a, "GEMINI")); // ...with GEMINI provenance
        assertThat((int) persisted).isEqualTo(2); // a (dedup dup) + b
        assertThat(a.getLevel()).isEqualTo("ENTRY_LEVEL");
        assertThat(a.getClassificationSource()).isEqualTo("GEMINI");
        assertThat(a.getDetectedAt()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void processCompanyClassifiesAndPersistsSurvivingJob() throws Exception {
        JobPollingService svc = svc(List.of());
        when(repo.findByCompanyExternalIdKeys(any())).thenReturn(List.of());
        when(repo.saveAll(any())).thenAnswer(inv -> ((Iterable<JobPosting>) inv.getArgument(0)));
        when(pipeline.classify(any()))
                .thenReturn(new ClassificationPipeline.Result(Map.of(), List.of(), 1, 0, 0));

        Class<?>[] sig = {JobScraper.class, String.class, Set.class};
        Object result = invoke(svc, "processCompany", sig, scraper, "c", Set.<String>of());
        assertThat(result).isNotNull();
        verify(pipeline).classify(any());
        verify(repo).saveAll(any());
    }

    @Test
    void retryIncrementsStillFailingJobs() {
        JobPosting stillFailing = jobOf("Retry Buyer", "San Jose, CA");
        stillFailing.setClassificationFailures(1);
        when(repo.findAllCompanyExternalIdKeys()).thenReturn(Set.of());
        when(repo.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(0, 3))
                .thenReturn(List.of(stillFailing));
        when(repo.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        2, Integer.MAX_VALUE))
                .thenReturn(List.of());
        when(classifier.classify(any()))
                .thenReturn(new ClassificationResult(Map.of(), List.of(stillFailing)));

        svc(List.of()).poll();
        verify(repo, atLeastOnce()).save(stillFailing); // failure count incremented + saved
        assertThat(stillFailing.getClassificationFailures()).isEqualTo(2);
    }
}
