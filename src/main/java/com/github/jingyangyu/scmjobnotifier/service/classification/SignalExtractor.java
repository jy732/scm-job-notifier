package com.github.jingyangyu.scmjobnotifier.service.classification;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the job evidence Stage 3 sends to Gemini, and runs the local Stage-2 description rules.
 *
 * <p><b>Why this is section-based rather than keyword-based.</b> It previously mined up to 3
 * keyword windows from a 13-word list. Backtested against 3,761 stored JDs that left <b>84.5% of
 * the jobs that actually reach Gemini with an empty payload</b> (median 0 chars) — Stages 1-2
 * already resolve everything with an obvious marker, so the jobs arriving here are exactly the ones
 * those keywords cannot see. Two of the keywords ("rising junior", "current student") never matched
 * a single stored JD.
 *
 * <p>The replacement sends the <b>Responsibilities / Qualifications sections</b> when the posting
 * has them, and the head of the description when it does not — never empty. Sections are preferred
 * over a blind head-truncation because 47% of discriminating phrases sit beyond the first 1500
 * characters, after the company blurb.
 *
 * <p>Measured effect on 40 known-answer retail postings, repeated 4x at production settings: the
 * old payload returned OTHER, OTHER, UNSURE, OTHER — the new one returned OTHER every time.
 * Accuracy across three graded groups went 116/120 to 120/120. Cost is ~5.6x the prompt tokens,
 * which at ~28 jobs per poll is a few cents a day.
 */
public final class SignalExtractor {

    private SignalExtractor() {}

    /** Max characters taken from each matched section. */
    private static final int SECTION_CAP = 1200;

    /** Max characters taken from the description head when no section heading is found. */
    private static final int HEAD_CAP = 2500;

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    /** Headings that introduce what the job actually involves. */
    private static final Pattern RESPONSIBILITIES =
            Pattern.compile(
                    "(?i)(responsibilities|what you.{0,3}ll do|duties|the role|day.to.day"
                            + "|essential functions|job summary)");

    /** Headings that introduce the bar for the job — experience, education, enrollment. */
    private static final Pattern QUALIFICATIONS =
            Pattern.compile(
                    "(?i)(qualifications|requirements|what you.{0,3}ll (bring|need)"
                            + "|experience required|who you are|minimum)");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Phrases that strongly indicate an <b>internship</b> when found in a description: the role
     * requires the candidate to be a currently-enrolled student. Checked before YOE in {@link
     * #inferLevelFromDescription}.
     */
    static final List<String> INTERNSHIP_ENROLLMENT_SIGNALS =
            List.of(
                    "currently enrolled",
                    "must be enrolled",
                    "actively enrolled",
                    "enrolled in a",
                    "enrolled in an",
                    "pursuing a bachelor",
                    "pursuing a master",
                    "pursuing an undergraduate",
                    "pursuing a degree",
                    "working towards a degree",
                    "working toward a degree",
                    "rising junior",
                    "rising senior",
                    "expected graduation",
                    "current student");

    /**
     * Matches YOE patterns like "2+ years", "3-5 years", "0-1 years experience". Captures the first
     * number (range start) so we can infer level from experience requirements.
     */
    private static final Pattern YOE_PATTERN =
            Pattern.compile("(?i)(\\d+)\\s*[+\\-–]\\s*(?:\\d+\\s*)?(?:years|yrs|yoe)");

    /**
     * Builds the evidence block for one job: the Responsibilities/Qualifications sections when the
     * posting has them, otherwise the head of the description.
     *
     * @return cleaned evidence text, or {@code "(none)"} when the posting carries no description at
     *     all — the only case where Gemini still sees the title alone.
     */
    public static String describeForPrompt(JobPosting job) {
        String description = job.getDescription() == null ? "" : job.getDescription();
        String clean =
                WHITESPACE
                        .matcher(HTML_TAG_PATTERN.matcher(description).replaceAll(" "))
                        .replaceAll(" ")
                        .trim();
        if (clean.isEmpty()) {
            return "(none)";
        }
        StringBuilder sections = new StringBuilder();
        for (Pattern heading : List.of(RESPONSIBILITIES, QUALIFICATIONS)) {
            Matcher m = heading.matcher(clean);
            if (m.find()) {
                if (sections.length() > 0) {
                    sections.append(" … ");
                }
                sections.append(
                        clean, m.start(), Math.min(clean.length(), m.start() + SECTION_CAP));
            }
        }
        return sections.length() > 0
                ? sections.toString()
                : clean.substring(0, Math.min(clean.length(), HEAD_CAP));
    }

    /**
     * Infers a track from description signals without calling Gemini (Stage 2). Per Decision D4,
     * entry-level spans 0–3 YOE.
     *
     * <ol>
     *   <li>An internship enrollment signal ("currently enrolled", "pursuing a degree") → {@code
     *       INTERNSHIP} (checked first — enrollment beats YOE).
     *   <li>YOE &gt; 3 → {@code OTHER} (too senior).
     *   <li>YOE 0–3 (no enrollment signal) → {@code ENTRY_LEVEL}.
     *   <li>Otherwise {@code null} → deferred to Gemini.
     * </ol>
     *
     * @return "INTERNSHIP", "ENTRY_LEVEL", "OTHER", or {@code null} if no confident determination.
     */
    public static String inferLevelFromDescription(JobPosting job) {
        String description = job.getDescription();
        if (description == null || description.isBlank()) {
            return null;
        }
        String clean = HTML_TAG_PATTERN.matcher(description).replaceAll(" ");
        String lower = clean.toLowerCase(Locale.ROOT);

        // Enrollment signal → internship, regardless of any stated YOE.
        if (INTERNSHIP_ENROLLMENT_SIGNALS.stream().anyMatch(lower::contains)) {
            return "INTERNSHIP";
        }

        Matcher m = YOE_PATTERN.matcher(clean);
        if (m.find()) {
            int yoe = Integer.parseInt(m.group(1));
            if (yoe > 3) {
                return "OTHER";
            }
            return "ENTRY_LEVEL"; // 0–3 years
        }

        return null;
    }
}
