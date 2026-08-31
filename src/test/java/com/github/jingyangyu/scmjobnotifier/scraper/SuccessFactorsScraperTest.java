package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.SuccessFactorsProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuccessFactorsScraperTest {

    private static final String HTML =
            "<li class=\"job-tile job-id-123\" data-url=\"/job/1\">"
                    + "<a class=\"jobTitle-link\" href=\"x\">Buyer</a></li>";

    private static String body(String url) {
        return url.contains("startrow=0") ? HTML : ""; // tiles on first page only
    }

    private static SuccessFactorsProperties props() {
        SuccessFactorsProperties.SuccessFactorsCompany c =
                new SuccessFactorsProperties.SuccessFactorsCompany();
        c.setName("supermicro");
        c.setHost("jobs.supermicro.com");
        SuccessFactorsProperties p = new SuccessFactorsProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    @Test
    void scrapeParsesTilesDeduped() {
        SuccessFactorsScraper s =
                new SuccessFactorsScraper(
                        WebClientStubs.text(SuccessFactorsScraperTest::body, "text/html"), props());
        List<JobPosting> jobs = s.scrape("supermicro");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("123");
        assertThat(j.getTitle()).isEqualTo("Buyer");
    }

    @Test
    void fetchDescriptionsExtractsMarkedText() {
        String desc =
                "<div data-careersite-propertyid=\"description\">" + "a".repeat(300) + "</div>";
        SuccessFactorsScraper s =
                new SuccessFactorsScraper(WebClientStubs.text(u -> desc, "text/html"), props());
        JobPosting j =
                JobPosting.builder()
                        .company("supermicro")
                        .externalId("1")
                        .title("Buyer")
                        .url("https://jobs.supermicro.com/job/1")
                        .detectedAt(Instant.now())
                        .build();
        s.fetchDescriptions(List.of(j));
        assertThat(j.getDescription()).isNotEmpty();
    }

    private static JobPosting job1() {
        return JobPosting.builder()
                .company("supermicro")
                .externalId("1")
                .title("Buyer")
                .url("https://jobs.supermicro.com/job/1")
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void tileWithoutTitleIsSkipped() {
        String html =
                "<li class=\"job-tile job-id-124\" data-url=\"/job/2\"></li>"
                        + "<li class=\"job-tile job-id-125\" data-url=\"/job/3\">"
                        + "<a class=\"jobTitle-link\" href=\"x\">Planner</a></li>";
        SuccessFactorsScraper s =
                new SuccessFactorsScraper(
                        WebClientStubs.text(u -> u.contains("startrow=0") ? html : "", "text/html"),
                        props());
        assertThat(s.scrape("supermicro")).hasSize(1); // title-less tile skipped
    }

    @Test
    void fetchDescriptionsEmptyWhenNoMarker() {
        SuccessFactorsScraper s =
                new SuccessFactorsScraper(
                        WebClientStubs.text(u -> "<div>nothing</div>", "text/html"), props());
        JobPosting j = job1();
        s.fetchDescriptions(List.of(j));
        assertThat(j.getDescription()).isEmpty();
    }

    @Test
    void fetchDescriptionsSurvivesError() {
        SuccessFactorsScraper s = new SuccessFactorsScraper(WebClientStubs.erroring(), props());
        JobPosting j = job1();
        s.fetchDescriptions(List.of(j)); // fetch throws -> caught
        assertThat(j.getDescription()).isNull();
    }
}
