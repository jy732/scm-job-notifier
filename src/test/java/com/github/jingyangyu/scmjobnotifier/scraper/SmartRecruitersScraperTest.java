package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmartRecruitersScraperTest {

    private static final String BODY =
            "{\"totalFound\":1,\"content\":[{\"id\":\"s1\",\"name\":\"Buyer\","
                    + "\"location\":{\"city\":\"San Jose\",\"region\":\"CA\",\"country\":\"us\"},"
                    + "\"relatedLinks\":{\"careerPage\":\"https://x/s1\"}}]}";

    @Test
    void platformAndCompanies() {
        SmartRecruitersScraper s =
                new SmartRecruitersScraper(WebClientStubs.json(u -> BODY), "arista, other");
        assertThat(s.platform()).isEqualTo("smartrecruiters");
        assertThat(s.companies()).containsExactly("arista", "other");
    }

    @Test
    void scrapeParsesContent() {
        SmartRecruitersScraper s =
                new SmartRecruitersScraper(WebClientStubs.json(u -> BODY), "arista");
        List<JobPosting> jobs = s.scrape("arista");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("s1");
        assertThat(j.getLocation()).contains("San Jose");
        assertThat(j.getUrl()).isEqualTo("https://x/s1");
    }

    @Test
    void scrapeEmptyOnError() {
        SmartRecruitersScraper s = new SmartRecruitersScraper(WebClientStubs.erroring(), "arista");
        assertThat(s.scrape("arista")).isEmpty();
    }
}
