package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.AdpProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AdpScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AdpProperties props() {
        AdpProperties.AdpCompany c = new AdpProperties.AdpCompany();
        c.setName("nongshim");
        c.setCid("cid-guid");
        AdpProperties p = new AdpProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    private static AdpScraper scraper(Function<String, String> urlToBody) {
        return new AdpScraper(WebClientStubs.json(urlToBody), MAPPER, props());
    }

    private static String requisition(String id, String title, String city, String state) {
        return "{\"itemID\":\""
                + id
                + "\",\"requisitionTitle\":\""
                + title
                + "\",\"clientRequisitionID\":\"1222\","
                + "\"postDate\":\"2026-09-18T18:09:00.000-04:00\",\"requisitionLocations\":"
                + "[{\"address\":{\"cityName\":\""
                + city
                + "\",\"countrySubdivisionLevel1\":{\"codeValue\":\""
                + state
                + "\"}},\"nameCode\":{\"shortName\":\" Fallback, CA, US\"}}]}";
    }

    private static String feed(int total, String... requisitions) {
        return "{\"jobRequisitions\":["
                + String.join(",", requisitions)
                + "],\"meta\":{\"totalNumber\":"
                + total
                + "}}";
    }

    @Test
    void scrapeParsesRequisitions() {
        List<JobPosting> jobs =
                scraper(u -> feed(1, requisition("i1", "Purchasing Associate", "Rancho", "CA")))
                        .scrape("nongshim");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("i1");
        assertThat(j.getTitle()).isEqualTo("Purchasing Associate");
        assertThat(j.getLocation()).isEqualTo("Rancho, CA");
        assertThat(j.getPostedDate()).isNotNull();
        assertThat(j.getUrl())
                .isEqualTo(
                        "https://workforcenow.adp.com/mascsr/default/mdf/recruitment/"
                                + "recruitment.html?cid=cid-guid&jobId=1222&lang=en_US");
    }

    @Test
    void multiSiteRequisitionJoinsEveryLocation() {
        String multi =
                "{\"itemID\":\"m\",\"requisitionTitle\":\"Buyer\",\"requisitionLocations\":["
                        + "{\"address\":{\"cityName\":\"Fremont\",\"countrySubdivisionLevel1\":"
                        + "{\"codeValue\":\"CA\"}}},"
                        + "{\"address\":{\"cityName\":\"Reno\",\"countrySubdivisionLevel1\":"
                        + "{\"codeValue\":\"NV\"}}}]}";
        assertThat(scraper(u -> feed(1, multi)).scrape("nongshim").get(0).getLocation())
                .isEqualTo("Fremont, CA; Reno, NV");
    }

    @Test
    void locationFallsBackToShortNameAndHandlesPartialAddresses() {
        String odd =
                "{\"itemID\":\"o\",\"requisitionTitle\":\"Buyer\",\"requisitionLocations\":["
                        + "{\"address\":{},\"nameCode\":{\"shortName\":\" Torrance, CA, US\"}},"
                        + "{\"address\":{\"cityName\":\"Brea\"}},"
                        + "{\"address\":{\"countrySubdivisionLevel1\":{\"codeValue\":\"OR\"}}},"
                        + "{\"address\":{},\"nameCode\":{\"shortName\":\"\"}}]}";
        // The shortName fallback keeps ADP's own " City, ST, US" form (trimmed) — still a CA token.
        assertThat(scraper(u -> feed(1, odd)).scrape("nongshim").get(0).getLocation())
                .isEqualTo("Torrance, CA, US; Brea; OR");
    }

    @Test
    void requisitionsMissingIdOrTitleAreSkipped() {
        String bad =
                "{\"itemID\":\"\",\"requisitionTitle\":\"Buyer\"},"
                        + "{\"itemID\":\"x\",\"requisitionTitle\":\"  \"}";
        assertThat(scraper(u -> feed(1, bad)).scrape("nongshim")).isEmpty();
    }

    @Test
    void urlFallsBackToItemIdWhenClientRequisitionIdMissing() {
        String noClientId = "{\"itemID\":\"only-id\",\"requisitionTitle\":\"Buyer\"}";
        assertThat(scraper(u -> feed(1, noClientId)).scrape("nongshim").get(0).getUrl())
                .contains("jobId=only-id");
    }

    @Test
    void unparseableOrMissingDateLeavesNull() {
        String bad =
                "{\"itemID\":\"d\",\"requisitionTitle\":\"Buyer\",\"postDate\":\"not-a-date\"}";
        assertThat(scraper(u -> feed(1, bad)).scrape("nongshim").get(0).getPostedDate()).isNull();
        String none = "{\"itemID\":\"d2\",\"requisitionTitle\":\"Buyer\"}";
        assertThat(scraper(u -> feed(1, none)).scrape("nongshim").get(0).getPostedDate()).isNull();
    }

    /** A full page plus a larger total means another request; ids must not be double-counted. */
    @Test
    void pagingStopsOnceTotalIsReached() {
        StringBuilder page = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            page.append(i > 0 ? "," : "").append(requisition("id" + i, "Buyer", "Brea", "CA"));
        }
        String body = feed(40, page.toString());
        assertThat(scraper(u -> body).scrape("nongshim")).hasSize(20);
    }

    /** Two full pages then a short one: exercises both stop conditions. */
    @Test
    void pagingWalksSeveralPagesThenStops() {
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            full.append(i > 0 ? "," : "").append(requisition("p1-" + i, "Buyer", "Brea", "CA"));
        }
        String firstPage = feed(100, full.toString());
        String shortPage = feed(100, requisition("last", "Planner", "Irvine", "CA"));
        assertThat(scraper(u -> u.contains("$skip=0") ? firstPage : shortPage).scrape("nongshim"))
                .hasSize(21);
    }

    @Test
    void emptyAndMalformedResponsesYieldNoJobs() {
        assertThat(scraper(u -> feed(0)).scrape("nongshim")).isEmpty();
        assertThat(scraper(u -> "not json").scrape("nongshim")).isEmpty();
        assertThat(scraper(u -> "").scrape("nongshim")).isEmpty();
        assertThat(scraper(u -> "{\"other\":1}").scrape("nongshim")).isEmpty();
    }

    @Test
    void blankBodyFromServerYieldsNoJobs() {
        assertThat(new AdpScraper(WebClientStubs.noBody(), MAPPER, props()).scrape("nongshim"))
                .isEmpty();
    }

    @Test
    void networkErrorYieldsNoJobs() {
        assertThat(new AdpScraper(WebClientStubs.erroring(), MAPPER, props()).scrape("nongshim"))
                .isEmpty();
    }

    @Test
    void unknownCompanyReturnsEmpty() {
        assertThat(scraper(u -> feed(0)).scrape("nope")).isEmpty();
    }

    @Test
    void platformAndCompaniesAreExposed() {
        AdpScraper s = scraper(u -> feed(0));
        assertThat(s.platform()).isEqualTo("adp");
        assertThat(s.companies()).containsExactly("nongshim");
    }

    @Test
    void feedUrlIsBuiltAndLookupWorks() {
        AdpProperties.AdpCompany c = new AdpProperties.AdpCompany();
        c.setName("x");
        c.setCid("abc");
        assertThat(c.feedUrl(20, 40))
                .isEqualTo(
                        "https://workforcenow.adp.com/mascsr/default/careercenter/public/events/"
                                + "staffing/v1/job-requisitions?cid=abc&lang=en_US&locale=en_US"
                                + "&$top=20&$skip=40");
        AdpProperties p = new AdpProperties();
        p.setCompanies(List.of(c));
        assertThat(p.findByName("x")).isPresent();
        assertThat(p.findByName("missing")).isEmpty();
    }
}
