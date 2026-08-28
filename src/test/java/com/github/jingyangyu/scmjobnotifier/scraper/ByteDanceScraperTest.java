package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ByteDanceScraperTest {

    private static final String OK =
            "{\"code\":0,\"data\":{\"count\":1,\"job_post_list\":[{\"id\":\"b1\","
                    + "\"title\":\"Buyer\",\"city_list\":[{\"en_name\":\"San Jose\"}],"
                    + "\"description\":\"<p>d</p>\"}]}}";

    private ByteDanceScraper scraper(String body) {
        return new ByteDanceScraper(WebClientStubs.json(u -> body), new ObjectMapper());
    }

    @Test
    void platformAndCompanies() {
        ByteDanceScraper s = scraper(OK);
        assertThat(s.platform()).isEqualTo("bytedance");
        assertThat(s.companies()).contains("bytedance", "tiktok");
    }

    @Test
    void scrapeParsesJobPosts() {
        List<JobPosting> jobs = scraper(OK).scrape("bytedance");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("b1");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).contains("San Jose");
        assertThat(j.getUrl()).contains("b1");
    }

    @Test
    void nonZeroCodeYieldsEmpty() {
        assertThat(scraper("{\"code\":1,\"data\":{}}").scrape("bytedance")).isEmpty();
    }

    @Test
    void unknownCompanyAndError() {
        assertThat(scraper(OK).scrape("nope")).isEmpty();
        assertThat(
                        new ByteDanceScraper(WebClientStubs.erroring(), new ObjectMapper())
                                .scrape("bytedance"))
                .isEmpty();
    }
}
