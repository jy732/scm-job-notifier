package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.AdpMyJobsProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AdpMyJobsScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONFIG = "{\"myJobsToken\":\"tok-123\"}";

    /** The tenant's facet list, with the state category the scraper looks for. */
    private static final String FILTERS =
            "{\"filterList\":[{\"category\":\"FIELD2\",\"categoryLabel\":\"CITY\","
                    + "\"filterList\":[{\"value\":\"Brea\"}]},"
                    + "{\"category\":\"FIELD1\",\"categoryLabel\":\"State\","
                    + "\"filterList\":[{\"value\":\"Arizona\"},{\"value\":\"California\"}]}]}";

    private static AdpMyJobsProperties props() {
        AdpMyJobsProperties.AdpMyJobsCompany c = new AdpMyJobsProperties.AdpMyJobsCompany();
        c.setName("bootbarn");
        c.setDomain("boottbarnext");
        AdpMyJobsProperties p = new AdpMyJobsProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    private static AdpMyJobsScraper scraper(Function<String, String> urlToBody) {
        return new AdpMyJobsScraper(WebClientStubs.json(urlToBody), MAPPER, props());
    }

    private static String requisition(String id, String title, String city, String state) {
        return "{\"reqId\":\""
                + id
                + "\",\"publishedJobTitle\":\""
                + title
                + "\",\"jobDescription\":\"desc\",\"postingDate\":\"2026-09-18T21:51:25Z\","
                + "\"requisitionLocations\":[{\"address\":{\"cityName\":\""
                + city
                + "\",\"countrySubdivisionLevel1\":{\"codeValue\":\""
                + state
                + "\"}},\"nameCode\":{\"longName\":\"494 Fallback CA\"}}]}";
    }

    private static String listing(int count, String... requisitions) {
        return "{\"count\":"
                + count
                + ",\"jobRequisitions\":["
                + String.join(",", requisitions)
                + "]}";
    }

    /** Routes each leg of the flow: career-site config, facet lookup, then the listing. */
    private static Function<String, String> flow(String listing) {
        return url -> {
            if (url.contains("career-site")) {
                return CONFIG;
            }
            if (url.contains("search-custom-filters")) {
                return FILTERS;
            }
            return listing;
        };
    }

    @Test
    void scrapeParsesRequisitions() {
        List<JobPosting> jobs =
                scraper(flow(listing(1, requisition("r1", "Inventory Planner", "Fontana", "CA"))))
                        .scrape("bootbarn");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("r1");
        assertThat(j.getTitle()).isEqualTo("Inventory Planner");
        assertThat(j.getLocation()).isEqualTo("Fontana, CA");
        assertThat(j.getDescription()).isEqualTo("desc");
        assertThat(j.getPostedDate()).isNotNull();
        assertThat(j.getUrl())
                .isEqualTo("https://myjobs.adp.com/boottbarnext/cx/job-details?reqId=r1");
    }

    /**
     * The California clause must reach the wire as %20/%27 — ADP silently ignores a "+"-encoded
     * filter and returns the whole unfiltered board.
     */
    @Test
    void californiaFilterIsSentPercentEncoded() {
        StringBuilder listingUrl = new StringBuilder();
        scraper(
                        url -> {
                            if (url.contains("career-site")) {
                                return CONFIG;
                            }
                            if (url.contains("search-custom-filters")) {
                                return FILTERS;
                            }
                            listingUrl.append(url);
                            return listing(0);
                        })
                .scrape("bootbarn");
        assertThat(listingUrl.toString()).contains("FIELD1%20eq%20%27California%27");
        assertThat(listingUrl.toString()).doesNotContain("+eq+");
    }

    /** No state facet (or no California in it) means the whole board is paged instead. */
    @Test
    void missingStateFacetFallsBackToUnfilteredPaging() {
        String noState =
                "{\"filterList\":[{\"category\":\"FIELD2\",\"categoryLabel\":\"CITY\","
                        + "\"filterList\":[{\"value\":\"Brea\"}]}]}";
        StringBuilder listingUrl = new StringBuilder();
        List<JobPosting> jobs =
                scraper(
                                url -> {
                                    if (url.contains("career-site")) {
                                        return CONFIG;
                                    }
                                    if (url.contains("search-custom-filters")) {
                                        return noState;
                                    }
                                    listingUrl.append(url);
                                    return listing(1, requisition("x", "Buyer", "Brea", "CA"));
                                })
                        .scrape("bootbarn");
        assertThat(jobs).hasSize(1);
        assertThat(listingUrl.toString()).contains("$filter=&");
    }

    @Test
    void stateFacetWithoutCaliforniaAlsoFallsBack() {
        String otherStates =
                "{\"filterList\":[{\"category\":\"FIELD1\",\"categoryLabel\":\"State\","
                        + "\"filterList\":[{\"value\":\"Texas\"}]}]}";
        StringBuilder listingUrl = new StringBuilder();
        scraper(
                        url -> {
                            if (url.contains("career-site")) {
                                return CONFIG;
                            }
                            if (url.contains("search-custom-filters")) {
                                return otherStates;
                            }
                            listingUrl.append(url);
                            return listing(0);
                        })
                .scrape("bootbarn");
        assertThat(listingUrl.toString()).contains("$filter=&");
    }

    @Test
    void titleFallsBackToJobTitleWhenPublishedTitleBlank() {
        String odd =
                "{\"reqId\":\"t\",\"publishedJobTitle\":\"  \",\"jobTitle\":\"Buyer\","
                        + "\"requisitionLocations\":[]}";
        assertThat(scraper(flow(listing(1, odd))).scrape("bootbarn").get(0).getTitle())
                .isEqualTo("Buyer");
    }

    @Test
    void requisitionsMissingIdOrTitleAreSkipped() {
        String bad =
                "{\"reqId\":\"\",\"publishedJobTitle\":\"Buyer\"},"
                        + "{\"reqId\":\"x\",\"publishedJobTitle\":\"\",\"jobTitle\":\"  \"}";
        assertThat(scraper(flow(listing(1, bad))).scrape("bootbarn")).isEmpty();
    }

    @Test
    void multiSiteRequisitionJoinsEveryLocation() {
        String multi =
                "{\"reqId\":\"m\",\"publishedJobTitle\":\"Planner\",\"requisitionLocations\":["
                        + "{\"address\":{\"cityName\":\"Irvine\",\"countrySubdivisionLevel1\":"
                        + "{\"codeValue\":\"CA\"}}},"
                        + "{\"address\":{\"cityName\":\"Reno\",\"countrySubdivisionLevel1\":"
                        + "{\"codeValue\":\"NV\"}}}]}";
        assertThat(scraper(flow(listing(1, multi))).scrape("bootbarn").get(0).getLocation())
                .isEqualTo("Irvine, CA; Reno, NV");
    }

    @Test
    void locationFallsBackToNameCodeAndHandlesPartialAddresses() {
        String odd =
                "{\"reqId\":\"o\",\"publishedJobTitle\":\"Buyer\",\"requisitionLocations\":["
                        + "{\"address\":{},\"nameCode\":{\"longName\":\"494 Fallback CA\"}},"
                        + "{\"address\":{\"cityName\":\"Brea\"}},"
                        + "{\"address\":{\"countrySubdivisionLevel1\":{\"codeValue\":\"OR\"}}},"
                        + "{\"address\":{},\"nameCode\":{\"longName\":\"\"}}]}";
        assertThat(scraper(flow(listing(1, odd))).scrape("bootbarn").get(0).getLocation())
                .isEqualTo("494 Fallback CA; Brea; OR");
    }

    @Test
    void unparseableOrMissingDateLeavesNull() {
        String bad = "{\"reqId\":\"d\",\"publishedJobTitle\":\"Buyer\",\"postingDate\":\"nope\"}";
        assertThat(scraper(flow(listing(1, bad))).scrape("bootbarn").get(0).getPostedDate())
                .isNull();
        String none = "{\"reqId\":\"d2\",\"publishedJobTitle\":\"Buyer\"}";
        assertThat(scraper(flow(listing(1, none))).scrape("bootbarn").get(0).getPostedDate())
                .isNull();
    }

    /** A full page with a bigger count means another request. */
    @Test
    void pagingWalksFullPagesThenStops() {
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            full.append(i > 0 ? "," : "").append(requisition("p" + i, "Buyer", "Brea", "CA"));
        }
        String firstPage = listing(500, full.toString());
        String shortPage = listing(500, requisition("last", "Planner", "Irvine", "CA"));
        List<JobPosting> jobs =
                scraper(
                                url -> {
                                    if (url.contains("career-site")) {
                                        return CONFIG;
                                    }
                                    if (url.contains("search-custom-filters")) {
                                        return FILTERS;
                                    }
                                    return url.contains("$skip=0") ? firstPage : shortPage;
                                })
                        .scrape("bootbarn");
        assertThat(jobs).hasSize(101);
    }

    /** A board that always answers with a full page and a huge count trips the page cap. */
    @Test
    void endlessFullPagesStopAtThePageCap() {
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            full.append(i > 0 ? "," : "").append(requisition("cap" + i, "Buyer", "Brea", "CA"));
        }
        String endless = listing(999999, full.toString());
        assertThat(scraper(flow(endless)).scrape("bootbarn")).hasSize(100);
    }

    @Test
    void missingTokenStopsBeforeTheApiCall() {
        assertThat(scraper(url -> "{\"name\":\"no token here\"}").scrape("bootbarn")).isEmpty();
    }

    @Test
    void emptyAndMalformedResponsesYieldNoJobs() {
        assertThat(scraper(flow(listing(0))).scrape("bootbarn")).isEmpty();
        assertThat(scraper(flow("not json")).scrape("bootbarn")).isEmpty();
        assertThat(scraper(flow("")).scrape("bootbarn")).isEmpty();
        assertThat(scraper(flow("{\"other\":1}")).scrape("bootbarn")).isEmpty();
    }

    @Test
    void blankBodyFromServerYieldsNoJobs() {
        assertThat(
                        new AdpMyJobsScraper(WebClientStubs.noBody(), MAPPER, props())
                                .scrape("bootbarn"))
                .isEmpty();
    }

    @Test
    void networkErrorYieldsNoJobs() {
        assertThat(
                        new AdpMyJobsScraper(WebClientStubs.erroring(), MAPPER, props())
                                .scrape("bootbarn"))
                .isEmpty();
    }

    @Test
    void unknownCompanyReturnsEmpty() {
        assertThat(scraper(flow(listing(0))).scrape("nope")).isEmpty();
    }

    @Test
    void platformAndCompaniesAreExposed() {
        AdpMyJobsScraper s = scraper(flow(listing(0)));
        assertThat(s.platform()).isEqualTo("adpmyjobs");
        assertThat(s.companies()).containsExactly("bootbarn");
    }

    @Test
    void configUrlsAreBuiltAndLookupWorks() {
        AdpMyJobsProperties.AdpMyJobsCompany c = new AdpMyJobsProperties.AdpMyJobsCompany();
        c.setName("x");
        c.setDomain("dom");
        assertThat(c.careerSiteUrl())
                .isEqualTo("https://myjobs.adp.com/public/staffing/v1/career-site/dom");
        assertThat(c.jobUrl("9")).isEqualTo("https://myjobs.adp.com/dom/cx/job-details?reqId=9");
        AdpMyJobsProperties p = new AdpMyJobsProperties();
        p.setCompanies(List.of(c));
        assertThat(p.findByName("x")).isPresent();
        assertThat(p.findByName("missing")).isEmpty();
    }
}
