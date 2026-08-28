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

    // A multi-location job whose locationsText lacks CA, but appears under the CA location facet,
    // gets re-tagged " · California" via the facet re-fetch.
    private static final String FACET_BODY =
            "{\"total\":1,\"facets\":[{\"facetParameter\":\"locations\",\"values\":"
                    + "[{\"descriptor\":\"California\",\"id\":\"ca-id\"}]}],"
                    + "\"jobPostings\":[{\"title\":\"Buyer\","
                    + "\"externalPath\":\"/job/Multi/Buyer_R1\","
                    + "\"locationsText\":\"Multiple Locations\",\"postedOn\":\"Posted Today\"}]}";

    @Test
    void locationFallsBackToExternalPath() {
        String body =
                "{\"total\":1,\"facets\":[],\"jobPostings\":[{\"title\":\"Buyer\","
                        + "\"externalPath\":\"/job/San-Jose-CA/Buyer_R1\",\"locationsText\":\"\","
                        + "\"postedOn\":\"Posted Today\"}]}";
        WorkdayScraper s = new WorkdayScraper(WebClientStubs.json(u -> body), props());
        List<JobPosting> jobs = s.scrape("iherb");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getLocation()).isEqualTo("San Jose CA");
    }

    @Test
    void tagsViaLocationMetroAreaFacet() {
        String body =
                "{\"total\":1,\"facets\":[{\"facetParameter\":\"locationMetroArea\",\"values\":"
                        + "[{\"descriptor\":\"California\",\"id\":\"ca-metro\"}]}],"
                        + "\"jobPostings\":[{\"title\":\"Buyer\","
                        + "\"externalPath\":\"/job/Multi/Buyer_R2\","
                        + "\"locationsText\":\"Multiple\",\"postedOn\":\"Posted Today\"}]}";
        WorkdayScraper s = new WorkdayScraper(WebClientStubs.json(u -> body), props());
        List<JobPosting> jobs = s.scrape("iherb");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getLocation()).contains("California");
    }

    @Test
    void tagsMultiLocationCaViaFacet() {
        WorkdayScraper s = new WorkdayScraper(WebClientStubs.json(u -> FACET_BODY), props());
        List<JobPosting> jobs = s.scrape("iherb");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getLocation()).contains("California");
    }

    @Test
    void parsesVariousPostedOnFormsAndDropsStale() {
        String body =
                "{\"total\":5,\"facets\":[],\"jobPostings\":["
                        + "{\"title\":\"A\",\"externalPath\":\"/job/X/A_1\","
                        + "\"locationsText\":\"San Jose, CA\",\"postedOn\":\"Posted Today\"},"
                        + "{\"title\":\"B\",\"externalPath\":\"/job/X/B_2\","
                        + "\"locationsText\":\"San Jose, CA\",\"postedOn\":\"Posted Yesterday\"},"
                        + "{\"title\":\"C\",\"externalPath\":\"/job/X/C_3\","
                        + "\"locationsText\":\"San Jose, CA\",\"postedOn\":\"Posted 5 Days Ago\"},"
                        + "{\"title\":\"D\",\"externalPath\":\"/job/X/D_4\","
                        + "\"locationsText\":\"San Jose, CA\",\"postedOn\":\"\"},"
                        + "{\"title\":\"E\",\"externalPath\":\"/job/X/E_5\","
                        + "\"locationsText\":\"San Jose, CA\",\"postedOn\":\"Posted 30+ Days Ago\"}"
                        + "]}";
        WorkdayScraper s = new WorkdayScraper(WebClientStubs.json(u -> body), props());
        List<JobPosting> jobs = s.scrape("iherb");
        // 30+ (stale) is dropped; the other four are within the window.
        assertThat(jobs).hasSize(4);
    }

    @Test
    void staleOnlyPageStopsPaginationEarly() {
        String body =
                "{\"total\":50,\"facets\":[],\"jobPostings\":[{\"title\":\"Old\","
                        + "\"externalPath\":\"/job/X/Old_1\",\"locationsText\":\"San Jose, CA\","
                        + "\"postedOn\":\"Posted 30+ Days Ago\"}]}";
        WorkdayScraper s = new WorkdayScraper(WebClientStubs.json(u -> body), props());
        assertThat(s.scrape("iherb")).isEmpty();
    }

    @Test
    void nonJobExternalPathYieldsBlankLocation() {
        String body =
                "{\"total\":1,\"facets\":[],\"jobPostings\":[{\"title\":\"Buyer\","
                        + "\"externalPath\":\"/other/thing\",\"locationsText\":\"\","
                        + "\"postedOn\":\"Posted Today\"}]}";
        WorkdayScraper s = new WorkdayScraper(WebClientStubs.json(u -> body), props());
        assertThat(s.scrape("iherb").get(0).getLocation()).isEmpty();
    }

    private static JobPosting detailJob(String company, String path) {
        return JobPosting.builder().company(company).externalId(path).title("Buyer").build();
    }

    @Test
    void fetchDescriptionsPopulatesStrippedHtml() {
        String detail = "{\"jobPostingInfo\":{\"jobDescription\":\"<p>Hello <b>SCM</b></p>\"}}";
        WorkdayScraper s = new WorkdayScraper(WebClientStubs.json(u -> detail), props());
        JobPosting j = detailJob("iherb", "/job/X/Buyer_R1");
        s.fetchDescriptions(List.of(j));
        assertThat(j.getDescription()).contains("Hello").contains("SCM").doesNotContain("<p>");
    }

    @Test
    void fetchDescriptionsEmptyWhenNoJobInfoOrNonStringDesc() {
        JobPosting a = detailJob("iherb", "/job/X/A_1");
        new WorkdayScraper(WebClientStubs.json(u -> "{}"), props())
                .fetchDescriptions(List.of(a)); // jobPostingInfo null -> ""
        assertThat(a.getDescription()).isEmpty();

        JobPosting b = detailJob("iherb", "/job/X/B_2");
        new WorkdayScraper(
                        WebClientStubs.json(u -> "{\"jobPostingInfo\":{\"jobDescription\":5}}"),
                        props())
                .fetchDescriptions(List.of(b)); // non-string -> ""
        assertThat(b.getDescription()).isEmpty();

        JobPosting c = detailJob("iherb", "/job/X/C_3");
        new WorkdayScraper(WebClientStubs.json(u -> "null"), props())
                .fetchDescriptions(List.of(c)); // detail null -> ""
        assertThat(c.getDescription()).isEmpty();
    }

    @Test
    void fetchDescriptionsSkipsUnknownCompanyAndSurvivesError() {
        JobPosting unknown = detailJob("ghost", "/job/X/G_1");
        new WorkdayScraper(WebClientStubs.json(u -> "{}"), props())
                .fetchDescriptions(List.of(unknown));
        assertThat(unknown.getDescription()).isNull(); // skipped, never set

        JobPosting err = detailJob("iherb", "/job/X/E_1");
        new WorkdayScraper(WebClientStubs.erroring(), props()).fetchDescriptions(List.of(err));
        assertThat(err.getDescription()).isEmpty(); // exception -> ""
    }
}
