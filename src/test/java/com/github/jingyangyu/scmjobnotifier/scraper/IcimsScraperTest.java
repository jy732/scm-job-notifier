package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.IcimsProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class IcimsScraperTest {

    private static final String HTML =
            "<span>Location</span><span>US-CA-San Jose</span>"
                    + "<a href=\"https://careers-nikkiso.icims.com/jobs/55/buyer/job\">"
                    + "<h3>Buyer</h3></a>";

    private static String body(String url) {
        return url.contains("pr=0") ? HTML : ""; // job cards on page 0 only
    }

    private static IcimsProperties props() {
        IcimsProperties.IcimsCompany c = new IcimsProperties.IcimsCompany();
        c.setName("nikkiso");
        c.setSubdomain("careers-nikkiso");
        IcimsProperties p = new IcimsProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    @Test
    void scrapeParsesAnchorsWithLocation() {
        IcimsScraper s =
                new IcimsScraper(WebClientStubs.text(IcimsScraperTest::body, "text/html"), props());
        List<JobPosting> jobs = s.scrape("nikkiso");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("55");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).contains("San Jose");
        assertThat(j.getUrl()).contains("/jobs/55/buyer/job");
    }
}
