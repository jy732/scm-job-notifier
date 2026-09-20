package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for ADP WorkforceNow career centers.
 *
 * <p>ADP runs two portals. The newer {@code myjobs.adp.com} SPA keeps its listing behind an
 * authenticated route, but the WorkforceNow career center backs its page with a public REST feed
 * ({@code /careercenter/public/events/staffing/v1/job-requisitions?cid=…}) that needs no browser —
 * see {@link com.github.jingyangyu.scmjobnotifier.scraper.AdpScraper}. The {@code cid} is the
 * opaque client GUID in the employer's careers link.
 */
@ConfigurationProperties(prefix = "job.adp")
@Getter
@Setter
public class AdpProperties {

    private List<AdpCompany> companies = new ArrayList<>();

    /** Looks up a company config by name for scraper initialization. */
    public Optional<AdpCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single WorkforceNow career center. */
    @Getter
    @Setter
    public static class AdpCompany {
        private String name;

        /** Client GUID from the careers link ({@code recruitment.html?cid=…}). */
        private String cid;

        /** One page of the public requisition feed. */
        public String feedUrl(int top, int skip) {
            return String.format(
                    "https://workforcenow.adp.com/mascsr/default/careercenter/public/events/"
                            + "staffing/v1/job-requisitions?cid=%s&lang=en_US&locale=en_US"
                            + "&$top=%d&$skip=%d",
                    cid, top, skip);
        }

        /** The public career-center page for one requisition. */
        public String jobUrl(String jobId) {
            return String.format(
                    "https://workforcenow.adp.com/mascsr/default/mdf/recruitment/recruitment.html"
                            + "?cid=%s&jobId=%s&lang=en_US",
                    cid, jobId);
        }
    }
}
