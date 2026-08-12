package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for iCIMS company career portals. */
@ConfigurationProperties(prefix = "job.icims")
@Getter
@Setter
public class IcimsProperties {

    private List<IcimsCompany> companies = new ArrayList<>();

    /** Looks up a company config by name for scraper initialization. */
    public Optional<IcimsCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single iCIMS company career portal. */
    @Getter
    @Setter
    public static class IcimsCompany {
        private String name;

        /** The portal subdomain, e.g. "careers-booz" for careers-booz.icims.com. */
        private String subdomain;

        /**
         * Optional custom domain override (e.g. "careers.company.com"). If set, used instead of
         * subdomain.
         */
        private String customDomain;

        /** Returns the base URL for this iCIMS career portal. */
        public String baseUrl() {
            if (customDomain != null && !customDomain.isBlank()) {
                return "https://" + customDomain;
            }
            return String.format("https://%s.icims.com", subdomain);
        }

        /**
         * Returns the legacy job-search fragment URL for a page. iCIMS {@code pr} is a 0-indexed
         * page number (~50 jobs/page); {@code in_iframe=1} returns the compact server-rendered list
         * we parse over plain HTTP (no browser needed).
         */
        public String searchUrl(int page) {
            return String.format("%s/jobs/search?pr=%d&in_iframe=1", baseUrl(), page);
        }

        /** Returns the public detail-page URL for a specific job. */
        public String jobUrl(String jobId, String slug) {
            return String.format("%s/jobs/%s/%s/job", baseUrl(), jobId, slug);
        }
    }
}
