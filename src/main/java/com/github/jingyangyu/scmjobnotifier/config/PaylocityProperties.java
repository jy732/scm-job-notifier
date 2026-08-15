package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Paylocity recruiting job boards. Paylocity has no public JSON API
 * (its API endpoints 302-redirect), but the jobs page server-renders a {@code "Jobs":[...]} JSON
 * array we parse over plain HTTP — the same static-HTML approach as the iCIMS/SuccessFactors
 * scrapers, just with clean JSON. Common among CA mid-size employers (O'Neill Vintners, Bay Cities
 * Container).
 */
@ConfigurationProperties(prefix = "job.paylocity")
@Getter
@Setter
public class PaylocityProperties {

    private List<PaylocityCompany> companies = new ArrayList<>();

    /** Looks up a company config by name for scraper initialization. */
    public Optional<PaylocityCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single Paylocity recruiting board. */
    @Getter
    @Setter
    public static class PaylocityCompany {
        private String name;

        /** The opaque company GUID in the board URL (unguessable — found via WebSearch). */
        private String companyId;

        /** The URL slug segment (e.g. "ONeill-Vintners-and-Distillers"); cosmetic, aids the job URL. */
        private String slug;

        /** The board URL whose server-rendered HTML embeds the {@code "Jobs":[...]} JSON array. */
        public String jobsUrl() {
            return String.format(
                    "https://recruiting.paylocity.com/recruiting/jobs/All/%s/%s", companyId, slug);
        }

        /** The public detail-page URL for a specific job. */
        public String jobUrl(long jobId) {
            return String.format(
                    "https://recruiting.paylocity.com/recruiting/jobs/Details/%d/%s", jobId, slug);
        }
    }
}
