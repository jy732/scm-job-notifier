package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class BambooHrScraperTest {

    private static final String LIST =
            "{\"result\":[{\"id\":\"9\",\"jobOpeningName\":\"Buyer/Planner\","
                    + "\"location\":{\"city\":\"Fremont\",\"state\":\"California\"}}]}";

    @Test
    void platformAndCompanies() {
        BambooHrScraper s = new BambooHrScraper(WebClientStubs.json(u -> LIST), "pivotalsys");
        assertThat(s.platform()).isEqualTo("bamboohr");
        assertThat(s.companies()).containsExactly("pivotalsys");
    }

    @Test
    void scrapeParsesResult() {
        BambooHrScraper s = new BambooHrScraper(WebClientStubs.json(u -> LIST), "pivotalsys");
        List<JobPosting> jobs = s.scrape("pivotalsys");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("9");
        assertThat(j.getTitle()).isEqualTo("Buyer/Planner");
        assertThat(j.getLocation()).isEqualTo("Fremont, California");
        assertThat(j.getUrl()).isEqualTo("https://pivotalsys.bamboohr.com/careers/9");
    }

    @Test
    void emptyWhenNoResultKey() {
        BambooHrScraper s = new BambooHrScraper(WebClientStubs.json(u -> "{}"), "pivotalsys");
        assertThat(s.scrape("pivotalsys")).isEmpty();
    }

    @Test
    void scrapeEmptyOnError() {
        BambooHrScraper s = new BambooHrScraper(WebClientStubs.erroring(), "pivotalsys");
        assertThat(s.scrape("pivotalsys")).isEmpty();
    }
}
