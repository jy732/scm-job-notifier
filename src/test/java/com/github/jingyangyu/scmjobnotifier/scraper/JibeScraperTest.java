package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JibeScraperTest {

    private static final String PAGE =
            "{\"jobs\":[{\"data\":{\"slug\":\"100\",\"title\":\"Buyer\","
                    + "\"full_location\":\"Palo Alto, California\",\"apply_url\":\"https://x/100\","
                    + "\"posted_date\":\"2026-08-20T19:08:00+0000\",\"description\":\"d\"}}]}";

    private JibeScraper scraper(String body) {
        return new JibeScraper(WebClientStubs.json(u -> body), new ObjectMapper());
    }

    @Test
    void platformAndCompanies() {
        JibeScraper s = scraper(PAGE);
        assertThat(s.platform()).isEqualTo("jibe");
        assertThat(s.companies()).containsExactly("amd", "rivian");
    }

    @Test
    void scrapeParsesJobs() {
        List<JobPosting> jobs = scraper(PAGE).scrape("amd");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getCompany()).isEqualTo("amd");
        assertThat(j.getExternalId()).isEqualTo("100");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).isEqualTo("Palo Alto, California");
        assertThat(j.getUrl()).isEqualTo("https://x/100");
        assertThat(j.getPostedDate()).isNotNull();
    }

    @Test
    void unknownCompanyEmpty() {
        assertThat(scraper(PAGE).scrape("nope")).isEmpty();
    }

    @Test
    void emptyPageStopsPagination() {
        assertThat(scraper("{\"jobs\":[]}").scrape("amd")).isEmpty();
    }

    @Test
    void fallsBackToCareersHomeUrlAndNullDate() {
        // no apply_url and an unparseable posted_date -> url fallback + null postedDate
        String page =
                "{\"jobs\":[{\"data\":{\"slug\":\"77\",\"title\":\"Buyer\","
                        + "\"full_location\":\"San Jose, California\",\"posted_date\":\"bad\"}}]}";
        List<JobPosting> jobs = scraper(page).scrape("amd");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getUrl()).contains("careers-home/jobs/77");
        assertThat(jobs.get(0).getPostedDate()).isNull();
    }

    @Test
    void emptyOnError() {
        assertThat(new JibeScraper(WebClientStubs.erroring(), new ObjectMapper()).scrape("amd"))
                .isEmpty();
    }

    @Test
    void nullResponseReturnsZero() {
        JibeScraper s = new JibeScraper(WebClientStubs.noBody(), new ObjectMapper());
        assertThat(s.scrape("amd")).isEmpty();
    }

    @Test
    void skipsJobWithoutIdAndBlankPostedDate() {
        String page =
                "{\"jobs\":[{\"data\":{\"title\":\"No Id\",\"full_location\":\"San Jose, CA\"}},"
                        + "{\"data\":{\"slug\":\"88\",\"title\":\"Buyer\","
                        + "\"full_location\":\"San Jose, CA\",\"apply_url\":\"https://x/88\","
                        + "\"posted_date\":\"\"}}]}";
        List<JobPosting> jobs = scraper(page).scrape("amd");
        assertThat(jobs).hasSize(1); // id-less job skipped
        assertThat(jobs.get(0).getPostedDate()).isNull(); // blank date -> null
    }

    @Test
    void fullPageWithNoNewIdsStopsPagination() {
        // a full page (PAGE_SIZE=100) whose ids repeat every page -> "no new ids" break
        StringBuilder sb = new StringBuilder("{\"jobs\":[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"data\":{\"slug\":\"j")
                    .append(i)
                    .append(
                            "\",\"title\":\"Buyer\",\"full_location\":\"San Jose, CA\","
                                    + "\"apply_url\":\"https://x/")
                    .append(i)
                    .append("\"}}");
        }
        sb.append("]}");
        assertThat(scraper(sb.toString()).scrape("amd")).hasSize(100);
    }
}
