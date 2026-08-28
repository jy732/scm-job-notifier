package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class GreenhouseScraperTest {

    private static final String LIST =
            "{\"jobs\":[{\"id\":123,\"title\":\"Buyer\",\"location\":{\"name\":\"San Jose, CA\"},"
                    + "\"absolute_url\":\"https://x/123\",\"updated_at\":\"2026-08-01T00:00:00Z\"}]}";
    private static final String DETAIL = "{\"content\":\"<p>Hello &amp; world</p>\"}";

    private GreenhouseScraper scraper(String list, String detail) {
        return new GreenhouseScraper(
                WebClientStubs.json(url -> url.contains("/jobs/") ? detail : list), "acme");
    }

    @Test
    void platformAndCompanies() {
        GreenhouseScraper s = scraper(LIST, DETAIL);
        assertThat(s.platform()).isEqualTo("greenhouse");
        assertThat(s.companies()).containsExactly("acme");
    }

    @Test
    void scrapeParsesJobs() {
        List<JobPosting> jobs = scraper(LIST, DETAIL).scrape("acme");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getCompany()).isEqualTo("acme");
        assertThat(j.getExternalId()).isEqualTo("123");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).isEqualTo("San Jose, CA");
        assertThat(j.getUrl()).isEqualTo("https://x/123");
        assertThat(j.getPostedDate()).isNotNull();
    }

    @Test
    void scrapeEmptyWhenNoJobsKey() {
        assertThat(scraper("{}", DETAIL).scrape("acme")).isEmpty();
    }

    @Test
    void scrapeEmptyOnNetworkError() {
        GreenhouseScraper s = new GreenhouseScraper(WebClientStubs.erroring(), "acme");
        assertThat(s.scrape("acme")).isEmpty();
    }

    @Test
    void fetchDescriptionsSetsStrippedHtml() {
        GreenhouseScraper s = scraper(LIST, DETAIL);
        List<JobPosting> jobs = s.scrape("acme");
        s.fetchDescriptions(jobs);
        assertThat(jobs.get(0).getDescription()).contains("Hello").doesNotContain("<p>");
    }

    @Test
    void fetchDescriptionsToleratesDetailError() {
        // list parses fine, but the per-job detail returns undecodable body -> catch, no throw
        GreenhouseScraper s = scraper(LIST, "not-json{");
        List<JobPosting> jobs = s.scrape("acme");
        s.fetchDescriptions(jobs);
        assertThat(jobs.get(0).getDescription()).isEqualTo("");
    }

    @Test
    void badUpdatedAtYieldsNullPostedDate() {
        String list =
                "{\"jobs\":[{\"id\":9,\"title\":\"Buyer\",\"location\":{\"name\":\"SF, CA\"},"
                        + "\"absolute_url\":\"https://x/9\",\"updated_at\":\"nope\"}]}";
        assertThat(scraper(list, DETAIL).scrape("acme").get(0).getPostedDate()).isNull();
    }
}
