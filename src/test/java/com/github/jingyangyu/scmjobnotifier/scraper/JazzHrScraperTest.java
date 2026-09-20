package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.JazzHrProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class JazzHrScraperTest {

    private static JazzHrProperties props() {
        JazzHrProperties p = new JazzHrProperties();
        p.setCompanies(List.of("esaero"));
        return p;
    }

    private static String item(String code, String slug, String title, String location) {
        return "<li class=\"list-group-item\"><h3 class='list-group-item-heading'>"
                + "<a href=\"https://esaero.applytojob.com/apply/"
                + code
                + "/"
                + slug
                + "\">\n   "
                + title
                + "   </a></h3><ul class='list-inline list-group-item-text'>"
                + (location == null
                        ? ""
                        : "<li><i class='fa fa-map-marker'></i>" + location + "</li>")
                + "</ul></li>";
    }

    private static List<JobPosting> scrape(String html) {
        return new JazzHrScraper(WebClientStubs.text(u -> html, "text/html"), props())
                .scrape("esaero");
    }

    @Test
    void scrapeParsesBoardItems() {
        List<JobPosting> jobs =
                scrape("<html>" + item("ABC123", "Buyer-II", "Buyer II", "San Luis Obispo, CA"));
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("ABC123");
        assertThat(j.getTitle()).isEqualTo("Buyer II");
        assertThat(j.getLocation()).isEqualTo("San Luis Obispo, CA");
        assertThat(j.getUrl()).isEqualTo("https://esaero.applytojob.com/apply/ABC123/Buyer-II");
        assertThat(j.getPostedDate()).isNull();
        assertThat(j.getCompany()).isEqualTo("esaero");
    }

    @Test
    void multipleItemsKeepTheirOwnLocations() {
        List<JobPosting> jobs =
                scrape(
                        item("A1", "buyer", "Buyer", "Irvine, CA")
                                + item("B2", "planner", "Master Scheduler", "Reno, NV"));
        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).getLocation()).isEqualTo("Irvine, CA");
        assertThat(jobs.get(1).getLocation()).isEqualTo("Reno, NV");
    }

    /** A card with no location must not borrow the next card's city. */
    @Test
    void itemWithoutLocationYieldsBlankLocation() {
        List<JobPosting> jobs =
                scrape(item("A1", "buyer", "Buyer", null) + item("B2", "p", "Planner", "Brea, CA"));
        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).getLocation()).isEmpty();
        assertThat(jobs.get(1).getLocation()).isEqualTo("Brea, CA");
    }

    @Test
    void nestedMarkupAndWhitespaceInTitleAreCleaned() {
        List<JobPosting> jobs =
                scrape(item("A1", "b", "Buyer   <span>II</span>\n  (Swing)", "Fresno, CA"));
        assertThat(jobs.get(0).getTitle()).isEqualTo("Buyer II (Swing)");
    }

    @Test
    void itemWithEmptyTitleIsSkipped() {
        assertThat(scrape(item("A1", "b", "   ", "Fresno, CA"))).isEmpty();
    }

    @Test
    void boardWithNoItemsYieldsNoJobs() {
        assertThat(scrape("<html><body>No openings</body></html>")).isEmpty();
    }

    @Test
    void emptyOrMissingBodyYieldsNoJobs() {
        assertThat(scrape("")).isEmpty();
        assertThat(new JazzHrScraper(WebClientStubs.noBody(), props()).scrape("esaero")).isEmpty();
    }

    @Test
    void networkErrorYieldsNoJobs() {
        assertThat(new JazzHrScraper(WebClientStubs.erroring(), props()).scrape("esaero"))
                .isEmpty();
    }

    @Test
    void unknownCompanyReturnsEmpty() {
        assertThat(scrape("<html>").isEmpty()).isTrue();
        assertThat(
                        new JazzHrScraper(WebClientStubs.text(u -> "<html>", "text/html"), props())
                                .scrape("nope"))
                .isEmpty();
    }

    @Test
    void platformAndCompaniesAreExposed() {
        JazzHrScraper s = new JazzHrScraper(WebClientStubs.text(u -> "", "text/html"), props());
        assertThat(s.platform()).isEqualTo("jazzhr");
        assertThat(s.companies()).containsExactly("esaero");
    }
}
