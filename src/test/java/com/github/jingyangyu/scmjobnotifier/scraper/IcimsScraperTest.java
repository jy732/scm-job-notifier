package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.IcimsProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class IcimsScraperTest {

    private static final String HTML =
            "<span>Location</span><span>US-CA-San Jose</span>"
                    + "<a href=\"https://careers-nikkiso.icims.com/jobs/55/buyer/job\">"
                    + "<h3>Buyer</h3></a>";

    private static String body(String url) {
        return url.contains("pr=0") ? HTML : ""; // job cards on page 0 only
    }

    private static IcimsProperties props() {
        IcimsProperties.IcimsCompany c = new IcimsProperties.IcimsCompany();
        c.setName("nikkiso");
        c.setSubdomain("careers-nikkiso");
        IcimsProperties p = new IcimsProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    @Test
    void scrapeParsesAnchorsWithLocation() {
        IcimsScraper s =
                new IcimsScraper(WebClientStubs.text(IcimsScraperTest::body, "text/html"), props());
        List<JobPosting> jobs = s.scrape("nikkiso");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("55");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).contains("San Jose");
        assertThat(j.getUrl()).contains("/jobs/55/buyer/job");
    }

    @Test
    void fetchDescriptionsExtractsJobContent() {
        String descHtml = "<div class=\"iCIMS_JobContent\">" + "detail ".repeat(60) + "</div>";
        IcimsScraper s = new IcimsScraper(WebClientStubs.text(u -> descHtml, "text/html"), props());
        com.github.jingyangyu.scmjobnotifier.model.JobPosting j =
                com.github.jingyangyu.scmjobnotifier.model.JobPosting.builder()
                        .company("nikkiso")
                        .externalId("55")
                        .title("Buyer")
                        .url("https://careers-nikkiso.icims.com/jobs/55/buyer/job")
                        .detectedAt(java.time.Instant.now())
                        .build();
        s.fetchDescriptions(List.of(j));
        assertThat(j.getDescription()).contains("detail");
    }

    private static JobPosting jobAt(String url) {
        return JobPosting.builder()
                .company("nikkiso")
                .externalId("55")
                .title("Buyer")
                .url(url)
                .detectedAt(java.time.Instant.now())
                .build();
    }

    @Test
    void skipsEmptyTitleAndBlankLocation() {
        String html =
                "<a href=\"https://careers-nikkiso.icims.com/jobs/56/x/job\"><h3>   </h3></a>"
                        + "<a href=\"https://careers-nikkiso.icims.com/jobs/57/buyer/job\">"
                        + "<h3>Buyer</h3></a>";
        IcimsScraper s =
                new IcimsScraper(
                        WebClientStubs.text(u -> u.contains("pr=0") ? html : "", "text/html"),
                        props());
        List<JobPosting> jobs = s.scrape("nikkiso");
        assertThat(jobs).hasSize(1); // whitespace-title anchor skipped
        assertThat(jobs.get(0).getLocation()).isEmpty(); // no location span -> ""
    }

    @Test
    void fetchDescriptionsEmptyWhenNoMarker() {
        IcimsScraper s =
                new IcimsScraper(
                        WebClientStubs.text(u -> "<div>no marker here</div>", "text/html"),
                        props());
        JobPosting j = jobAt("https://careers-nikkiso.icims.com/jobs/55/buyer/job");
        s.fetchDescriptions(List.of(j));
        assertThat(j.getDescription()).isEmpty();
    }

    @Test
    void fetchDescriptionsSurvivesError() {
        IcimsScraper s = new IcimsScraper(WebClientStubs.erroring(), props());
        JobPosting j = jobAt("https://careers-nikkiso.icims.com/jobs/55/buyer/job");
        s.fetchDescriptions(List.of(j)); // fetch throws -> caught
        assertThat(j.getDescription()).isNull();
    }
}
