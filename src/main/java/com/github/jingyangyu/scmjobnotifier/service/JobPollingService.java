package com.github.jingyangyu.scmjobnotifier.service;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.repository.JobPostingRepository;
import com.github.jingyangyu.scmjobnotifier.scraper.JobScraper;
import com.github.jingyangyu.scmjobnotifier.service.classification.ClassificationPipeline;
import com.github.jingyangyu.scmjobnotifier.service.classification.ClassificationResult;
import com.github.jingyangyu.scmjobnotifier.service.classification.JobClassifier;
import com.github.jingyangyu.scmjobnotifier.service.classification.JobTitleFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled polling orchestrator that runs every 15 minutes ({@code job.poll.cron}).
 *
 * <p>Companies within each platform are scraped in parallel (8-thread pool) with a per-company
 * timeout. Each company runs scrape → pre-filter → dedup → fetch descriptions → classify → persist.
 */
@Slf4j
@Service
public class JobPollingService {

    static final int MAX_CLASSIFICATION_FAILURES = 3;
    private static final Duration COMPANY_TIMEOUT = Duration.ofMinutes(3);

    /** Tracks that get emailed. OTHER is stored but never notified. */
    private static final Set<String> NOTIFIABLE = Set.of("ENTRY_LEVEL", "INTERNSHIP", "UNSURE");

    private final List<JobScraper> scrapers;
    private final JobPostingRepository repository;
    private final ClassificationPipeline pipeline;
    private final JobClassifier classifier;
    private final JobTitleFilter titleFilter;
    private final PipelineMetrics metrics;
    private final ExecutorService scrapePool = Executors.newFixedThreadPool(8);

    public JobPollingService(
            List<JobScraper> scrapers,
            JobPostingRepository repository,
            ClassificationPipeline pipeline,
            JobClassifier classifier,
            JobTitleFilter titleFilter,
            PipelineMetrics metrics) {
        this.scrapers = scrapers;
        this.repository = repository;
        this.pipeline = pipeline;
        this.classifier = classifier;
        this.titleFilter = titleFilter;
        this.metrics = metrics;
    }

    /**
     * Main poll loop. Loads known job keys once, then iterates all scrapers in parallel, collecting
     * new notifiable postings. Retries any jobs that failed Gemini classification in a previous
     * cycle. Runs on the configured cron schedule (default: every 15 minutes).
     */
    @Scheduled(cron = "${job.poll.cron}")
    public void poll() {
        log.info("=== POLL CYCLE START ===");
        var timerSample = metrics.startPollTimer();
        long startTime = System.currentTimeMillis();
        List<JobPosting> allNewJobs = new ArrayList<>();
        int totalScraped = 0;
        int totalUnseen = 0;
        int totalAutoApproved = 0;
        int totalSentToGemini = 0;
        int companiesProcessed = 0;

        // Load all known job keys once for O(1) in-memory dedup (replaces N per-job DB queries)
        Set<String> knownKeys = new HashSet<>(repository.findAllCompanyExternalIdKeys());
        log.info("Loaded {} known job keys for dedup", knownKeys.size());

        for (JobScraper scraper : scrapers) {
            log.info(
                    "Processing platform: {} ({} companies)",
                    scraper.platform(),
                    scraper.companies().size());

            for (var result : scrapeAllCompanies(scraper, knownKeys)) {
                totalScraped += result.scraped;
                totalUnseen += result.unseen;
                totalAutoApproved += result.autoApproved;
                totalSentToGemini += result.sentToGemini;
                allNewJobs.addAll(result.approved);
                companiesProcessed++;
            }
        }

        retryFailedClassifications(allNewJobs);

        metrics.stopPollTimer(timerSample);
        metrics.recordJobsScraped(totalScraped);
        metrics.recordJobsAutoApproved(totalAutoApproved);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info(
                "=== POLL CYCLE COMPLETE === companies={}, scraped={}, unseen={},"
                        + " autoApproved={}, sentToGemini={}, newNotifiable={}, elapsed={}s",
                companiesProcessed,
                totalScraped,
                totalUnseen,
                totalAutoApproved,
                totalSentToGemini,
                allNewJobs.size(),
                elapsed / 1000);
    }

    // ── Parallel scraping ──────────────────────────────────────────────────

