package com.github.jingyangyu.scmjobnotifier.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for SAP SuccessFactors "Career Site Builder" (CSB) career sites.
 *
 * <p>Unlike Workday/Greenhouse/Oracle, SuccessFactors exposes no public JSON API — the
 * authenticated OData API is gated behind per-tenant OAuth. The only public surface is the CSB
 * search page, which serves rendered HTML "job tiles" from {@code
 * https://{host}/tile-search-results/?q=..&startrow=N}. This adapter scrapes that HTML. Because it
 * is tenant-HTML (not a uniform API), each {@code host} must be individually verified — see {@code
 * SuccessFactorsScraper}.
 */
@ConfigurationProperties(prefix = "job.successfactors")
@Getter
@Setter
public class SuccessFactorsProperties {

    private List<SuccessFactorsCompany> companies = new ArrayList<>();

    /** Looks up a company config by name. */
    public Optional<SuccessFactorsCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single SuccessFactors CSB career site. */
    @Getter
    @Setter
    public static class SuccessFactorsCompany {
        private String name;

        /** CSB host, e.g. {@code jobs.sap.com}. The tile-search endpoint lives at its root. */
        private String host;

        /** Returns the tile-search URL for a keyword query and pagination offset. */
        public String searchUrl(String query, int startRow) {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
            return String.format(
                    "https://%s/tile-search-results/?q=%s&startrow=%d", host, q, startRow);
        }

        /** Turns a tile's relative {@code data-url} into an absolute job URL. */
        public String jobUrl(String dataUrl) {
            return "https://" + host + dataUrl;
        }
    }
}
