package com.github.jingyangyu.scmjobnotifier.service.classification;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.service.PipelineMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

/**
 * Orchestrates job classification via Gemini with batching, rate limiting, and retry.
 *
 * <p>This class handles the "how" of classification (batching, pacing, error handling) while {@link
 * GeminiClient} handles the "what" (API calls, prompt building, response parsing).
 *
 * <p>Uses a single 4-way SCM track classification (ENTRY_LEVEL/INTERNSHIP/UNSURE/OTHER) per batch.
 *
 * <p>Design decisions:
 *
 * <ul>
 *   <li>Jobs are sent in batches of {@value #BATCH_SIZE} to stay within token limits.
 *   <li>Each batch is retried up to 3 times with exponential backoff (5s -> 10s -> 20s).
 *   <li>When no API key is configured, all jobs are returned as UNSURE — this lets the app run in
 *       dev mode without Gemini (UNSURE is still emailed), relying on local title/description
 *       rules.
 * </ul>
 */
@Slf4j
@Service
public class JobClassifier {

    private static final int BATCH_SIZE = 50;

    /** Tracks that get emailed (used only for the classified-count metric). */
    private static final Set<String> NOTIFIABLE = Set.of("ENTRY_LEVEL", "INTERNSHIP", "UNSURE");

    private final GeminiClient geminiClient;
    private final PipelineMetrics metrics;
    private final RetryTemplate retryTemplate;

    public JobClassifier(GeminiClient geminiClient, PipelineMetrics metrics) {
        this.geminiClient = geminiClient;
        this.metrics = metrics;
        this.retryTemplate =
                RetryTemplate.builder().maxAttempts(3).exponentialBackoff(5000, 2, 20000).build();
    }

    /**
     * Classifies a list of job postings via Gemini's 4-way track classification in batches.
     *
     * <p>Returns a {@link ClassificationResult} containing the track map and failed jobs.
     *
     * <p>If no API key is configured, all jobs are mapped to "UNSURE" — intentional so the app can
     * run in dev mode without Gemini (UNSURE jobs are still emailed, flagged as unclassified).
     */
    public ClassificationResult classify(List<JobPosting> jobs) {
        if (jobs.isEmpty()) {
            return new ClassificationResult(Collections.emptyMap(), Collections.emptyList());
        }
        if (!geminiClient.isConfigured()) {
            log.warn(
                    "Gemini API key not configured — returning all {} job(s) as UNSURE",
                    jobs.size());
            Map<JobPosting, String> allUnsure = new HashMap<>();
            jobs.forEach(j -> allUnsure.put(j, "UNSURE"));
            return new ClassificationResult(allUnsure, Collections.emptyList());
        }

        int totalBatches = (int) Math.ceil((double) jobs.size() / BATCH_SIZE);
        log.info("Gemini classification: {} job(s) in {} batch(es)", jobs.size(), totalBatches);
        Map<JobPosting, String> levelMap = new HashMap<>();
        List<JobPosting> failed = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i += BATCH_SIZE) {
            int batchNum = (i / BATCH_SIZE) + 1;
            List<JobPosting> batch = jobs.subList(i, Math.min(i + BATCH_SIZE, jobs.size()));
            Map<JobPosting, String> batchResult = classifyBatchWithRetry(batch);
            if (batchResult == null) {
                failed.addAll(batch);
                metrics.recordGeminiFail();
                log.warn(
                        "Gemini batch {}/{} failed — {} job(s) will retry next poll",
                        batchNum,
                        totalBatches,
                        batch.size());
            } else {
                levelMap.putAll(batchResult);
                metrics.recordGeminiSuccess();
                long notifiableCount =
                        batchResult.values().stream().filter(NOTIFIABLE::contains).count();
                metrics.recordJobsClassified((int) notifiableCount);
                log.info(
                        "Gemini batch {}/{}: {} entry, {} intern, {} unsure, {} other (of {})",
                        batchNum,
                        totalBatches,
                        batchResult.values().stream().filter("ENTRY_LEVEL"::equals).count(),
                        batchResult.values().stream().filter("INTERNSHIP"::equals).count(),
                        batchResult.values().stream().filter("UNSURE"::equals).count(),
                        batchResult.values().stream().filter("OTHER"::equals).count(),
                        batch.size());
            }
        }

        log.info(
                "Gemini classification complete: {} classified ({} entry, {} intern, {} unsure,"
                        + " {} other), {} failed",
                levelMap.size(),
                levelMap.values().stream().filter("ENTRY_LEVEL"::equals).count(),
                levelMap.values().stream().filter("INTERNSHIP"::equals).count(),
                levelMap.values().stream().filter("UNSURE"::equals).count(),
                levelMap.values().stream().filter("OTHER"::equals).count(),
                failed.size());

        return new ClassificationResult(levelMap, failed);
    }

    /**
     * Sends a single batch to Gemini for 4-way track classification with retry support.
     *
     * @return map of job to track string, or {@code null} if the API call failed after all retries.
     */
    private Map<JobPosting, String> classifyBatchWithRetry(List<JobPosting> batch) {
        log.info("Classifying batch of {} job(s) via Gemini", batch.size());
        try {
            return retryTemplate.execute(
                    context -> {
                        if (context.getRetryCount() > 0) {
                            metrics.recordGeminiRetry();
                            log.warn(
                                    "Gemini retry attempt {} for batch of {}",
                                    context.getRetryCount(),
                                    batch.size());
                        }
                        return geminiClient.classifyLevel(batch);
                    });
        } catch (Exception e) {
            log.error(
                    "Gemini classification failed after retries, skipping batch of {}",
                    batch.size(),
                    e);
            return null;
        }
    }
}
