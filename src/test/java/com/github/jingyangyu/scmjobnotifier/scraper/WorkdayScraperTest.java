package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.WorkdayProperties;
import com.github.jingyangyu.scmjobnotifier.config.WorkdayProperties.WorkdayCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkdayScraperTest {

    private static final String BODY =
            "{\"total\":1,\"facets\":[],\"jobPostings\":[{\"title\":\"Buyer\","
                    + "\"externalPath\":\"/job/San-Jose/Buyer_R1\",\"locationsText\":\"San Jose, CA\","
                    + "\"postedOn\":\"Posted Today\"}]}";

    private static WorkdayProperties props() {
        WorkdayCompany c = new WorkdayCompany();
        c.setName("iherb");
        c.setSubdomain("iherb");
        c.setInstance(5);
        c.setSite("Careers");
        WorkdayProperties p = new WorkdayProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    @Test
    void platformAndCompanies() {
        WorkdayScraper s = new WorkdayScraper(WebClientStubs.json(u -> BODY), props());
        assertThat(s.platform()).isEqualTo("workday");
        assertThat(s.companies()).containsExactly("iherb");
    }

    @Test
    void scrapeParsesJobPostings() {
        WorkdayScraper s = new WorkdayScraper(WebClientStubs.json(u -> BODY), props());
        List<JobPosting> jobs = s.scrape("iherb");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("/job/San-Jose/Buyer_R1");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).isEqualTo("San Jose, CA");
        assertThat(j.getUrl()).contains("Buyer_R1");
    }

    @Test
    void unknownCompanyEmpty() {
        WorkdayScraper s = new WorkdayScraper(WebClientStubs.json(u -> BODY), props());
        assertThat(s.scrape("nope")).isEmpty();
    }
}
