package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for UltiPro (UKG Pro Recruiting) job boards.
 *
 * <p>Each board lives at {@code recruiting{N}.ultipro.com/{tenant}/JobBoard/{boardId}} and backs
 * its search box with a public JSON endpoint ({@code JobBoardView/LoadSearchResults}) that takes a
 * query string plus Top/Skip paging — see {@link
 * com.github.jingyangyu.scmjobnotifier.scraper.UltiProScraper}. Both the tenant code (e.g. {@code
 * GRO1006}) and the board GUID are opaque, so they are read off the employer's careers link rather
 * than guessed; {@code host} distinguishes the {@code recruiting} and {@code recruiting2} pods.
 */
@ConfigurationProperties(prefix = "job.ultipro")
@Getter
@Setter
public class UltiProProperties {

    private List<UltiProCompany> companies = new ArrayList<>();

    /** Looks up a company config by name for scraper initialization. */
    public Optional<UltiProCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single UltiPro job board. */
    @Getter
    @Setter
    public static class UltiProCompany {
        private String name;

        /** Tenant code in the board URL (e.g. {@code GRO1006}, {@code SHA1012SHRO}). */
        private String tenant;

        /** The board GUID in the board URL. */
        private String boardId;

        /** Pod host: {@code recruiting} or {@code recruiting2} (defaults to the former). */
        private String host = "recruiting";

        /** The JSON search endpoint the scraper POSTs to. */
        public String searchUrl() {
            return String.format(
                    "https://%s.ultipro.com/%s/JobBoard/%s/JobBoardView/LoadSearchResults",
                    host, tenant, boardId);
        }

        /** The public detail-page URL for one opportunity. */
        public String jobUrl(String opportunityId) {
            return String.format(
                    "https://%s.ultipro.com/%s/JobBoard/%s/OpportunityDetail?opportunityId=%s",
                    host, tenant, boardId, opportunityId);
        }
    }
}
