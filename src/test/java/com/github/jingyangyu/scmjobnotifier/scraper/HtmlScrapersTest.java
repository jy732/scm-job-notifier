package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.IcimsProperties;
import com.github.jingyangyu.scmjobnotifier.config.PaylocityProperties;
import com.github.jingyangyu.scmjobnotifier.config.SuccessFactorsProperties;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reliable-path coverage for the HTML-regex, config-driven scrapers: construction, platform/
 * companies, unknown-company (config miss), no-match HTML, and the error/catch path.
 */
class HtmlScrapersTest {

    // ── iCIMS ──
    private static IcimsProperties icimsProps() {
        IcimsProperties.IcimsCompany c = new IcimsProperties.IcimsCompany();
        c.setName("nikkiso");
        c.setSubdomain("careers-nikkiso");
        IcimsProperties p = new IcimsProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    @Test
    void icims() {
        IcimsScraper s =
                new IcimsScraper(
                        WebClientStubs.text(u -> "<html></html>", "text/html"), icimsProps());
        assertThat(s.platform()).isEqualTo("icims");
        assertThat(s.companies()).containsExactly("nikkiso");
        assertThat(s.scrape("unknown")).isEmpty();
        assertThat(s.scrape("nikkiso")).isEmpty(); // no job anchors in HTML
        assertThat(new IcimsScraper(WebClientStubs.erroring(), icimsProps()).scrape("nikkiso"))
                .isEmpty();
    }

    // ── SuccessFactors ──
    private static SuccessFactorsProperties sfProps() {
        SuccessFactorsProperties.SuccessFactorsCompany c =
                new SuccessFactorsProperties.SuccessFactorsCompany();
        c.setName("supermicro");
        c.setHost("jobs.supermicro.com");
        SuccessFactorsProperties p = new SuccessFactorsProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    @Test
    void successFactors() {
        SuccessFactorsScraper s =
                new SuccessFactorsScraper(
                        WebClientStubs.text(u -> "<html></html>", "text/html"), sfProps());
        assertThat(s.platform()).isEqualTo("successfactors");
        assertThat(s.companies()).containsExactly("supermicro");
        assertThat(s.scrape("unknown")).isEmpty();
        assertThat(s.scrape("supermicro")).isEmpty();
        assertThat(
                        new SuccessFactorsScraper(WebClientStubs.erroring(), sfProps())
                                .scrape("supermicro"))
                .isEmpty();
    }

    // ── Paylocity ──
    private static PaylocityProperties payProps() {
        PaylocityProperties.PaylocityCompany c = new PaylocityProperties.PaylocityCompany();
        c.setName("baycitiescontainer");
        c.setCompanyId("ABC");
        c.setSlug("Bay-Cities");
        PaylocityProperties p = new PaylocityProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    @Test
    void paylocity() {
        PaylocityScraper s =
                new PaylocityScraper(WebClientStubs.text(u -> "{}", "text/html"), payProps());
        assertThat(s.platform()).isEqualTo("paylocity");
        assertThat(s.companies()).containsExactly("baycitiescontainer");
        assertThat(s.scrape("unknown")).isEmpty();
        assertThat(
                        new PaylocityScraper(WebClientStubs.erroring(), payProps())
                                .scrape("baycitiescontainer"))
                .isEmpty();
    }
}
