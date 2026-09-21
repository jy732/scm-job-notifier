package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for ADP's newer {@code myjobs.adp.com} career sites.
 *
 * <p>Distinct from {@link AdpProperties}, which covers the older WorkforceNow career center. A
 * myJobs board is addressed by its domain segment (e.g. {@code boottbarnext} in {@code
 * myjobs.adp.com/boottbarnext/cx}) — see {@link
 * com.github.jingyangyu.scmjobnotifier.scraper.AdpMyJobsScraper}.
 */
@ConfigurationProperties(prefix = "job.adpmyjobs")
@Getter
@Setter
public class AdpMyJobsProperties {

    private List<AdpMyJobsCompany> companies = new ArrayList<>();

    /** Looks up a company config by name for scraper initialization. */
    public Optional<AdpMyJobsCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single myJobs career site. */
    @Getter
    @Setter
    public static class AdpMyJobsCompany {
        private String name;

        /** Career-site domain segment, e.g. {@code boottbarnext}. */
        private String domain;

        /** Public career-site config — this is where the myJobs API token comes from. */
        public String careerSiteUrl() {
            return "https://myjobs.adp.com/public/staffing/v1/career-site/" + domain;
        }

        /** The public detail-page URL for one requisition. */
        public String jobUrl(String reqId) {
            return String.format(
                    "https://myjobs.adp.com/%s/cx/job-details?reqId=%s", domain, reqId);
        }
    }
}
