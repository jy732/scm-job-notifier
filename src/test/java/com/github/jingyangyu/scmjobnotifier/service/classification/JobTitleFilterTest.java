package com.github.jingyangyu.scmjobnotifier.service.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class JobTitleFilterTest {

    private final JobTitleFilter filter = new JobTitleFilter(90);

    private static JobPosting job(String title, String location) {
        return JobPosting.builder()
                .company("x")
                .externalId("1")
                .title(title)
                .location(location)
                .detectedAt(Instant.now())
                .build();
    }

    private static JobPosting titled(String title) {
        return job(title, "San Jose, CA");
    }

    // ── isFresh ──
    @Test
    void freshWhenNoPostedDate() {
        assertThat(filter.isFresh(titled("Buyer"))).isTrue();
    }

    @Test
    void freshWhenRecent() {
        JobPosting j = titled("Buyer");
        j.setPostedDate(Instant.now().minus(1, ChronoUnit.DAYS));
        assertThat(filter.isFresh(j)).isTrue();
    }

    @Test
    void staleWhenOld() {
        JobPosting j = titled("Buyer");
        j.setPostedDate(Instant.now().minus(200, ChronoUnit.DAYS));
        assertThat(filter.isFresh(j)).isFalse();
    }

    // ── excludeReason / shouldExclude ──
    @Test
    void seniorityExcluded() {
        assertThat(filter.excludeReason(titled("Senior Supply Chain Analyst")))
                .isEqualTo("seniority");
        assertThat(filter.excludeReason(titled("Procurement Manager"))).isEqualTo("seniority");
        assertThat(filter.shouldExclude(titled("Director of Logistics"))).isTrue();
    }

    @Test
    void leadExcluded() {
        assertThat(filter.excludeReason(titled("Supply Chain Lead"))).isEqualTo("lead");
    }

    @Test
    void leadershipNotExcludedAsLead() {
        // "leadership" must NOT match the \blead\b pattern; it's an entry signal
        assertThat(filter.excludeReason(titled("Supply Chain Leadership Development Program")))
                .isNull();
    }

    @Test
    void shiftLaborExcluded() {
        assertThat(filter.excludeReason(titled("Warehouse Coordinator - 2nd Shift")))
                .isEqualTo("shift-labor");
        assertThat(filter.excludeReason(titled("Logistics Coordinator (Night Shift)")))
                .isEqualTo("shift-labor");
        assertThat(filter.excludeReason(titled("Inventory Specialist - $1.00 Shift Differential")))
                .isEqualTo("shift-labor");
    }

    @Test
    void nonScmRoleExcluded() {
        assertThat(filter.excludeReason(titled("Material Handler"))).isEqualTo("non-scm-role");
        assertThat(filter.excludeReason(titled("Warehouse Associate"))).isEqualTo("non-scm-role");
        assertThat(filter.excludeReason(titled("Purchasing Clerk"))).isEqualTo("non-scm-role");
    }

    @Test
    void nonScmTechnicalExcludedWithoutAnchor() {
        assertThat(filter.excludeReason(titled("Software Engineer, Freight Systems")))
                .isEqualTo("non-scm-technical");
    }

    @Test
    void scmEngineeringKeptViaAnchor() {
        // engineer + SCM anchor ("supply chain") is rescued, not excluded
        assertThat(filter.excludeReason(titled("Supply Chain Engineer"))).isNull();
        assertThat(filter.excludeReason(titled("Supplier Quality Engineer"))).isNull();
    }

    @Test
    void cleanTitleNotExcluded() {
        assertThat(filter.excludeReason(titled("Supply Chain Analyst"))).isNull();
        assertThat(filter.shouldExclude(titled("Buyer"))).isFalse();
    }

    // ── isCaliforniaLocation ──
    @Test
    void californiaDetection() {
        assertThat(filter.isCaliforniaLocation(job("Buyer", "San Jose, CA"))).isTrue();
        assertThat(filter.isCaliforniaLocation(job("Buyer", "California"))).isTrue();
        assertThat(filter.isCaliforniaLocation(job("Buyer", "Fremont"))).isTrue(); // known CA city
    }

    @Test
    void remoteRequiresCaToken() {
        assertThat(filter.isCaliforniaLocation(job("Buyer", "Remote, CA"))).isTrue();
        assertThat(filter.isCaliforniaLocation(job("Buyer", "Remote - US"))).isFalse();
    }

    @Test
    void nonCaliforniaRejected() {
        assertThat(filter.isCaliforniaLocation(job("Buyer", "Austin, TX"))).isFalse();
        assertThat(filter.isCaliforniaLocation(job("Buyer", ""))).isFalse();
        assertThat(filter.isCaliforniaLocation(job("Buyer", null))).isFalse();
    }

    @Test
    void ambiguousCityRejectedWhenNonUsCountry() {
        // "San Jose" also exists in Costa Rica — non-US country reject wins
        assertThat(filter.isCaliforniaLocation(job("Buyer", "San Jose, Costa Rica"))).isFalse();
    }

    // ── isScmRelevant ──
    @Test
    void scmRelevance() {
        assertThat(filter.isScmRelevant(titled("Supply Chain Analyst"))).isTrue();
        assertThat(filter.isScmRelevant(titled("Procurement Specialist"))).isTrue();
        assertThat(filter.isScmRelevant(titled("Software Developer"))).isFalse();
    }

    @Test
    void newPurchaserAndShippingKeywords() {
        assertThat(filter.isScmRelevant(titled("Medical Equipment Purchaser"))).isTrue();
        assertThat(filter.isScmRelevant(titled("International Shipping Specialist"))).isTrue();
    }

    // ── autoClassifyLevel ──
    @Test
    void autoClassifyInternship() {
        assertThat(filter.autoClassifyLevel(titled("Supply Chain Intern"))).isEqualTo("INTERNSHIP");
        assertThat(filter.autoClassifyLevel(titled("Procurement Co-op"))).isEqualTo("INTERNSHIP");
    }

    @Test
    void autoClassifyEntryFromMarker() {
        assertThat(filter.autoClassifyLevel(titled("New Grad Supply Chain Analyst")))
                .isEqualTo("ENTRY_LEVEL");
        assertThat(filter.autoClassifyLevel(titled("Junior Buyer"))).isEqualTo("ENTRY_LEVEL");
        assertThat(filter.autoClassifyLevel(titled("Buyer I"))).isEqualTo("ENTRY_LEVEL");
    }

    @Test
    void autoClassifyEntryFromRoleNoun() {
        assertThat(filter.autoClassifyLevel(titled("Supply Chain Coordinator")))
                .isEqualTo("ENTRY_LEVEL");
        assertThat(filter.autoClassifyLevel(titled("Procurement Assistant")))
                .isEqualTo("ENTRY_LEVEL");
    }

    @Test
    void autoClassifyNullForBareFunctional() {
        assertThat(filter.autoClassifyLevel(titled("Supply Chain Analyst"))).isNull();
        assertThat(filter.autoClassifyLevel(titled("Buyer"))).isNull();
    }
}
