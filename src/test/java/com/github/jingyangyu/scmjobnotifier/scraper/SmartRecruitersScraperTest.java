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
                    + "\"jobAd\":{\"sections\":{\"jobDescription\":{\"text\":"
                    + "\"Manage the supply chain and procurement operations.\"}}},"
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
        assertThat(j.getDescription()).contains("supply chain");
    }

    @Test
    void buildsCanonicalUrlWhenRelatedLinksMissing() {
        // The postings API usually omits relatedLinks — fall back to jobs.smartrecruiters.com/{co}/{id}.
        String body =
                "{\"totalFound\":1,\"content\":[{\"id\":\"744000\",\"name\":\"Buyer\","
                        + "\"location\":{\"city\":\"San Jose\",\"region\":\"CA\",\"country\":\"us\"}}]}";
        SmartRecruitersScraper s = new SmartRecruitersScraper(WebClientStubs.json(u -> body), "PactGroup");
        JobPosting j = s.scrape("PactGroup").get(0);
        assertThat(j.getUrl()).isEqualTo("https://jobs.smartrecruiters.com/PactGroup/744000");
    }

    @Test
    void scrapeEmptyOnError() {
        SmartRecruitersScraper s = new SmartRecruitersScraper(WebClientStubs.erroring(), "arista");
        assertThat(s.scrape("arista")).isEmpty();
    }

    @Test
    void nullResponseBreaksPagination() {
        SmartRecruitersScraper s =
                new SmartRecruitersScraper(WebClientStubs.json(u -> "null"), "arista");
        assertThat(s.scrape("arista")).isEmpty();
    }

    @Test
    void emptyContentBreaksPagination() {
        SmartRecruitersScraper s =
                new SmartRecruitersScraper(
                        WebClientStubs.json(u -> "{\"totalFound\":99,\"content\":[]}"), "arista");
        assertThat(s.scrape("arista")).isEmpty();
    }

    @Test
    void paginatesAcrossMultiplePages() {
        // totalFound (150) > PAGE_SIZE (100) so page 0 doesn't break -> loop iterates to page 1
        String body =
                "{\"totalFound\":150,\"content\":[{\"id\":\"m1\",\"name\":\"Buyer\","
                        + "\"location\":{\"city\":\"San Jose\",\"region\":\"CA\"},"
                        + "\"relatedLinks\":{\"careerPage\":\"https://x/m1\"}}]}";
        SmartRecruitersScraper s =
                new SmartRecruitersScraper(WebClientStubs.json(u -> body), "arista");
        assertThat(s.scrape("arista")).isNotEmpty();
    }

    @Test
    void multipleSectionsJoinedAndDatesParsed() {
        String body =
                "{\"totalFound\":2,\"content\":[{\"id\":\"sA\",\"name\":\"Buyer\","
                        + "\"jobAd\":{\"sections\":{"
                        + "\"jobDescription\":{\"text\":\"Manage supply chain.\"},"
                        + "\"qualifications\":{\"text\":\"3 years procurement.\"}}},"
                        + "\"releasedDate\":\"2026-08-01T00:00:00Z\"},"
                        + "{\"id\":\"sB\",\"name\":\"Planner\",\"releasedDate\":\"bad-date\"}]}";
        SmartRecruitersScraper s =
                new SmartRecruitersScraper(WebClientStubs.json(u -> body), "arista");
        List<JobPosting> jobs = s.scrape("arista");
        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).getDescription()).contains("supply chain").contains("procurement");
        assertThat(jobs.get(0).getPostedDate()).isNotNull();
        assertThat(jobs.get(1).getPostedDate()).isNull();
    }
}
