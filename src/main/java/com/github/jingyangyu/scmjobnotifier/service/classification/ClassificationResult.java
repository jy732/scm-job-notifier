package com.github.jingyangyu.scmjobnotifier.service.classification;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * Result of Gemini 4-way SCM track classification (ENTRY_LEVEL/INTERNSHIP/UNSURE/OTHER).
 *
 * <p>Contains two outputs:
 *
 * <ul>
 *   <li>{@code levelMap} — maps each successfully classified job to its track string.
 *   <li>{@code failed} — jobs that Gemini could not process (e.g. 429/timeout); NOT persisted so
 *       they remain "unseen" and retry on the next poll cycle.
 * </ul>
 *
 * <p>ENTRY_LEVEL, INTERNSHIP, and UNSURE are all notified; OTHER is stored but not emailed.
 */
@Getter
public class ClassificationResult {

    /** 4-way track classification: job → ENTRY_LEVEL/INTERNSHIP/UNSURE/OTHER. */
    private final Map<JobPosting, String> levelMap;

    /** Jobs where Gemini API calls failed after retries. */
    private final List<JobPosting> failed;

    /**
     * Provenance per classified job (see {@link ClassificationSource}): {@code GEMINI} for a real
     * verdict, {@code FALLBACK_UNSURE} when Gemini was unconfigured. Parallel to {@code levelMap}.
     */
    private final Map<JobPosting, String> sourceMap;

    public ClassificationResult(Map<JobPosting, String> levelMap, List<JobPosting> failed) {
        this(levelMap, failed, Map.of());
    }

    public ClassificationResult(
            Map<JobPosting, String> levelMap,
            List<JobPosting> failed,
            Map<JobPosting, String> sourceMap) {
        this.levelMap = levelMap;
        this.failed = failed;
        this.sourceMap = sourceMap;
    }
}
