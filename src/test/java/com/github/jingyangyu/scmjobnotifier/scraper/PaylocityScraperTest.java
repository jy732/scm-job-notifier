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
                        + "{\"JobId\":2,\"JobTitle\":\"Sourcing \\\"Specialist\\\" \\\\ \\n\\t\","
                        + "\"City\":\"Irvine\","
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

    private static List<JobPosting> scrapeHtml(String html) {
        return new PaylocityScraper(WebClientStubs.text(u -> html, "text/html"), props())
                .scrape("baycitiescontainer");
    }

    @Test
    void unknownCompanyReturnsEmpty() {
        PaylocityScraper s =
                new PaylocityScraper(WebClientStubs.text(u -> HTML, "text/html"), props());
        assertThat(s.scrape("nope")).isEmpty();
    }

    @Test
    void noJobsKeyReturnsEmpty() {
        assertThat(scrapeHtml("<html>no data here</html>")).isEmpty();
    }

    @Test
    void nullBodyReturnsEmpty() {
        assertThat(scrapeHtml("")).isEmpty(); // empty body -> fetch returns null -> null html
    }

    @Test
    void noBracketAfterKeyReturnsEmpty() {
        assertThat(scrapeHtml("<script>var x={\"Jobs\": 5};</script>")).isEmpty();
    }

    @Test
    void unbalancedArrayReturnsEmpty() {
        assertThat(scrapeHtml("<script>{\"Jobs\":[ {\"JobId\":1,\"JobTitle\":\"X\"}</script>"))
                .isEmpty();
    }

    @Test
    void recordMissingTitleOrIdIsSkipped() {
        String html =
                "<script>{\"Jobs\":[{\"JobId\":20,\"JobTitle\":\"Keep Me\",\"City\":\"San Jose\","
                        + "\"State\":\"CA\"},{\"JobTitle\":\"Drop Me\",\"City\":\"SF\","
                        + "\"State\":\"CA\"}]}</script>";
        List<JobPosting> jobs = scrapeHtml(html);
        assertThat(jobs).hasSize(1); // second record (no JobId) skipped
        assertThat(jobs.get(0).getTitle()).isEqualTo("Keep Me");
    }

    @Test
    void extractJobsArrayHandlesNullHtml() throws Exception {
        java.lang.reflect.Method m =
                PaylocityScraper.class.getDeclaredMethod("extractJobsArray", String.class);
        m.setAccessible(true);
        assertThat(m.invoke(null, (Object) null)).isNull();
    }

    @Test
    void stateOnlyLocationAndMissingPublishedDate() {
        String html =
                "<script>{\"Jobs\":[{\"JobId\":11,\"JobTitle\":\"Planner\","
                        + "\"State\":\"CA\"}]}</script>";
        List<JobPosting> jobs = scrapeHtml(html);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getLocation()).isEqualTo("CA"); // city empty -> state only
        assertThat(jobs.get(0).getPostedDate()).isNull(); // no PublishedDate
    }

    @Test
    void offsetDateTimeIsParsed() {
        String html =
                "<script>{\"Jobs\":[{\"JobId\":12,\"JobTitle\":\"Buyer\",\"City\":\"San Jose\","
                        + "\"State\":\"CA\",\"PublishedDate\":\"2026-08-01T00:00:00-07:00\"}]}"
                        + "</script>";
        assertThat(scrapeHtml(html).get(0).getPostedDate()).isNotNull();
    }

    @Test
    void unescapeHandlesSlashCarriageReturnAndUnknownEscape() {
        String html =
                "<script>{\"Jobs\":[{\"JobId\":13,"
                        + "\"JobTitle\":\"Buyer \\/ Planner \\r X \\q Y\","
                        + "\"City\":\"San Jose\",\"State\":\"CA\"}]}</script>";
        String title = scrapeHtml(html).get(0).getTitle();
        assertThat(title).contains("/").contains("q"); // \/ -> '/', \q -> 'q'
    }
}
