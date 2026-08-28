package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.SuccessFactorsProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuccessFactorsScraperTest {

    private static final String HTML =
            "<li class=\"job-tile job-id-123\" data-url=\"/job/1\">"
                    + "<a class=\"jobTitle-link\" href=\"x\">Buyer</a></li>";

    private static String body(String url) {
        return url.contains("startrow=0") ? HTML : ""; // tiles on first page only
    }

    private static SuccessFactorsProperties props() {
        SuccessFactorsProperties.SuccessFactorsCompany c =
                new SuccessFactorsProperties.SuccessFactorsCompany();
        c.setName("supermicro");
        c.setHost("jobs.supermicro.com");
        SuccessFactorsProperties p = new SuccessFactorsProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    @Test
    void scrapeParsesTilesDeduped() {
        SuccessFactorsScraper s =
                new SuccessFactorsScraper(
                        WebClientStubs.text(SuccessFactorsScraperTest::body, "text/html"), props());
        List<JobPosting> jobs = s.scrape("supermicro");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("123");
        assertThat(j.getTitle()).isEqualTo("Buyer");
    }
}
