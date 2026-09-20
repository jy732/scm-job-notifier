package com.github.jingyangyu.scmjobnotifier.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers the URL builders for the two Playwright-driven portals. Their scrapers are excluded from
 * coverage (they need a real browser), but the config that addresses each tenant is plain logic.
 */
class PlaywrightPortalPropertiesTest {

    @Test
    void dayforceBuildsPortalAndJobUrls() {
        DayforceProperties.DayforceCompany c = new DayforceProperties.DayforceCompany();
        c.setName("californiadairies");
        c.setTenant("cadry");
        c.setBoard("CANDIDATEPORTAL");
        assertThat(c.portalUrl())
                .isEqualTo("https://jobs.dayforcehcm.com/en-US/cadry/CANDIDATEPORTAL");
        assertThat(c.jobUrl("1606"))
                .isEqualTo("https://jobs.dayforcehcm.com/en-US/cadry/CANDIDATEPORTAL/jobs/1606");
        DayforceProperties p = new DayforceProperties();
        p.setCompanies(List.of(c));
        assertThat(p.findByName("californiadairies")).isPresent();
        assertThat(p.findByName("missing")).isEmpty();
    }

    @Test
    void paycomBuildsCareerPageUrl() {
        PaycomProperties.PaycomCompany c = new PaycomProperties.PaycomCompany();
        c.setName("haasautomation");
        c.setPortalId("PORTALKEY");
        assertThat(c.careerPageUrl())
                .isEqualTo(
                        "https://www.paycomonline.net/v4/ats/web.php/portal/PORTALKEY/career-page");
        PaycomProperties p = new PaycomProperties();
        p.setCompanies(List.of(c));
        assertThat(p.findByName("haasautomation")).isPresent();
        assertThat(p.findByName("missing")).isEmpty();
    }
}
