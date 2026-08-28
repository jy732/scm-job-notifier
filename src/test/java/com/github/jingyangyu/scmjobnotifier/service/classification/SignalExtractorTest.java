package com.github.jingyangyu.scmjobnotifier.service.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignalExtractorTest {

    private static JobPosting job(String title, String description) {
        return JobPosting.builder()
                .company("x")
                .externalId("1")
                .title(title)
                .description(description)
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void extractFromTitle() {
        List<Signal> signals = SignalExtractor.extract(job("New Grad Analyst", null));
        assertThat(signals).isNotEmpty();
        assertThat(signals.get(0).source()).isEqualTo(Signal.Source.TITLE);
        assertThat(signals.get(0).keyword()).isEqualTo("new grad");
    }

    @Test
    void extractFromDescriptionWhenTitleThin() {
        List<Signal> signals =
                SignalExtractor.extract(job("Buyer", "Must be currently enrolled in a program."));
        assertThat(signals).extracting(Signal::source).contains(Signal.Source.DESCRIPTION);
    }

    @Test
    void extractEmptyWhenNoSignals() {
        assertThat(SignalExtractor.extract(job("Buyer", "Great team."))).isEmpty();
        assertThat(SignalExtractor.extract(job(null, null))).isEmpty();
    }

    @Test
    void extractCapsAtMaxSignals() {
        String desc = "years years years years years pursuing new grad recent graduate entry level";
        assertThat(SignalExtractor.extract(job("years", desc))).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void formatNoneWhenEmpty() {
        assertThat(SignalExtractor.format(List.of())).isEqualTo("(none)");
    }

    @Test
    void formatJoinsQuotedSnippets() {
        List<Signal> signals = SignalExtractor.extract(job("New Grad Analyst", null));
        String formatted = SignalExtractor.format(signals);
        assertThat(formatted).startsWith("\"").contains("New Grad");
    }

    @Test
    void inferInternshipFromEnrollment() {
        assertThat(
                        SignalExtractor.inferLevelFromDescription(
                                job("Intern", "Must be enrolled in a BS.")))
                .isEqualTo("INTERNSHIP");
    }

    @Test
    void inferOtherFromHighYoe() {
        assertThat(
                        SignalExtractor.inferLevelFromDescription(
                                job("Buyer", "Requires 8+ years experience")))
                .isEqualTo("OTHER");
    }

    @Test
    void inferEntryFromLowYoe() {
        assertThat(
                        SignalExtractor.inferLevelFromDescription(
                                job("Buyer", "1-2 years of experience")))
                .isEqualTo("ENTRY_LEVEL");
    }

    @Test
    void inferNullWhenNoSignalOrBlank() {
        assertThat(SignalExtractor.inferLevelFromDescription(job("Buyer", "A fun role."))).isNull();
        assertThat(SignalExtractor.inferLevelFromDescription(job("Buyer", null))).isNull();
        assertThat(SignalExtractor.inferLevelFromDescription(job("Buyer", "  "))).isNull();
    }

    @Test
    void inferStripsHtml() {
        assertThat(
                        SignalExtractor.inferLevelFromDescription(
                                job("Buyer", "<p>Must be <b>currently enrolled</b></p>")))
                .isEqualTo("INTERNSHIP");
    }
}
