package com.github.jingyangyu.scmjobnotifier.service;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.repository.JobPostingRepository;
import com.github.jingyangyu.scmjobnotifier.service.classification.ClassificationPipeline;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Re-runs classification for jobs that were classified during a <em>degraded</em> window (Gemini
 * unconfigured/failing) and re-queues any that flip to (or upgrade toward) a notifiable track — so
 * alerts missed while classification was degraded get sent once Gemini is healthy again.
 *
 * <p>Two things keep this safe:
 *
 * <ul>
 *   <li>It never emails directly. It only re-classifies and, on a <em>material upgrade</em>, resets
 *       {@code notified=false}; the existing 5-minute alert scan does the sending (preserving
 *       dedup, retry, and the daily-summary safety net).
 *   <li>{@link #shouldRequeue} re-sends only when relevance actually improves — a job never
 *       classified/emailed becomes notifiable, or a vague {@code UNSURE} becomes a confident {@code
 *       ENTRY_LEVEL}/{@code INTERNSHIP}. Lateral/downgrade changes just correct the stored record
 *       without re-notifying.
 * </ul>
 *
 * <p>Targets are selected by provenance ({@code FALLBACK_UNSURE}/{@code AUTO_APPROVED}) and/or a
 * {@code [since, until)} detection window (for legacy rows with no provenance). {@code dryRun}
 * returns the transition breakdown without writing, so the blast radius is visible before any
 * re-send.
 */
@Slf4j
@Service
public class ClassificationReplayService {

    private static final Set<String> NOTIFIABLE = Set.of("ENTRY_LEVEL", "INTERNSHIP", "UNSURE");
    private static final Set<String> CONFIDENT_NOTIFIABLE = Set.of("ENTRY_LEVEL", "INTERNSHIP");

    private final JobPostingRepository repository;
    private final ClassificationPipeline pipeline;
    private final int retentionDays;

    public ClassificationReplayService(
            JobPostingRepository repository,
            ClassificationPipeline pipeline,
            @Value("${job.retention.days:90}") int retentionDays) {
        this.repository = repository;
        this.pipeline = pipeline;
        this.retentionDays = retentionDays;
    }

    /**
     * Selects jobs (by {@code sources} provenance, else by {@code [since, until)} window), re-runs
     * the classification pipeline over them, and re-queues material upgrades for alerting.
     *
     * @param sources provenance values to target (e.g. FALLBACK_UNSURE, AUTO_APPROVED); if
     *     null/empty the window selector is used instead
     * @param since inclusive lower bound on {@code detectedAt} (window mode)
     * @param until exclusive upper bound on {@code detectedAt} (window mode)
     * @param limit max jobs to re-classify (≤0 = no cap) — bounds Gemini cost
     * @param dryRun when true, computes transitions but writes nothing
     */
    public ReplaySummary replay(
            Set<String> sources, Instant since, Instant until, int limit, boolean dryRun) {
        List<JobPosting> selected;
        if (sources != null && !sources.isEmpty()) {
            selected = repository.findByClassificationSourceIn(sources);
        } else if (since != null && until != null) {
            selected = repository.findByDetectedAtBetween(since, until);
        } else {
            return ReplaySummary.error(
                    "no selector: provide 'sources' or both 'since' and 'until'");
        }
        int selectedCount = selected.size();

        // Skip jobs the cleanup job will delete anyway (postedDate older than retention); keep
        // null-postedDate rows since those are never cleaned.
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<JobPosting> targets =
                selected.stream()
                        .filter(
                                j ->
                                        j.getPostedDate() == null
                                                || !j.getPostedDate().isBefore(cutoff))
                        .toList();
        int skippedStale = selectedCount - targets.size();

        if (limit > 0 && targets.size() > limit) {
            targets = targets.subList(0, limit);
        }

        Map<String, Integer> byTransition = new TreeMap<>();
        int reclassified = 0;
        int requeued = 0;
        int geminiUnavailable = 0;

        if (!targets.isEmpty()) {
            ClassificationPipeline.Result result = pipeline.classify(targets);
            Map<JobPosting, String> newLevels = result.levelMap();
            Map<JobPosting, String> newSources = result.sourceMap();
            List<JobPosting> toSave = new ArrayList<>();
            for (JobPosting job : targets) {
                String newLevel = newLevels.get(job);
                if (newLevel == null) {
                    geminiUnavailable++; // Gemini still failing for this job — leave it untouched
                    continue;
                }
                String oldLevel = job.getLevel();
                byTransition.merge(label(oldLevel) + "->" + newLevel, 1, Integer::sum);
                reclassified++;
                boolean requeue = shouldRequeue(oldLevel, newLevel);
                if (requeue) {
                    requeued++;
                }
                if (!dryRun) {
                    job.setLevel(newLevel);
                    job.setClassificationSource(newSources.get(job));
                    if (requeue) {
                        job.setNotified(false);
                    }
                    toSave.add(job);
                }
            }
            if (!dryRun && !toSave.isEmpty()) {
                repository.saveAll(toSave);
            }
        }

        log.info(
                "Classification replay: selected={}, reclassified={}, requeued={}, skippedStale={},"
                        + " geminiUnavailable={}, dryRun={}",
                selectedCount,
                reclassified,
                requeued,
                skippedStale,
                geminiUnavailable,
                dryRun);
        return new ReplaySummary(
                selectedCount,
                reclassified,
                requeued,
                skippedStale,
                geminiUnavailable,
                byTransition,
                dryRun,
                null);
    }

    /**
     * Re-send only on a material relevance upgrade: a non-notifiable (or never-classified) job that
     * becomes notifiable, or a vague {@code UNSURE} that becomes a confident notifiable track.
     */
    private static boolean shouldRequeue(String oldLevel, String newLevel) {
        if (!isNotifiable(newLevel)) {
            return false; // OTHER / non-notifiable — never emailed
        }
        if (!isNotifiable(oldLevel)) {
            return true; // was OTHER / null / non-notifiable → now surfaces
        }
        // both notifiable: only re-send when upgrading vague UNSURE → confident ENTRY/INTERNSHIP
        return "UNSURE".equals(oldLevel) && CONFIDENT_NOTIFIABLE.contains(newLevel);
    }

    private static boolean isNotifiable(String level) {
        return level != null && NOTIFIABLE.contains(level);
    }

    private static String label(String level) {
        return level == null ? "NULL" : level;
    }

    /**
     * Outcome of a replay run.
     *
     * @param selected jobs matched by the selector
     * @param reclassified jobs the pipeline produced a (non-failed) level for
     * @param requeued jobs whose {@code notified} was reset for re-alerting (0 in dry-run counts
     *     the jobs that <em>would</em> be re-queued)
     * @param skippedStale selected jobs skipped because they're past retention
     * @param geminiUnavailable selected jobs Gemini still couldn't classify (left untouched)
     * @param byTransition count per {@code oldLevel->newLevel} transition (NULL = previously
     *     unclassified)
     * @param dryRun whether writes were suppressed
     * @param error non-null when the request was rejected (e.g. no selector)
     */
    public record ReplaySummary(
            int selected,
            int reclassified,
            int requeued,
            int skippedStale,
            int geminiUnavailable,
            Map<String, Integer> byTransition,
            boolean dryRun,
            String error) {

        public static ReplaySummary error(String message) {
            return new ReplaySummary(0, 0, 0, 0, 0, Map.of(), false, message);
        }
    }
}
