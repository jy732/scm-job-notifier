package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeverScraperTest {

    private static final String LIST =
            "[{\"id\":\"a1\",\"text\":\"Buyer\",\"categories\":{\"location\":\"San Jose, CA\"},"
                    + "\"hostedUrl\":\"https://x/a1\",\"descriptionPlain\":\"desc\","
                    + "\"createdAt\":1690000000000}]";

    @Test
    void platformAndCompanies() {
        LeverScraper s = new LeverScraper(WebClientStubs.json(u -> LIST), "acme, beta");
        assertThat(s.platform()).isEqualTo("lever");
        assertThat(s.companies()).containsExactly("acme", "beta");
    }

    @Test
    void scrapeParsesPostings() {
        LeverScraper s = new LeverScraper(WebClientStubs.json(u -> LIST), "acme");
        List<JobPosting> jobs = s.scrape("acme");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("a1");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).isEqualTo("San Jose, CA");
        assertThat(j.getUrl()).isEqualTo("https://x/a1");
        assertThat(j.getDescription()).isEqualTo("desc");
        assertThat(j.getPostedDate()).isNotNull();
    }

    @Test
    void scrapeEmptyOnError() {
        LeverScraper s = new LeverScraper(WebClientStubs.erroring(), "acme");
        assertThat(s.scrape("acme")).isEmpty();
    }

    @Test
    void nullBodyReturnsEmpty() {
        LeverScraper s = new LeverScraper(WebClientStubs.json(u -> "null"), "acme");
        assertThat(s.scrape("acme")).isEmpty();
    }
}
