package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Radancy (TalentBrew) career sites.
 *
 * <p>Only the {@code host} is configured — the search endpoint path, the row markup and the
 * pagination metadata are identical across TalentBrew tenants, so a new employer is one config
 * entry. See {@code RadancyScraper} for the response shape.
 */
@ConfigurationProperties(prefix = "job.radancy")
@Getter
@Setter
public class RadancyProperties {

    private List<RadancyCompany> companies = new ArrayList<>();

    /** Looks up a company config by name. */
    public Optional<RadancyCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single Radancy/TalentBrew career site. */
    @Getter
    @Setter
    public static class RadancyCompany {
        private String name;

        /** Career-site host, e.g. {@code jobs.boeing.com}. */
        private String host;

        /**
         * The AJAX search endpoint for one page of the whole board.
         *
         * <p>The parameter set is not optional window dressing: dropping {@code CustomFacetName} /
         * {@code FacetTerm} / {@code FacetType} / the two {@code *ModuleName} params makes
         * TalentBrew return <em>zero</em> rows rather than an error, so the full set is sent
         * verbatim. {@code Keywords} and {@code Location} stay empty — the keyword search is fuzzy
         * and the location filter is only a relevance boost (on Boeing it let Australia and Qatar
         * rows through a {@code Location=California} query), so we scan the board and let the
         * downstream California + SCM pre-filters decide.
         */
        public String searchUrl(int page, int pageSize) {
            return "https://"
                    + host
                    + "/search-jobs/results?ActiveFacetID=0"
                    + "&CurrentPage="
                    + page
                    + "&RecordsPerPage="
                    + pageSize
                    + "&Distance=50&RadiusUnitType=0&Keywords=&Location="
                    + "&ShowRadius=False&IsPagination=False&CustomFacetName=&FacetTerm="
                    + "&FacetType=0&SearchResultsModuleName=Search+Results"
                    + "&SearchFiltersModuleName=Search+Filters&SortCriteria=0&SortDirection=0"
                    + "&SearchType=5&PostalCode=&fc=&fl=&fcf=&afc=&afl=&afcf=";
        }

        /** The public search page, used as the {@code Referer} for the AJAX endpoint. */
        public String searchPageUrl() {
            return "https://" + host + "/search-jobs";
        }

        /** Resolves a row's root-relative {@code /job/...} href to an absolute detail URL. */
        public String jobUrl(String href) {
            return "https://" + host + href;
        }
    }
}
