package com.github.jingyangyu.scmjobnotifier.service.classification;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts track-relevant signals from job postings by searching titles and descriptions for
 * keywords that indicate whether a role is an internship, entry-level, or neither (e.g. "currently
 * enrolled", "3+ years", "new grad").
 *
 * <p>Stateless utility. Each keyword match produces a {@link Signal} with a ~{@value
 * #SIGNAL_WINDOW} -char context snippet and its source (title vs. description). At most {@value
 * #MAX_SIGNALS} signals are returned per job to keep Gemini prompts concise.
 */
public final class SignalExtractor {

    private SignalExtractor() {}

    private static final int SIGNAL_WINDOW = 200;
    private static final int MAX_SIGNALS = 3;
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    /**
     * Consolidated signal keywords used for Gemini prompt building. Ordered roughly by
     * discriminative value — YOE first, then internship enrollment signals, then entry-level
     * signals.
     */
    static final List<String> SIGNAL_KEYWORDS =
            List.of(
                    // YOE / experience (strong level discriminator)
                    "years",
                    // Internship / enrollment signals
                    "currently enrolled",
                    "pursuing",
                    "expected graduation",
                    "rising junior",
                    "rising senior",
                    "current student",
                    "must be enrolled",
                    // Entry-level / new-grad signals
                    "new grad",
                    "new graduate",
                    "recent graduate",
                    "entry level",
                    "entry-level");

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
     * Extracts signals from a job posting's title and description.
     *
     * @return list of up to {@value #MAX_SIGNALS} signals, empty if no keywords found
     */
    public static List<Signal> extract(JobPosting job) {
        String title = job.getTitle() != null ? job.getTitle() : "";
        String description = job.getDescription() != null ? job.getDescription() : "";

        List<Signal> signals = new ArrayList<>();
        extractFrom(title, Signal.Source.TITLE, signals);
        if (signals.size() < MAX_SIGNALS) {
            extractFrom(description, Signal.Source.DESCRIPTION, signals);
        }
        return Collections.unmodifiableList(signals);
    }

    /**
     * Formats extracted signals into a string for Gemini prompts. Returns "(none)" if no signals
     * were found.
     */
    public static String format(List<Signal> signals) {
        if (signals.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < signals.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append('"').append(signals.get(i).snippet()).append('"');
        }
        return sb.toString();
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

    private static void extractFrom(String text, Signal.Source source, List<Signal> signals) {
        if (text == null || text.isBlank()) {
            return;
        }
        String clean = HTML_TAG_PATTERN.matcher(text).replaceAll(" ");
        String lower = clean.toLowerCase(Locale.ROOT);

        Set<String> seenSnippets = new LinkedHashSet<>();
        for (String keyword : SIGNAL_KEYWORDS) {
            int idx = 0;
            while (idx < lower.length() && signals.size() < MAX_SIGNALS) {
                int pos = lower.indexOf(keyword, idx);
                if (pos == -1) break;

                int start = Math.max(0, pos - SIGNAL_WINDOW / 2);
                int end = Math.min(clean.length(), pos + keyword.length() + SIGNAL_WINDOW / 2);
                String snippet = clean.substring(start, end).trim().replaceAll("\\s+", " ");

                if (seenSnippets.add(snippet)) {
                    signals.add(new Signal(keyword, snippet, source));
                }
                idx = pos + keyword.length();
            }
            if (signals.size() >= MAX_SIGNALS) break;
        }
    }
}
