package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.UltiProProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class UltiProScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static UltiProProperties props() {
        UltiProProperties.UltiProCompany c = new UltiProProperties.UltiProCompany();
        c.setName("groceryoutlet");
        c.setTenant("GRO1006");
        c.setBoardId("board-guid");
        c.setHost("recruiting");
        UltiProProperties p = new UltiProProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    private static UltiProScraper scraper(Function<String, String> urlToBody) {
        return new UltiProScraper(WebClientStubs.json(urlToBody), MAPPER, props());
    }

    private static String opportunity(String id, String title, String city, String state) {
        return "{\"Id\":\""
                + id
                + "\",\"Title\":\""
                + title
                + "\",\"PostedDate\":\"2026-09-18T23:44:43.603Z\","
                + "\"BriefDescription\":\"desc\",\"Locations\":[{\"LocalizedDescription\":\"HQ\","
                + "\"Address\":{\"City\":\""
                + city
                + "\",\"State\":{\"Code\":\""
                + state
                + "\"}}}]}";
    }

    private static String body(String... opportunities) {
        return "{\"opportunities\":[" + String.join(",", opportunities) + "],\"totalCount\":1}";
    }

    @Test
    void scrapeParsesOpportunities() {
        List<JobPosting> jobs =
                scraper(u -> body(opportunity("abc", "Assistant Buyer", "Emeryville", "CA")))
                        .scrape("groceryoutlet");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("abc");
        assertThat(j.getTitle()).isEqualTo("Assistant Buyer");
        assertThat(j.getLocation()).isEqualTo("Emeryville, CA");
        assertThat(j.getDescription()).isEqualTo("desc");
        assertThat(j.getPostedDate()).isNotNull();
        assertThat(j.getUrl())
                .isEqualTo(
                        "https://recruiting.ultipro.com/GRO1006/JobBoard/board-guid/"
                                + "OpportunityDetail?opportunityId=abc");
    }

    /** The same opportunity is returned by several SCM queries — it must be kept once. */
    @Test
    void duplicateOpportunitiesAcrossQueriesAreDeduplicated() {
        List<JobPosting> jobs =
                scraper(u -> body(opportunity("same", "Buyer", "Irvine", "CA")))
                        .scrape("groceryoutlet");
        assertThat(jobs).hasSize(1);
    }

    @Test
    void multiLocationPostingJoinsEverySite() {
        String multi =
                "{\"Id\":\"m\",\"Title\":\"Planner\",\"Locations\":["
                        + "{\"Address\":{\"City\":\"Fontana\",\"State\":{\"Code\":\"CA\"}}},"
                        + "{\"Address\":{\"City\":\"Reno\",\"State\":{\"Code\":\"NV\"}}}]}";
        List<JobPosting> jobs = scraper(u -> body(multi)).scrape("groceryoutlet");
        assertThat(jobs.get(0).getLocation()).isEqualTo("Fontana, CA; Reno, NV");
    }

    @Test
    void locationFallsBackToLocalizedDescriptionAndHandlesPartialAddresses() {
        String odd =
                "{\"Id\":\"o\",\"Title\":\"Buyer\",\"Locations\":["
                        + "{\"LocalizedDescription\":\"Remote - CA\",\"Address\":{}},"
                        + "{\"Address\":{\"City\":\"Brea\",\"State\":{\"Code\":\"\"}}},"
                        + "{\"Address\":{\"City\":\"\",\"State\":{\"Code\":\"OR\"}}},"
                        + "{\"LocalizedDescription\":\"\",\"Address\":{}}]}";
        List<JobPosting> jobs = scraper(u -> body(odd)).scrape("groceryoutlet");
        assertThat(jobs.get(0).getLocation()).isEqualTo("Remote - CA; Brea; OR");
    }

    @Test
    void recordsMissingIdOrTitleAreSkipped() {
        String bad =
                "{\"Id\":\"\",\"Title\":\"Buyer\",\"Locations\":[]},"
                        + "{\"Id\":\"x\",\"Title\":\"   \",\"Locations\":[]}";
        assertThat(scraper(u -> body(bad)).scrape("groceryoutlet")).isEmpty();
    }

    @Test
    void unparseableDateLeavesPostedDateNull() {
        String odd = "{\"Id\":\"d\",\"Title\":\"Buyer\",\"PostedDate\":\"not-a-date\"}";
        assertThat(scraper(u -> body(odd)).scrape("groceryoutlet").get(0).getPostedDate()).isNull();
    }

    @Test
    void missingDateLeavesPostedDateNull() {
        String odd = "{\"Id\":\"d2\",\"Title\":\"Buyer\"}";
        assertThat(scraper(u -> body(odd)).scrape("groceryoutlet").get(0).getPostedDate()).isNull();
    }

    @Test
    void emptyAndMalformedResponsesYieldNoJobs() {
        assertThat(scraper(u -> "{\"opportunities\":[]}").scrape("groceryoutlet")).isEmpty();
        assertThat(scraper(u -> "not json at all").scrape("groceryoutlet")).isEmpty();
        assertThat(scraper(u -> "").scrape("groceryoutlet")).isEmpty();
        assertThat(scraper(u -> "{\"other\":1}").scrape("groceryoutlet")).isEmpty();
    }

    @Test
    void blankBodyFromServerYieldsNoJobs() {
        UltiProScraper s = new UltiProScraper(WebClientStubs.noBody(), MAPPER, props());
        assertThat(s.scrape("groceryoutlet")).isEmpty();
    }

    /** A full page implies another page; the stub keeps returning it until the page cap trips. */
    @Test
    void fullPagesStopAtThePageCap() {
        StringBuilder page = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            page.append(i > 0 ? "," : "").append(opportunity("id" + i, "Buyer", "Brea", "CA"));
        }
        String full = "{\"opportunities\":[" + page + "]}";
        assertThat(scraper(u -> full).scrape("groceryoutlet")).hasSize(50);
    }

    @Test
    void networkErrorIsSwallowedPerQuery() {
        UltiProScraper s = new UltiProScraper(WebClientStubs.erroring(), MAPPER, props());
        assertThat(s.scrape("groceryoutlet")).isEmpty();
    }

    @Test
    void unknownCompanyReturnsEmpty() {
        assertThat(scraper(u -> body()).scrape("nope")).isEmpty();
    }

    @Test
    void platformAndCompaniesAreExposed() {
        UltiProScraper s = scraper(u -> body());
        assertThat(s.platform()).isEqualTo("ultipro");
        assertThat(s.companies()).containsExactly("groceryoutlet");
    }

    @Test
    void defaultHostIsRecruitingAndSearchUrlIsBuilt() {
        UltiProProperties.UltiProCompany c = new UltiProProperties.UltiProCompany();
        c.setName("x");
        c.setTenant("T1");
        c.setBoardId("b1");
        assertThat(c.getHost()).isEqualTo("recruiting");
        assertThat(c.searchUrl())
                .isEqualTo(
                        "https://recruiting.ultipro.com/T1/JobBoard/b1/JobBoardView/"
                                + "LoadSearchResults");
        UltiProProperties p = new UltiProProperties();
        p.setCompanies(List.of(c));
        assertThat(p.findByName("x")).isPresent();
        assertThat(p.findByName("missing")).isEmpty();
    }
}
