package com.github.jingyangyu.scmjobnotifier.service.classification;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local filter and Stage-1 classifier for SCM postings. Reduces Gemini API calls by pre-filtering
 * and by classifying titles that carry an explicit early-career marker.
 *
 * <p>Pre-filters (used by {@code JobPollingService#applyPreFilters}), in order:
 *
 * <ul>
 *   <li>{@link #isFresh} — drop postings older than {@code job.retention.days}.
 *   <li>{@link #shouldExclude} — drop senior/staff/manager/director/lead titles (NOT interns).
 *   <li>{@link #isCaliforniaLocation} — keep only California roles (remote requires a CA token).
 *   <li>{@link #isScmRelevant} — require a supply-chain keyword in the title.
 * </ul>
 *
 * <p>Stage-1 classification (used by {@link ClassificationPipeline}):
 *
 * <ul>
 *   <li>{@link #autoClassifyLevel} — INTERNSHIP / ENTRY_LEVEL from title markers, else null.
 * </ul>
 *
 * <p>All keyword lists and patterns live in {@link FilterKeywords}.
 */
@Component
public class JobTitleFilter {

    private final int retentionDays;

    public JobTitleFilter(@Value("${job.retention.days:90}") int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /**
     * Tier 0: Returns true if the job is fresh enough (posted within retention period). Jobs with
     * no posted date (e.g. all Workday jobs) pass through.
     */
    public boolean isFresh(JobPosting job) {
        if (job.getPostedDate() == null) {
            return true;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        return job.getPostedDate().isAfter(cutoff);
    }

    /**
     * Tier 1: Returns true if the title should be excluded — either a seniority marker
     * (senior/staff/manager/director/lead etc.) or a non-SCM role (software/ML/data-science, which
     * can slip through the SCM keyword gate via terms like "freight"/"planner"). Interns are
     * intentionally NOT excluded — they are a target track.
     */
    public boolean shouldExclude(JobPosting job) {
        return excludeReason(job) != null;
    }

    /**
     * Which exclude tier (if any) fires for this title, or {@code null} if it passes. Exposed for the
     * filter audit so it can attribute drops; {@link #shouldExclude} is just {@code != null}.
     *
     * @return "seniority", "lead", "non-scm-role", "non-scm-technical", or null
     */
    public String excludeReason(JobPosting job) {
        String title = job.getTitle().toLowerCase(Locale.ROOT);
        if (FilterKeywords.EXCLUDE_KEYWORDS.stream().anyMatch(title::contains)) {
            return "seniority";
        }
        if (FilterKeywords.EXCLUDE_LEAD_PATTERN.matcher(title).find()) {
            return "lead";
        }
        // Hourly warehouse/clerical labor + materials-science/facilities roles that pass the SCM
        // keyword gate but aren't the professional SCM roles we target.
        if (FilterKeywords.NON_SCM_ROLE_KEYWORDS.stream().anyMatch(title::contains)) {
            return "non-scm-role";
        }
        // Non-SCM technical role (engineer/scientist/developer) — excluded UNLESS the title carries
        // a strong SCM anchor, which keeps genuine SCM engineering roles (SQE, Supply Chain
        // Engineer, Sourcing Engineer) while dropping software/materials-science titles.
        if (FilterKeywords.NON_SCM_PATTERN.matcher(title).find()
                && FilterKeywords.SCM_ANCHOR_KEYWORDS.stream().noneMatch(title::contains)) {
            return "non-scm-technical";
        }
        return null;
    }

    /**
     * Stage 1: Auto-classifies a job's SCM track from the title alone.
     *
     * <p>Precedence: INTERNSHIP (explicit intern token) beats ENTRY_LEVEL; explicit entry markers
     * beat entry-only role nouns (coordinator/assistant/administrator). Bare functional titles
     * ("Supply Chain Analyst", "Buyer") return null and defer to Stages 2–3.
     *
     * @return "INTERNSHIP", "ENTRY_LEVEL", or null if the title is ambiguous.
     */
    public String autoClassifyLevel(JobPosting job) {
        String title = job.getTitle();
        if (FilterKeywords.INTERNSHIP_PATTERN.matcher(title).find()) {
            return "INTERNSHIP";
        }
        if (FilterKeywords.ENTRY_LEVEL_PATTERN.matcher(title).find()) {
            return "ENTRY_LEVEL";
        }
        String lower = title.toLowerCase(Locale.ROOT);
        if (FilterKeywords.ENTRY_ROLE_NOUNS.stream().anyMatch(lower::contains)) {
            return "ENTRY_LEVEL";
        }
        return null;
    }

    /** Tier 3 gate: Returns true if the title contains at least one supply-chain keyword. */
    public boolean isScmRelevant(JobPosting job) {
        String title = job.getTitle().toLowerCase(Locale.ROOT);
        return FilterKeywords.SCM_KEYWORDS.stream().anyMatch(title::contains);
    }

    /**
     * Tier 1.5: California-only location filter (Decision D2: CA-tokened only).
     *
     * <p>Detection strategy:
     *
     * <ol>
     *   <li>Reject blank locations — a CA-specific notifier must not accept unlocated roles.
     *   <li>A "CA token" is the word "california" or a ", CA" state token.
     *   <li>"Remote" is accepted only if a CA token is also present (bare/US-remote is dropped).
     *   <li>Any CA token → accept.
     *   <li>Otherwise, reject if a known non-US country is named, then accept if a known CA city
     *       appears (this catches "San Jose" / "Irvine" without an explicit ", CA").
     *   <li>Anything else → reject.
     * </ol>
     *
     * @return true only for locations we can positively tie to California.
     */
    public boolean isCaliforniaLocation(JobPosting job) {
        String location = job.getLocation();
        if (location == null || location.isBlank()) {
            return false;
        }
        String loc = location.toLowerCase(Locale.ROOT);

        boolean hasCaToken =
                loc.contains("california") || FilterKeywords.CA_STATE_PATTERN.matcher(loc).find();

        if (loc.contains("remote")) {
            return hasCaToken;
        }
        if (hasCaToken) {
            return true;
        }

        for (String country : FilterKeywords.NON_US_COUNTRIES) {
            if (loc.contains(country)) {
                return false;
            }
        }
        for (String city : FilterKeywords.CA_CITIES) {
            if (loc.contains(city)) {
                return true;
            }
        }
        return false;
    }
}