    /**
     * Scrapes all companies for a platform in parallel via the shared thread pool. Each company is
     * awaited with a timeout so one slow site can't stall the whole cycle.
     */
    private List<CompanyResult> scrapeAllCompanies(JobScraper scraper, Set<String> knownKeys) {
        List<String> companies = scraper.companies();
        List<Future<CompanyResult>> futures =
                companies.stream()
                        .map(
                                company ->
                                        scrapePool.submit(
                                                () ->
                                                        safeProcessCompany(
                                                                scraper, company, knownKeys)))
                        .toList();

        List<CompanyResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            results.add(awaitResult(futures.get(i), scraper, companies.get(i)));
        }
        return results;
    }

    /**
     * Wraps {@link #processCompany} in a try/catch so a single company failure doesn't kill the
     * pool thread.
     */
    private CompanyResult safeProcessCompany(
            JobScraper scraper, String company, Set<String> knownKeys) {
        try {
            return processCompany(scraper, company, knownKeys);
        } catch (Exception e) {
            log.error("Error polling [{}] {} — skipping company", scraper.platform(), company, e);
            return CompanyResult.EMPTY;
        }
    }

    /**
     * Waits up to {@link #COMPANY_TIMEOUT} for a company scrape to finish. On timeout, cancels
     * without interrupting ({@code cancel(false)}) so in-progress DB writes are not corrupted.
     */
    private CompanyResult awaitResult(Future<CompanyResult> future, JobScraper scraper, String co) {
        try {
            return future.get(COMPANY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(false);
            metrics.recordScrapeFail();
            if (e instanceof java.util.concurrent.TimeoutException) {
                log.warn(
                        "Timeout after {}s polling [{}] {} — skipping",
                        COMPANY_TIMEOUT.toSeconds(),
                        scraper.platform(),
                        co);
            } else {
                log.error("Error polling [{}] {} — skipping company", scraper.platform(), co, e);
            }
            return CompanyResult.EMPTY;
        }
    }

    // ── Per-company pipeline ───────────────────────────────────────────────

    /**
     * Full per-company pipeline: scrape → pre-filter → dedup → fetch descriptions → classify →
     * persist. Description fetching is deferred to post-dedup so only unseen jobs pay the cost.
     */
    private CompanyResult processCompany(
            JobScraper scraper, String company, Set<String> knownKeys) {
        List<JobPosting> scraped = scraper.scrape(company);
        List<JobPosting> candidates = applyPreFilters(scraper, company, scraped);
        List<JobPosting> unseen = dedup(candidates, knownKeys);

        if (unseen.isEmpty()) {
            log.info("[{}] {} — 0 unseen, skipping", scraper.platform(), company);
            return new CompanyResult(List.of(), scraped.size(), 0, 0, 0);
        }

        scraper.fetchDescriptions(unseen);

        return classifyAndPersist(scraper, company, scraped.size(), unseen);
    }

    /**
     * Applies the filter funnel: freshness → exclude keywords → California location → SCM
     * relevance. Logs the count at each stage for pipeline observability.
     */
    private List<JobPosting> applyPreFilters(
            JobScraper scraper, String company, List<JobPosting> scraped) {
        List<JobPosting> fresh = scraped.stream().filter(titleFilter::isFresh).toList();
        List<JobPosting> afterExclude =
                fresh.stream().filter(job -> !titleFilter.shouldExclude(job)).toList();
        List<JobPosting> caLocation =
                afterExclude.stream().filter(titleFilter::isCaliforniaLocation).toList();
        List<JobPosting> scmRelevant =
                caLocation.stream().filter(titleFilter::isScmRelevant).toList();

        log.info(
                "[{}] {} — {} scraped → {} fresh → {} after exclude"
                        + " → {} California → {} SCM-relevant",
                scraper.platform(),
                company,
                scraped.size(),
                fresh.size(),
                afterExclude.size(),
                caLocation.size(),
                scmRelevant.size());
        return scmRelevant;
    }

    /**
     * Removes jobs already in the DB (via {@code knownKeys} snapshot) and intra-batch duplicates
     * (via a local {@code seen} set). Both checks use the compound key {@code company:externalId}.
     */
    private List<JobPosting> dedup(List<JobPosting> jobs, Set<String> knownKeys) {
        Set<String> seen = new HashSet<>();
        return jobs.stream()
                .filter(
                        job -> {
                            String key = job.getCompany() + ":" + job.getExternalId();
                            return !knownKeys.contains(key) && seen.add(key);
                        })
                .toList();
    }

    // ── Classification + persistence ───────────────────────────────────────

    /**
     * Classifies unseen jobs by track (ENTRY_LEVEL/INTERNSHIP/UNSURE/OTHER) via the three-stage
     * {@link ClassificationPipeline} and persists all to the DB.
     */
    private CompanyResult classifyAndPersist(
            JobScraper scraper, String company, int scrapedCount, List<JobPosting> unseen) {
        ClassificationPipeline.Result result = pipeline.classify(unseen);
        Map<JobPosting, String> levelMap = result.levelMap();
        List<JobPosting> geminiFailed = result.geminiFailed();

        int persisted = persistJobs(unseen, geminiFailed, levelMap);

        List<JobPosting> allApproved =
                unseen.stream().filter(j -> NOTIFIABLE.contains(levelMap.get(j))).toList();

        int localCount = result.stage1Count() + result.stage2Count();
        log.info(
                "[{}] {} — persisted {}, {} failed: {} notifiable ({} title + {} desc + {} Gemini)",
                scraper.platform(),
                company,
                persisted,
                geminiFailed.size(),
                allApproved.size(),
                result.stage1Count(),
                result.stage2Count(),
                result.stage3Count() - geminiFailed.size());

        return new CompanyResult(
                allApproved, scrapedCount, unseen.size(), localCount, result.stage3Count());
    }

    /**
     * Persists a batch of jobs, merging with any existing rows (upsert-safe). Batch-loads existing
     * rows in a single query to avoid N+1. Sets {@code level} and tracks {@code
     * classificationFailures}.
     *
     * @return the number of jobs persisted
     */
    private int persistJobs(
            List<JobPosting> toProcess,
            List<JobPosting> geminiFailed,
            Map<JobPosting, String> levelMap) {
        Set<String> failedIds =
                geminiFailed.stream().map(JobPosting::getExternalId).collect(Collectors.toSet());
        Instant now = Instant.now();
        Set<String> lookupKeys =
                toProcess.stream()
                        .map(j -> j.getCompany() + ":" + j.getExternalId())
                        .collect(Collectors.toSet());
        Map<String, JobPosting> existingMap =
                repository.findByCompanyExternalIdKeys(lookupKeys).stream()
                        .collect(
                                Collectors.toMap(
                                        j -> j.getCompany() + ":" + j.getExternalId(), j -> j));
        Map<String, JobPosting> toPersistMap = new LinkedHashMap<>();
        for (JobPosting job : toProcess) {
            String key = job.getCompany() + ":" + job.getExternalId();
            if (toPersistMap.containsKey(key)) {
                continue; // skip intra-batch duplicates
            }
            JobPosting target = existingMap.getOrDefault(key, job);
            if (target.getDetectedAt() == null) {
                target.setDetectedAt(now);
            }
            if (failedIds.contains(job.getExternalId())) {
                target.setClassificationFailures(target.getClassificationFailures() + 1);
            } else {
                String level = levelMap.get(job);
                if (level != null) {
                    target.setLevel(level);
                }
                target.setClassificationFailures(0);
            }

            toPersistMap.put(key, target);
        }
        List<JobPosting> toPersist = new ArrayList<>(toPersistMap.values());
        repository.saveAll(toPersist);
        return toPersist.size();
    }

    // ── Gemini retry ───────────────────────────────────────────────────────

    /**
     * Retries Gemini classification for jobs that failed in previous polls. Jobs that have
     * exhausted {@link #MAX_CLASSIFICATION_FAILURES} attempts are auto-approved as UNSURE (Decision
     * D3 — better to over-notify with an "unsure" flag than silently drop a job).
     */
    private void retryFailedClassifications(List<JobPosting> allNewJobs) {
        List<JobPosting> retryJobs =
                repository.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        0, MAX_CLASSIFICATION_FAILURES);
        List<JobPosting> exhaustedJobs =
                repository.findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
                        MAX_CLASSIFICATION_FAILURES - 1, Integer.MAX_VALUE);

        for (JobPosting job : exhaustedJobs) {
            metrics.recordAutoApprovedFallback();
            log.warn(
                    "Auto-approving as UNSURE after {} Gemini failures: [{}] {}",
                    job.getClassificationFailures(),
                    job.getCompany(),
                    job.getTitle());
            job.setLevel("UNSURE");
            job.setClassificationFailures(0);
            repository.save(job);
            allNewJobs.add(job);
        }

        if (retryJobs.isEmpty()) return;

        log.info("=== RETRY === {} job(s) pending Gemini re-classification", retryJobs.size());
        ClassificationResult result = classifier.classify(retryJobs);

        for (Map.Entry<JobPosting, String> entry : result.getLevelMap().entrySet()) {
            JobPosting job = entry.getKey();
            String level = entry.getValue();
            job.setLevel(level);
            job.setClassificationFailures(0);
            repository.save(job);
            if (NOTIFIABLE.contains(level)) {
                allNewJobs.add(job);
            }
        }
        for (JobPosting job : result.getFailed()) {
            job.setClassificationFailures(job.getClassificationFailures() + 1);
            repository.save(job);
        }

        log.info(
                "=== RETRY COMPLETE === {}/{} classified, {} failed",
                result.getLevelMap().size(),
                retryJobs.size(),
                result.getFailed().size());
    }

    // ── Result record ──────────────────────────────────────────────────────

    /** Per-company pipeline result, aggregated in {@link #poll()} for cycle-level stats. */
    private record CompanyResult(
            List<JobPosting> approved,
            int scraped,
            int unseen,
            int autoApproved,
            int sentToGemini) {
        static final CompanyResult EMPTY = new CompanyResult(List.of(), 0, 0, 0, 0);
    }
}
