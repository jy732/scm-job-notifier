package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.PaylocityProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaylocityScraperTest {

    private static final String HTML =
            "<html><script>var data = {\"Jobs\":[{\"JobId\":123,\"JobTitle\":\"Buyer\","
                    + "\"City\":\"San Jose\",\"State\":\"CA\","
                    + "\"PublishedDate\":\"2026-08-01T00:00:00\"}]};</script></html>";

    private static PaylocityProperties props() {
        PaylocityProperties.PaylocityCompany c = new PaylocityProperties.PaylocityCompany();
        c.setName("baycitiescontainer");
        c.setCompanyId("ABC");
        c.setSlug("Bay-Cities");
        PaylocityProperties p = new PaylocityProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    @Test
    void scrapeParsesMultipleRecordsWithEscapes() {
        String html =
                "<script>var d = {\"Jobs\":[{\"JobId\":1,\"JobTitle\":\"Buyer \\u0026 Planner\","
                        + "\"City\":\"San Jose\",\"State\":\"CA\","
                        + "\"PublishedDate\":\"2026-08-01T00:00:00\"},"
                        + "{\"JobId\":2,\"JobTitle\":\"Sourcing Specialist\",\"City\":\"Irvine\","
                        + "\"State\":\"CA\",\"PublishedDate\":\"2026-08-02T00:00:00\"}]};</script>";
        PaylocityScraper s =
                new PaylocityScraper(WebClientStubs.text(u -> html, "text/html"), props());
        List<JobPosting> jobs = s.scrape("baycitiescontainer");
        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).getTitle()).contains("&"); // & unescaped
    }

    @Test
    void scrapeParsesJobsArray() {
        PaylocityScraper s =
                new PaylocityScraper(WebClientStubs.text(u -> HTML, "text/html"), props());
        List<JobPosting> jobs = s.scrape("baycitiescontainer");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("123");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).contains("San Jose").contains("CA");
    }
}
