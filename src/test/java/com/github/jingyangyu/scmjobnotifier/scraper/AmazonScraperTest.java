package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class AmazonScraperTest {

    private static final String BODY =
            "{\"hits\":1,\"jobs\":[{\"id_icims\":\"a1\",\"title\":\"Buyer\","
                    + "\"job_path\":\"/en/jobs/a1\",\"normalized_location\":\"San Jose, CA\","
                    + "\"posted_date\":\"August 1, 2026\"}]}";

    @Test
    void platform() {
        assertThat(new AmazonScraper(WebClientStubs.json(u -> BODY)).platform())
                .isEqualTo("amazon");
    }

    @Test
    void scrapeDedupesAcrossQueries() {
        AmazonScraper s = new AmazonScraper(WebClientStubs.json(u -> BODY));
        List<JobPosting> jobs = s.scrape("amazon");
        assertThat(jobs).hasSize(1); // same id across all SCM queries -> deduped
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("a1");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).isEqualTo("San Jose, CA");
        assertThat(j.getUrl()).isEqualTo("https://www.amazon.jobs/en/jobs/a1");
    }

    @Test
    void scrapeEmptyWhenNoJobs() {
        AmazonScraper s = new AmazonScraper(WebClientStubs.json(u -> "{\"hits\":0,\"jobs\":[]}"));
        assertThat(s.scrape("amazon")).isEmpty();
    }

    @Test
    void queryFailureIsCaughtPerQuery() {
        AmazonScraper s = new AmazonScraper(WebClientStubs.erroring());
        assertThat(s.scrape("amazon")).isEmpty(); // each query throws -> caught
    }

    @Test
    void nullResponseBreaksPagination() {
        AmazonScraper s = new AmazonScraper(WebClientStubs.json(u -> "null"));
        assertThat(s.scrape("amazon")).isEmpty();
    }

    @Test
    void missingAndBadPostedDatesYieldNull() {
        String body =
                "{\"hits\":2,\"jobs\":[{\"id_icims\":\"b1\",\"title\":\"Buyer\","
                        + "\"job_path\":\"/j/b1\",\"normalized_location\":\"San Jose, CA\","
                        + "\"posted_date\":\"\"},"
                        + "{\"id_icims\":\"b2\",\"title\":\"Planner\",\"job_path\":\"/j/b2\","
                        + "\"normalized_location\":\"Irvine, CA\","
                        + "\"posted_date\":\"not a date\"}]}";
        AmazonScraper s = new AmazonScraper(WebClientStubs.json(u -> body));
        List<JobPosting> jobs = s.scrape("amazon");
        assertThat(jobs).hasSize(2);
        assertThat(jobs).allSatisfy(j -> assertThat(j.getPostedDate()).isNull());
    }
}
