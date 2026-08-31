package com.github.jingyangyu.scmjobnotifier.service.classification;

/**
 * Provenance values for {@link
 * com.github.jingyangyu.scmjobnotifier.model.JobPosting#classificationSource} — how a job's {@code
 * level} was assigned. Declared on an interface so it's a pure constants holder (no instantiable
 * type). {@link #FALLBACK_UNSURE} and {@link #AUTO_APPROVED} are the low-confidence outcomes a
 * classification replay re-runs once Gemini is healthy again.
 */
public interface ClassificationSource {
    /** Stage 1 — assigned by a local title rule. */
    String TITLE_RULE = "TITLE_RULE";

    /** Stage 2 — assigned by a local description-signal rule. */
    String DESC_RULE = "DESC_RULE";

    /** Stage 3 — a confident Gemini verdict. */
    String GEMINI = "GEMINI";

    /** Stage 3 fallback — Gemini unconfigured/degraded, so UNSURE was assumed. */
    String FALLBACK_UNSURE = "FALLBACK_UNSURE";

    /** Auto-approved as UNSURE after exhausting Gemini retries. */
    String AUTO_APPROVED = "AUTO_APPROVED";
}
