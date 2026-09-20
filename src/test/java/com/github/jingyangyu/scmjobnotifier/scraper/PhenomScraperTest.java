package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.PhenomProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PhenomScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PhenomProperties props() {
        PhenomProperties.PhenomCompany c = new PhenomProperties.PhenomCompany();
        c.setName("lineage");
        c.setHost("careers.onelineage.com");
        PhenomProperties p = new PhenomProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    private static PhenomScraper scraper(Function<String, String> urlToBody) {
        return new PhenomScraper(WebClientStubs.text(urlToBody, "text/html"), MAPPER, props());
    }

    /** Wraps a jobs array in the inline phApp.ddo assignment the search page renders. */
    private static String page(String jobsJson) {
        return "<html><script>phApp.ddo = {\"eagerLoadRefineSearch\":{\"data\":{\"jobs\":"
                + jobsJson
                + "}}};</script></html>";
    }

    private static final String ONE_JOB =
            "[{\"jobSeqNo\":\"SEQ1\",\"title\":\"Inventory Control Tech\","
                    + "\"cityState\":\"Manteca, California\",\"descriptionTeaser\":\"teaser\","
                    + "\"postedDate\":\"2026-08-10T00:00:00.000+0000\"}]";

    @Test
    void scrapeParsesEmbeddedJobs() {
        List<JobPosting> jobs = scraper(u -> page(ONE_JOB)).scrape("lineage");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("SEQ1");
        assertThat(j.getTitle()).isEqualTo("Inventory Control Tech");
        assertThat(j.getLocation()).isEqualTo("Manteca, California");
        assertThat(j.getDescription()).isEqualTo("teaser");
        assertThat(j.getUrl())
                .isEqualTo("https://careers.onelineage.com/us/en/job/SEQ1/Inventory-Control-Tech");
    }

    /** Phenom's basic-form offset (+0000) must still parse. */
    @Test
    void basicOffsetDateIsParsed() {
        assertThat(scraper(u -> page(ONE_JOB)).scrape("lineage").get(0).getPostedDate())
                .isNotNull();
    }

    @Test
    void isoOffsetDateIsParsed() {
        String iso =
                "[{\"jobSeqNo\":\"S\",\"title\":\"Buyer\",\"postedDate\":\"2026-08-10T00:00:00Z\"}]";
        assertThat(scraper(u -> page(iso)).scrape("lineage").get(0).getPostedDate()).isNotNull();
    }

    @Test
    void unparseableOrMissingDateLeavesNull() {
        String bad = "[{\"jobSeqNo\":\"S\",\"title\":\"Buyer\",\"postedDate\":\"nope\"}]";
        assertThat(scraper(u -> page(bad)).scrape("lineage").get(0).getPostedDate()).isNull();
        String none = "[{\"jobSeqNo\":\"S2\",\"title\":\"Buyer\"}]";
        assertThat(scraper(u -> page(none)).scrape("lineage").get(0).getPostedDate()).isNull();
    }

    @Test
    void multiLocationArrayIsJoined() {
        String multi =
                "[{\"jobSeqNo\":\"M\",\"title\":\"Planner\",\"cityState\":\"Reno, Nevada\","
                        + "\"multi_location_array\":[{\"city\":\"Fontana\",\"state\":\"California\"},"
                        + "{\"city\":\"Reno\",\"state\":\"Nevada\"}]}]";
        assertThat(scraper(u -> page(multi)).scrape("lineage").get(0).getLocation())
                .isEqualTo("Fontana, California; Reno, Nevada");
    }

    @Test
    void emptyMultiLocationEntriesFallBackToCityState() {
        String odd =
                "[{\"jobSeqNo\":\"M2\",\"title\":\"Planner\",\"cityState\":\"Brea, California\","
                        + "\"multi_location_array\":[{\"foo\":\"bar\"}]}]";
        assertThat(scraper(u -> page(odd)).scrape("lineage").get(0).getLocation())
                .isEqualTo("Brea, California");
    }

    @Test
    void cityAndStateFieldsAreUsedWhenCityStateMissing() {
        String odd =
                "[{\"jobSeqNo\":\"M3\",\"title\":\"Planner\",\"city\":\"Irvine\","
                        + "\"state\":\"California\"}]";
        assertThat(scraper(u -> page(odd)).scrape("lineage").get(0).getLocation())
                .isEqualTo("Irvine, California");
    }

    @Test
    void partialCityStateFieldsDegradeGracefully() {
        String cityOnly = "[{\"jobSeqNo\":\"C\",\"title\":\"Planner\",\"city\":\"Irvine\"}]";
        assertThat(scraper(u -> page(cityOnly)).scrape("lineage").get(0).getLocation())
                .isEqualTo("Irvine");
        String stateOnly = "[{\"jobSeqNo\":\"S\",\"title\":\"Planner\",\"state\":\"Oregon\"}]";
        assertThat(scraper(u -> page(stateOnly)).scrape("lineage").get(0).getLocation())
                .isEqualTo("Oregon");
    }

    @Test
    void textualMultiLocationEntriesAreKept() {
        String textual =
                "[{\"jobSeqNo\":\"T\",\"title\":\"Planner\","
                        + "\"multi_location_array\":[\"Fontana, California\",\"\"]}]";
        assertThat(scraper(u -> page(textual)).scrape("lineage").get(0).getLocation())
                .isEqualTo("Fontana, California");
    }

    @Test
    void recordsMissingIdOrTitleAreSkipped() {
        String bad =
                "[{\"jobSeqNo\":\"\",\"title\":\"Buyer\"},{\"jobSeqNo\":\"X\",\"title\":\"  \"}]";
        assertThat(scraper(u -> page(bad)).scrape("lineage")).isEmpty();
    }

    @Test
    void pagesWithoutDdoOrJobsYieldNothing() {
        assertThat(scraper(u -> "<html>no ddo here</html>").scrape("lineage")).isEmpty();
        assertThat(scraper(u -> page("[]")).scrape("lineage")).isEmpty();
        assertThat(scraper(u -> "<script>phApp.ddo = {oops;</script>").scrape("lineage")).isEmpty();
        assertThat(scraper(u -> "<script>phApp.ddo = {\"a\":1};</script>").scrape("lineage"))
                .isEmpty();
        assertThat(scraper(u -> "").scrape("lineage")).isEmpty();
    }

    /** The blob matches the assignment pattern but is not valid JSON — parse error path. */
    @Test
    void ddoThatMatchesButIsNotJsonYieldsNothing() {
        assertThat(scraper(u -> "<script>phApp.ddo = {bad json here};</script>").scrape("lineage"))
                .isEmpty();
    }

    @Test
    void jobsNodeThatIsNotAnArrayYieldsNothing() {
        String notArray =
                "<script>phApp.ddo = {\"eagerLoadRefineSearch\":{\"data\":{\"jobs\":\"x\"}}};"
                        + "</script>";
        assertThat(scraper(u -> notArray).scrape("lineage")).isEmpty();
    }

    @Test
    void blankBodyFromServerYieldsNoJobs() {
        assertThat(new PhenomScraper(WebClientStubs.noBody(), MAPPER, props()).scrape("lineage"))
                .isEmpty();
    }

    /** A full page implies another page; the stub repeats it until the page cap trips. */
    @Test
    void fullPagesStopAtThePageCap() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            b.append(i > 0 ? "," : "")
                    .append("{\"jobSeqNo\":\"S")
                    .append(i)
                    .append("\",\"title\":\"Buyer\",\"cityState\":\"Brea, California\"}");
        }
        String full = page(b.append("]").toString());
        assertThat(scraper(u -> full).scrape("lineage")).hasSize(10);
    }

    @Test
    void networkErrorIsSwallowedPerQuery() {
        assertThat(new PhenomScraper(WebClientStubs.erroring(), MAPPER, props()).scrape("lineage"))
                .isEmpty();
    }

    @Test
    void unknownCompanyReturnsEmpty() {
        assertThat(scraper(u -> page(ONE_JOB)).scrape("nope")).isEmpty();
    }

    @Test
    void platformAndCompaniesAreExposed() {
        PhenomScraper s = scraper(u -> page("[]"));
        assertThat(s.platform()).isEqualTo("phenom");
        assertThat(s.companies()).containsExactly("lineage");
    }

    @Test
    void searchUrlUsesConfiguredLocalePath() {
        PhenomProperties.PhenomCompany c = new PhenomProperties.PhenomCompany();
        c.setHost("careers.ppg.com");
        assertThat(c.getLocalePath()).isEqualTo("us/en");
        assertThat(c.searchUrl("buyer", 10))
                .isEqualTo(
                        "https://careers.ppg.com/us/en/search-results?keywords=buyer&from=10&s=1");
    }
}
