package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class AshbyScraperTest {

    private static final String LIST =
            "{\"jobs\":[{\"id\":\"h1\",\"title\":\"Buyer\",\"jobUrl\":\"https://x/h1\","
                    + "\"location\":\"Los Angeles, CA\","
                    + "\"secondaryLocations\":[{\"location\":\"Irvine, CA\"}],"
                    + "\"descriptionPlain\":\"desc\",\"publishedAt\":\"2026-08-01T00:00:00Z\"}]}";

    @Test
    void platformAndCompanies() {
        AshbyScraper s = new AshbyScraper(WebClientStubs.json(u -> LIST), "hadrian, mach");
        assertThat(s.platform()).isEqualTo("ashby");
        assertThat(s.companies()).containsExactly("hadrian", "mach");
    }

    @Test
    void scrapeParsesJobsWithSecondaryLocations() {
        AshbyScraper s = new AshbyScraper(WebClientStubs.json(u -> LIST), "hadrian");
        List<JobPosting> jobs = s.scrape("hadrian");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("h1");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getUrl()).isEqualTo("https://x/h1");
        assertThat(j.getLocation()).isEqualTo("Los Angeles, CA; Irvine, CA");
        assertThat(j.getDescription()).isEqualTo("desc");
        assertThat(j.getPostedDate()).isNotNull();
    }

    @Test
    void descriptionFallsBackToStrippedHtml() {
        String list =
                "{\"jobs\":[{\"id\":\"h2\",\"title\":\"Planner\",\"location\":\"SF, CA\","
                        + "\"descriptionHtml\":\"<p>Hi <b>there</b></p>\"}]}";
        AshbyScraper s = new AshbyScraper(WebClientStubs.json(u -> list), "hadrian");
        assertThat(s.scrape("hadrian").get(0).getDescription())
                .contains("Hi")
                .doesNotContain("<p>");
    }

    @Test
    void emptyAndError() {
        assertThat(new AshbyScraper(WebClientStubs.json(u -> "{}"), "h").scrape("h")).isEmpty();
        assertThat(new AshbyScraper(WebClientStubs.erroring(), "h").scrape("h")).isEmpty();
    }

    @Test
    void unparseableDateYieldsNullPostedDate() {
        String list =
                "{\"jobs\":[{\"id\":\"h3\",\"title\":\"Buyer\",\"location\":\"SF, CA\","
                        + "\"publishedAt\":\"not-a-date\"}]}";
        AshbyScraper s = new AshbyScraper(WebClientStubs.json(u -> list), "hadrian");
        assertThat(s.scrape("hadrian").get(0).getPostedDate()).isNull();
    }
}
