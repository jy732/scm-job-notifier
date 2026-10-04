package com.github.jingyangyu.scmjobnotifier.service.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
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
    void usesResponsibilitiesAndQualificationsSections() {
        String jd =
                "About our company, founded 1952, we value teamwork. "
                        + "Responsibilities: maintain the sales floor and the stock room. "
                        + "Qualifications: high school diploma, 0-2 years.";
        String out = SignalExtractor.describeForPrompt(job("Inventory Associate", jd));
        assertThat(out).contains("sales floor").contains("high school diploma");
        // the company blurb ahead of the first heading is dropped
        assertThat(out).doesNotContain("founded 1952");
    }

    @Test
    void joinsBothSectionsWhenBothPresent() {
        String jd = "Responsibilities: pick orders. Requirements: forklift certification.";
        assertThat(SignalExtractor.describeForPrompt(job("Picker", jd)))
                .contains("pick orders")
                .contains("forklift certification")
                .contains("…");
    }

    @Test
    void fallsBackToDescriptionHeadWhenNoHeadings() {
        String jd = "We need somebody to run daily cycle counts across three sites.";
        assertThat(SignalExtractor.describeForPrompt(job("Analyst", jd))).isEqualTo(jd);
    }

    @Test
    void stripsHtmlAndCollapsesWhitespace() {
        String jd = "<p>Responsibilities:</p>\n\n<ul><li>ship   parcels</li></ul>";
        String out = SignalExtractor.describeForPrompt(job("Clerk", jd));
        assertThat(out).doesNotContain("<").contains("ship parcels");
    }

    @Test
    void capsSectionLength() {
        String jd = "Responsibilities: " + "x".repeat(5000);
        assertThat(SignalExtractor.describeForPrompt(job("Planner", jd)).length())
                .isLessThanOrEqualTo(1200);
    }

    @Test
    void capsHeadLengthWhenNoHeadings() {
        String jd = "y".repeat(5000);
        assertThat(SignalExtractor.describeForPrompt(job("Planner", jd)).length())
                .isLessThanOrEqualTo(2500);
    }

    @Test
    void noneWhenDescriptionMissingOrBlank() {
        assertThat(SignalExtractor.describeForPrompt(job("Buyer", null))).isEqualTo("(none)");
        assertThat(SignalExtractor.describeForPrompt(job("Buyer", "   "))).isEqualTo("(none)");
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
