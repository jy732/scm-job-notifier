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

    @Test
    void tiktokPortalSetsWebsitePathHeader() {
        // tiktok portal has a non-null websitePath -> the website-path header branch runs
        assertThat(scraper(OK).scrape("tiktok")).hasSize(1);
    }

    @Test
    void nullResponseBreaks() {
        ByteDanceScraper s = new ByteDanceScraper(WebClientStubs.noBody(), new ObjectMapper());
        assertThat(s.scrape("bytedance")).isEmpty();
    }

    @Test
    void emptyJobListBreaks() {
        assertThat(
                        scraper("{\"code\":0,\"data\":{\"count\":0,\"job_post_list\":[]}}")
                                .scrape("bytedance"))
                .isEmpty();
    }

    @Test
    void singleCityInfoLocation() {
        String body =
                "{\"code\":0,\"data\":{\"count\":1,\"job_post_list\":[{\"id\":\"t1\","
                        + "\"title\":\"Buyer\",\"city_info\":{\"en_name\":\"Los Angeles\"},"
                        + "\"description\":\"<p>d</p>\"}]}}";
        assertThat(scraper(body).scrape("tiktok").get(0).getLocation()).contains("Los Angeles");
    }
}
