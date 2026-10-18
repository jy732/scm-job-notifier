package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.AvatureProperties;
import com.github.jingyangyu.scmjobnotifier.config.AvatureProperties.AvatureCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class AvatureScraperTest {

    private static final String HOST = "avature.sandisk.com";

    /** The list widget's config, as Avature embeds it in {@code <list data-props='...'>}. */
    private static String props(String listType) {
        return "{\"widgetId\":\"searchJobsList\",\"uuid\":\"u-1\","
                + "\"hasToIncludePaginationOptions\":false,\"allowListSorting\":true,"
                + "\"listType\":\""
                + listType
                + "\",\"allowFilteringFromUrlParams\":true,"
                + "\"links\":{\"applyPage\":{\"targetPageUuid\":\"a-1\"}},"
                + "\"dynamicValueConfigs\":[],\"shouldAddBase64FileFields\":false,"
                // &amp; exercises the entity-unescaping of the attribute value
                + "\"searchMode\":\"ResultsAndCount\",\"formId\":\"tpt_jobSortableForm&amp;x\","
                + "\"searchIndexId\":1,\"qtvc\":\"deadbeef\",\"layout\":\"custom\"}";
    }

    /** A search page carrying a decoy non-job list widget before the real one. */
    private static String searchPage() {
        return "<html><list data-props='"
                + props("PeopleList")
                + "'></list><list data-props='"
                + props("JobList")
                + "'></list>"
                + "<script src=\"/portal/13/js/specifics.js\"></script></html>";
    }

    /** One result row in Avature's shape: stable codes plus opaque per-tenant schema fields. */
    private static String row(String id, String title, String city, String state) {
        return "{\"id\":"
                + id
                + ",\"entityId\":9"
                + id
                + ",\"fields\":{"
                + "\"name\":{\"stringValue\":\""
                + title
                + "\"},"
                + "\"postedDate\":{\"stringValue\":\"2026-10-16\"},"
                + "\"sf_city\":{\"stringValue\":\""
                + city
                + "\"},"
                + "\"sf_state\":{\"stringValue\":\""
                + state
                + "\"},"
                + "\"sf_country\":{\"stringValue\":\"\"},"
                + "\"sf_desc\":{\"stringValue\":\"Own the procurement plan.\"}}}";
    }

    /** {@code columns[]} maps human labels to those opaque codes — and includes junk entries. */
    private static final String COLUMNS =
            "\"columns\":["
                    + "{\"code\":\"name\",\"label\":\"Name\"},"
                    + "{\"code\":\"sf_city\",\"label\":\"City\"},"
                    + "{\"code\":\"sf_state\",\"label\":\"State\"},"
                    + "{\"code\":\"sf_country\",\"label\":\"Country\"},"
                    + "{\"code\":\"sf_desc\",\"label\":\"Job Description\"},"
                    + "{\"code\":\"sf_other\",\"label\":\"City\"},"
                    + "{\"code\":\"\",\"label\":\"Blank Code\"},"
                    + "{\"code\":\"sf_x\",\"label\":\"\"}]";

    private static String listBody(String... rows) {
        return "{" + COLUMNS + ",\"results\":[" + String.join(",", rows) + "]}";
    }

    private static AvatureProperties properties() {
        AvatureCompany c = new AvatureCompany();
        c.setName("sandisk");
        c.setHost(HOST);
        AvatureProperties p = new AvatureProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    /** Stub that serves the search page with cookies and the list endpoint with JSON. */
    private static WebClient.Builder stub(
            Function<String, String> list, Map<String, String> cookies) {
        ExchangeFunction ex =
                req -> {
                    String url = req.url().toString();
                    ClientResponse.Builder b = ClientResponse.create(HttpStatus.OK);
                    if (url.contains("_portalList")) {
                        b.header("Content-Type", "application/json").body(list.apply(url));
                    } else {
                        cookies.forEach(b::cookie);
                        b.header("Content-Type", "text/html").body(searchPage());
                    }
                    return Mono.just(b.build());
                };
        return WebClient.builder().exchangeFunction(ex);
    }

    private static AvatureScraper scraper(
            Function<String, String> list, Map<String, String> cookies) {
        return new AvatureScraper(stub(list, cookies), properties());
    }

    private static AvatureScraper scraper(Function<String, String> list) {
        return scraper(list, Map.of("ScustomPortal-13", "sess", "other", "v"));
    }

    @Test
    void platformAndCompanies() {
        AvatureScraper s = scraper(u -> listBody());
        assertThat(s.platform()).isEqualTo("avature");
        assertThat(s.companies()).containsExactly("sandisk");
    }

    @Test
    void scrapeParsesLabelledFields() {
        List<JobPosting> jobs =
                scraper(
                                u ->
                                        listBody(
                                                row(
                                                        "4032",
                                                        "Procurement Business Partner",
                                                        "Irvine",
                                                        "California")))
                        .scrape("sandisk");

        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getCompany()).isEqualTo("sandisk");
        assertThat(j.getExternalId()).isEqualTo("4032");
        assertThat(j.getTitle()).isEqualTo("Procurement Business Partner");
        assertThat(j.getUrl()).isEqualTo("https://avature.sandisk.com/Careers/JobDetail/job/4032");
        // location is assembled by label, skipping the blank Country field
        assertThat(j.getLocation()).isEqualTo("Irvine, California");
        assertThat(j.getDescription()).isEqualTo("Own the procurement plan.");
        assertThat(j.getPostedDate()).isEqualTo(Instant.parse("2026-10-16T00:00:00Z"));
        assertThat(j.isNotified()).isFalse();
        assertThat(j.getDetectedAt()).isNotNull();
    }

    /**
     * The request must carry the widget's own qtvc/links/uuid, and the portal id from the cookie.
     */
    @Test
    void listRequestIsRebuiltFromTheWidgetConfig() {
        StringBuilder seen = new StringBuilder();
        scraper(
                        u -> {
                            seen.append(u);
                            return listBody();
                        })
                .scrape("sandisk");

        String url = seen.toString();
        assertThat(url).startsWith("https://avature.sandisk.com/13/_portalList?");
        assertThat(url).contains("uuid=u-1").contains("qtvc=deadbeef");
        assertThat(url).contains("listType=JobList").contains("searchIndexId=1");
        assertThat(url).contains("recordsPerPage=50").contains("offset=0");
        assertThat(url).contains("searchMode=ResultsAndCount").contains("layout=custom");
        assertThat(url).contains("hasToIncludePaginationOptions=false");
        assertThat(url)
                .contains("allowListSorting=true")
                .contains("shouldAddBase64FileFields=false");
        assertThat(url).contains("dynamicValueConfigs=%5B%5D");
        // links must be sent or Avature answers 403 "This list is private"
        assertThat(url).contains("links=%7B%22applyPage%22");
        // the attribute's &amp; was unescaped before the JSON was parsed
        assertThat(url).contains("formId=tpt_jobSortableForm%26x");
    }

    @Test
    void paginatesUntilShortPage() {
        Function<String, String> list =
                url -> {
                    if (url.contains("offset=0")) {
                        String[] rows = new String[50];
                        for (int i = 0; i < 50; i++) {
                            rows[i] =
                                    row(
                                            String.valueOf(1000 + i),
                                            "Buyer " + i,
                                            "Milpitas",
                                            "California");
                        }
                        return listBody(rows);
                    }
                    if (url.contains("offset=50")) {
                        return listBody(row("2000", "Planner", "Irvine", "California"));
                    }
                    return listBody();
                };
        assertThat(scraper(list).scrape("sandisk")).hasSize(51);
    }

    @Test
    void stopsAtPageCap() {
        // every page full => the MAX_PAGES ceiling ends the loop; ids repeat so dedup keeps one
        assertThat(
                        scraper(
                                        u -> {
                                            String[] rows = new String[50];
                                            for (int i = 0; i < 50; i++) {
                                                rows[i] =
                                                        row(
                                                                String.valueOf(i),
                                                                "Buyer",
                                                                "Milpitas",
                                                                "CA");
                                            }
                                            return listBody(rows);
                                        })
                                .scrape("sandisk"))
                .hasSize(50);
    }

    @Test
    void portalIdFallsBackToPageAssetPathWhenCookieAbsent() {
        StringBuilder seen = new StringBuilder();
        List<JobPosting> jobs =
                scraper(
                                u -> {
                                    seen.append(u);
                                    return listBody(row("1", "Buyer", "Irvine", "California"));
                                },
                                Map.of())
                        .scrape("sandisk");
        assertThat(jobs).hasSize(1);
        assertThat(seen.toString()).contains("/13/_portalList");
    }

    @Test
    void unknownCompanyReturnsEmpty() {
        assertThat(scraper(u -> listBody()).scrape("nope")).isEmpty();
    }

    @Test
    void serverRenderedPortalWithoutJobListWidgetReturnsEmpty() {
        ExchangeFunction ex =
                req ->
                        Mono.just(
                                ClientResponse.create(HttpStatus.OK)
                                        .header("Content-Type", "text/html")
                                        .body("<html>no list widget here</html>")
                                        .build());
        AvatureScraper s =
                new AvatureScraper(WebClient.builder().exchangeFunction(ex), properties());
        assertThat(s.scrape("sandisk")).isEmpty();
    }

    @Test
    void missingPortalIdReturnsEmpty() {
        // JobList widget present, but neither a portal cookie nor a /portal/{id}/ asset path
        ExchangeFunction ex =
                req ->
                        Mono.just(
                                ClientResponse.create(HttpStatus.OK)
                                        .header("Content-Type", "text/html")
                                        .body(
                                                "<html><list data-props='"
                                                        + props("JobList")
                                                        + "'></list></html>")
                                        .build());
        AvatureScraper s =
                new AvatureScraper(WebClient.builder().exchangeFunction(ex), properties());
        assertThat(s.scrape("sandisk")).isEmpty();
    }

    @Test
    void emptyExchangeReturnsEmpty() {
        AvatureScraper s =
                new AvatureScraper(
                        WebClient.builder().exchangeFunction(req -> Mono.empty()), properties());
        assertThat(s.scrape("sandisk")).isEmpty();
    }

    @Test
    void searchPageWithoutBodyReturnsEmpty() {
        assertThat(new AvatureScraper(WebClientStubs.noBody(), properties()).scrape("sandisk"))
                .isEmpty();
    }

    @Test
    void networkErrorReturnsEmpty() {
        assertThat(new AvatureScraper(WebClientStubs.erroring(), properties()).scrape("sandisk"))
                .isEmpty();
    }

    @Test
    void blankListBodyReturnsEmpty() {
        assertThat(scraper(u -> "").scrape("sandisk")).isEmpty();
    }

    @Test
    void emptyResultsArrayReturnsEmpty() {
        assertThat(scraper(u -> listBody()).scrape("sandisk")).isEmpty();
    }

    @Test
    void nonArrayResultsReturnsEmpty() {
        assertThat(scraper(u -> "{\"results\":{}}").scrape("sandisk")).isEmpty();
    }

    @Test
    void rowsWithoutIdOrTitleAreSkipped() {
        String noTitle = "{\"id\":7,\"fields\":{\"name\":{\"stringValue\":\"\"}}}";
        String noId = "{\"fields\":{\"name\":{\"stringValue\":\"Buyer\"}}}";
        assertThat(scraper(u -> listBody(noTitle, noId)).scrape("sandisk")).isEmpty();
    }

    @Test
    void missingColumnsAndWidgetFieldsDegradeGracefully() {
        // no columns[] at all => location/description unresolvable; absent optional widget fields
        // fall back rather than throwing
        String body =
                "{\"columns\":{},\"results\":[{\"id\":5,\"fields\":{"
                        + "\"name\":{\"stringValue\":\"Logistics Analyst\"},"
                        + "\"postedDate\":{\"stringValue\":\"not-a-date\"}}}]}";
        List<JobPosting> jobs = scraper(u -> body).scrape("sandisk");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getTitle()).isEqualTo("Logistics Analyst");
        assertThat(jobs.get(0).getLocation()).isEmpty();
        assertThat(jobs.get(0).getDescription()).isEmpty();
        assertThat(jobs.get(0).getPostedDate()).isNull();
    }

    @Test
    void widgetConfigWithoutOptionalBlobsUsesFallbacks() {
        String bare =
                "{\"uuid\":\"u-2\",\"listType\":\"JobList\",\"qtvc\":\"q\",\"links\":null,"
                        + "\"formId\":\"f\",\"searchMode\":\"ResultsAndCount\",\"layout\":\"custom\"}";
        StringBuilder seen = new StringBuilder();
        ExchangeFunction ex =
                req -> {
                    String url = req.url().toString();
                    if (url.contains("_portalList")) {
                        seen.append(url);
                        return Mono.just(
                                ClientResponse.create(HttpStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .body(listBody())
                                        .build());
                    }
                    return Mono.just(
                            ClientResponse.create(HttpStatus.OK)
                                    .cookie("ScustomPortal-7", "s")
                                    .header("Content-Type", "text/html")
                                    .body("<html><list data-props='" + bare + "'></list></html>")
                                    .build());
                };
        new AvatureScraper(WebClient.builder().exchangeFunction(ex), properties())
                .scrape("sandisk");

        assertThat(seen.toString()).startsWith("https://avature.sandisk.com/7/_portalList?");
        // absent / null blobs fall back to empty JSON literals, and absent flags to false
        assertThat(seen.toString()).contains("links=%7B%7D").contains("dynamicValueConfigs=%5B%5D");
        assertThat(seen.toString()).contains("allowListSorting=false");
    }
}
