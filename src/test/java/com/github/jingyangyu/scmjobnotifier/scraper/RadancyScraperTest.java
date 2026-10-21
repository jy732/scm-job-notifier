package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.RadancyProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RadancyScraperTest {

    /** Builds a results row in TalentBrew's markup — spans are siblings of the anchor. */
    private static String row(String id, String slug, String title, String location, String date) {
        return "<li class=\"no-security-clearance\">"
                + "<a class=\"search-results__job-link\" href=\"/job/city/"
                + slug
                + "/185/"
                + id
                + "\" data-job-id=\""
                + id
                + "\"><span class=\"search-results__job-title\">"
                + title
                + "</span></a>"
                + "<span class=\"search-results__job-info location\">"
                + location
                + "</span>"
                + "<span class=\"search-results__job-info date\">"
                + date
                + "</span>"
                + "<button type=\"button\" class=\"js-save-job-btn\" data-job-id=\""
                + id
                + "\"><span>Save for Later</span></button></li>";
    }

    private static String results(int totalPages, String... rows) {
        return "{\"results\":\"<section class=\\\"search-results\\\" data-total-results=\\\"2\\\" "
                + "data-total-pages=\\\""
                + totalPages
                + "\\\"><ul>"
                + String.join("", rows).replace("\"", "\\\"")
                + "</ul></section>\",\"hasJobs\":true}";
    }

    private static final String PAGE_ONE =
            results(
                    1,
                    row(
                            "102185258480",
                            "buyer",
                            "Buyer &amp; Planner",
                            "El Segundo, California; and other locations",
                            "10/19/2026"));

    private RadancyProperties properties() {
        RadancyProperties.RadancyCompany c = new RadancyProperties.RadancyCompany();
        c.setName("boeing");
        c.setHost("jobs.boeing.com");
        RadancyProperties p = new RadancyProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    private RadancyScraper scraper(Function<String, String> stub) {
        return new RadancyScraper(WebClientStubs.json(stub), new ObjectMapper(), properties());
    }

    @Test
    void platformAndCompanies() {
        RadancyScraper s = scraper(u -> PAGE_ONE);
        assertThat(s.platform()).isEqualTo("radancy");
        assertThat(s.companies()).containsExactly("boeing");
    }

    @Test
    void scrapeParsesRows() {
        List<JobPosting> jobs = scraper(u -> PAGE_ONE).scrape("boeing");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getCompany()).isEqualTo("boeing");
        assertThat(j.getExternalId()).isEqualTo("102185258480");
        assertThat(j.getTitle()).isEqualTo("Buyer & Planner"); // entity-unescaped
        assertThat(j.getLocation()).isEqualTo("El Segundo, California; and other locations");
        assertThat(j.getUrl()).isEqualTo("https://jobs.boeing.com/job/city/buyer/185/102185258480");
        assertThat(j.getPostedDate()).isEqualTo(Instant.parse("2026-10-19T00:00:00Z"));
        assertThat(j.getDescription()).isEmpty(); // listing rows carry none
    }

    @Test
    void unknownCompanyEmpty() {
        assertThat(scraper(u -> PAGE_ONE).scrape("nope")).isEmpty();
    }

    @Test
    void paginatesUpToTotalPages() {
        Function<String, String> stub =
                u ->
                        u.contains("CurrentPage=1")
                                ? results(
                                        2,
                                        row(
                                                "a",
                                                "s",
                                                "Buyer",
                                                "Long Beach, California",
                                                "1/2/2026"))
                                : results(
                                        2,
                                        row(
                                                "b",
                                                "s",
                                                "Planner",
                                                "Seal Beach, California",
                                                "1/3/2026"));
        assertThat(scraper(stub).scrape("boeing"))
                .extracting(JobPosting::getExternalId)
                .containsExactly("a", "b");
    }

    @Test
    void stopsWhenAPageAddsNoNewIds() {
        // every page returns the same row — the no-new-ids guard must break instead of looping
        assertThat(scraper(u -> PAGE_ONE).scrape("boeing")).hasSize(1);
    }

    @Test
    void missingTotalPagesFallsBackToCap() {
        // no data-total-pages attribute: page 1 parses, page 2 repeats, so the guard stops it
        String noMeta =
                "{\"results\":\"<section><ul>"
                        + row("a", "s", "Buyer", "Irvine, California", "1/2/2026")
                                .replace("\"", "\\\"")
                        + "</ul></section>\"}";
        assertThat(scraper(u -> noMeta).scrape("boeing")).hasSize(1);
    }

    @Test
    void blankResultsBreaksPagination() {
        assertThat(scraper(u -> "{\"results\":\"\"}").scrape("boeing")).isEmpty();
    }

    @Test
    void noRowsInFragmentYieldsNothing() {
        assertThat(scraper(u -> results(1)).scrape("boeing")).isEmpty();
    }

    @Test
    void rowWithUnparseableDateKeptWithNullPostedDate() {
        String bad = results(1, row("a", "s", "Buyer", "Irvine, California", "not-a-date"));
        List<JobPosting> jobs = scraper(u -> bad).scrape("boeing");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getPostedDate()).isNull();
    }

    @Test
    void rowWithMissingSpansKeptWithBlankFields() {
        String bare =
                "{\"results\":\"<section data-total-pages=\\\"1\\\"><ul><li>"
                        + "<a class=\\\"search-results__job-link\\\" href=\\\"/job/x\\\" "
                        + "data-job-id=\\\"z1\\\"></a></li></ul></section>\"}";
        List<JobPosting> jobs = scraper(u -> bare).scrape("boeing");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getTitle()).isEmpty();
        assertThat(jobs.get(0).getLocation()).isEmpty();
        assertThat(jobs.get(0).getPostedDate()).isNull();
    }

    @Test
    void nullBodyBreaksPagination() {
        RadancyScraper s =
                new RadancyScraper(WebClientStubs.noBody(), new ObjectMapper(), properties());
        assertThat(s.scrape("boeing")).isEmpty();
    }

    @Test
    void networkErrorIsCaught() {
        RadancyScraper s =
                new RadancyScraper(WebClientStubs.erroring(), new ObjectMapper(), properties());
        assertThat(s.scrape("boeing")).isEmpty();
    }

    @Test
    void fetchDescriptionsFillsAtsDescription() {
        // The real page wraps the body in <div id="ats-description"> and follows it with the
        // marketing footer, which must NOT be swallowed.
        String detail =
                "<html><div id=\"ats-description\" class=\"ats-description show-less\">"
                        + "<p><b>Job Description</b></p>"
                        + "<p>Manage the supply base &amp; schedules&#x2011;end&#39;s.</p></div>"
                        + "<div class=\"footer\"><p>Boeing in Germany</p></div></html>";
        RadancyScraper s =
                new RadancyScraper(
                        WebClientStubs.text(
                                u -> u.contains("/job/") ? detail : PAGE_ONE, "text/html"),
                        new ObjectMapper(),
                        properties());
        JobPosting job =
                JobPosting.builder()
                        .company("boeing")
                        .externalId("1")
                        .url("https://jobs.boeing.com/job/city/buyer/185/1")
                        .build();
        s.fetchDescriptions(List.of(job));
        assertThat(job.getDescription())
                .isEqualTo("Job Description Manage the supply base & schedules\u2011end's.")
                .doesNotContain("Boeing in Germany"); // footer stays out
    }

    @Test
    void fetchDescriptionsLeavesJobAloneWhenBlockAbsent() {
        RadancyScraper s =
                new RadancyScraper(
                        WebClientStubs.text(u -> "<html>no description here</html>", "text/html"),
                        new ObjectMapper(),
                        properties());
        JobPosting job = JobPosting.builder().company("boeing").externalId("1").url("u").build();
        s.fetchDescriptions(List.of(job));
        assertThat(job.getDescription()).isNull();
    }

    @Test
    void fetchDescriptionsSurvivesNullAndFailedResponses() {
        JobPosting a = JobPosting.builder().company("boeing").externalId("1").url("u").build();
        new RadancyScraper(WebClientStubs.noBody(), new ObjectMapper(), properties())
                .fetchDescriptions(List.of(a));
        assertThat(a.getDescription()).isNull();

        JobPosting b = JobPosting.builder().company("boeing").externalId("2").url("u").build();
        new RadancyScraper(WebClientStubs.erroring(), new ObjectMapper(), properties())
                .fetchDescriptions(List.of(b));
        assertThat(b.getDescription()).isNull();
    }
}
