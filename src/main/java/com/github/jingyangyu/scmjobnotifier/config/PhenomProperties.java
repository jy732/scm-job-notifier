package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Phenom People career sites.
 *
 * <p>Phenom hosts each employer on its own domain ({@code careers.ppg.com}, {@code
 * careers.onelineage.com}, …) but every site shares one shape: {@code
 * /us/en/search-results?keywords=…} server-renders a {@code phApp.ddo} blob holding the result
 * page. Only the host is needed — see {@link
 * com.github.jingyangyu.scmjobnotifier.scraper.PhenomScraper}.
 *
 * <p>Note {@link com.github.jingyangyu.scmjobnotifier.scraper.PaloAltoNetworksScraper} stays
 * separate: that tenant serves an older markup-only variant with no {@code phApp.ddo} blob.
 */
@ConfigurationProperties(prefix = "job.phenom")
@Getter
@Setter
public class PhenomProperties {

    private List<PhenomCompany> companies = new ArrayList<>();

    /** Configuration for a single Phenom-hosted career site. */
    @Getter
    @Setter
    public static class PhenomCompany {
        private String name;

        /** Career-site host, e.g. {@code careers.ppg.com} (no scheme, no trailing slash). */
        private String host;

        /** Locale path segment; nearly every US site uses {@code us/en}. */
        private String localePath = "us/en";

        /** The search-results URL for one keyword and result offset. */
        public String searchUrl(String encodedKeyword, int from) {
            return String.format(
                    "https://%s/%s/search-results?keywords=%s&from=%d&s=1",
                    host, localePath, encodedKeyword, from);
        }
    }
}
