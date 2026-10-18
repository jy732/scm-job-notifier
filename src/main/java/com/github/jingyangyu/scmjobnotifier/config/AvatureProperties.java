package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Avature career portals.
 *
 * <p>Only the {@code host} is configured: everything else the list endpoint needs (portal id, list
 * uuid, {@code qtvc} token, {@code links} blob, {@code formId}, {@code searchIndexId}) is
 * discovered at scrape time from the search page — see {@code AvatureScraper}.
 *
 * <p><strong>Applies to the JSON {@code _portalList} portal flavour only.</strong> Some Avature
 * tenants instead server-render their job list with no JSON endpoint at all (verified on {@code
 * synopsys.avature.net}: no XHR, 6 jobs/page with the page-size param ignored, and rows carrying
 * neither location nor description). Those are <em>not</em> supported here — adding one means a
 * second parser, so confirm a tenant exposes {@code <list data-props=...>} before configuring it.
 */
@ConfigurationProperties(prefix = "job.avature")
@Getter
@Setter
public class AvatureProperties {

    private List<AvatureCompany> companies = new ArrayList<>();

    /** Looks up a company config by name. */
    public Optional<AvatureCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single Avature career portal. */
    @Getter
    @Setter
    public static class AvatureCompany {
        private String name;

        /** Portal host, e.g. {@code avature.sandisk.com}. */
        private String host;

        /** The public job-search page, which seeds the cookie and the list widget config. */
        public String searchUrl() {
            return "https://" + host + "/Careers/SearchJobs";
        }

        /** The JSON list endpoint, namespaced by the tenant's portal id. */
        public String listUrl(String portalId) {
            return "https://" + host + "/" + portalId + "/_portalList";
        }

        /**
         * Builds a job detail URL. Avature's canonical form is {@code
         * /Careers/JobDetail/{slug}/{id}} but the slug is decorative — the id alone resolves, so a
         * fixed placeholder avoids depending on tenant slug rules.
         */
        public String jobUrl(String jobId) {
            return "https://" + host + "/Careers/JobDetail/job/" + jobId;
        }
    }
}
