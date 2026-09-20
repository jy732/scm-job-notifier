package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Dayforce (Ceridian) candidate portals.
 *
 * <p>Each portal lives at {@code jobs.dayforcehcm.com/en-US/{tenant}/{board}}; both segments come
 * straight off the employer's careers link (e.g. {@code cadry} / {@code CANDIDATEPORTAL}). The
 * board's data endpoint is WAF-protected, so {@link
 * com.github.jingyangyu.scmjobnotifier.scraper.DayforceScraper} drives it with Playwright.
 */
@ConfigurationProperties(prefix = "job.dayforce")
@Getter
@Setter
public class DayforceProperties {

    private List<DayforceCompany> companies = new ArrayList<>();

    /** Looks up a company config by name for scraper initialization. */
    public Optional<DayforceCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single Dayforce candidate portal. */
    @Getter
    @Setter
    public static class DayforceCompany {
        private String name;

        /** Tenant segment, Dayforce's {@code clientNamespace} (e.g. {@code cadry}). */
        private String tenant;

        /** Job-board segment, Dayforce's {@code jobBoardCode} (e.g. {@code CANDIDATEPORTAL}). */
        private String board;

        /** The portal landing page — loading it is what mints the CSRF token. */
        public String portalUrl() {
            return String.format("https://jobs.dayforcehcm.com/en-US/%s/%s", tenant, board);
        }

        /** The public detail-page URL for one posting. */
        public String jobUrl(String jobPostingId) {
            return String.format(
                    "https://jobs.dayforcehcm.com/en-US/%s/%s/jobs/%s",
                    tenant, board, jobPostingId);
        }
    }
}
