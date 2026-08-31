package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EightfoldScraperTest {

    // first page has a position; later pages (start!=0) are empty so pagination terminates
    private static String body(String url) {
        if (url.contains("start=0&") || url.endsWith("start=0")) {
            return "{\"data\":{\"positions\":[{\"id\":\"p1\",\"name\":\"Buyer\","
                    + "\"locations\":[\"Fremont, CA\"]}]}}";
        }
        return "{\"data\":{\"positions\":[]}}";
    }

    private EightfoldScraper scraper() {
        return new EightfoldScraper(
                WebClientStubs.json(EightfoldScraperTest::body), new ObjectMapper());
    }

    @Test
    void platformAndCompanies() {
        EightfoldScraper s = scraper();
        assertThat(s.platform()).isEqualTo("eightfold");
        assertThat(s.companies()).containsExactly("lamresearch", "qualcomm");
    }

    @Test
    void scrapeParsesPositions() {
        List<JobPosting> jobs = scraper().scrape("lamresearch");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getCompany()).isEqualTo("lamresearch");
        assertThat(j.getExternalId()).isEqualTo("p1");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).isEqualTo("Fremont, CA");
        assertThat(j.getUrl()).contains("careers.lamresearch.com").contains("pid=p1");
    }

    @Test
    void unknownCompanyEmpty() {
        assertThat(scraper().scrape("nope")).isEmpty();
    }

    @Test
    void queryFailureIsCaughtPerQuery() {
        EightfoldScraper s = new EightfoldScraper(WebClientStubs.erroring(), new ObjectMapper());
        assertThat(s.scrape("lamresearch")).isEmpty();
    }

    @Test
    void nullResponseBreaksPagination() {
        EightfoldScraper s = new EightfoldScraper(WebClientStubs.noBody(), new ObjectMapper());
        assertThat(s.scrape("lamresearch")).isEmpty();
    }
}
