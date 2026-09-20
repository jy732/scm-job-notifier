package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Paycom career pages.
 *
 * <p>Each employer's board is a React app at {@code
 * paycomonline.net/v4/ats/web.php/portal/{portalId}/career-page}; the portal id is the opaque hex
 * key from the employer's careers link. The app ships no job API (its bundle exposes UI routes
 * only), so {@link com.github.jingyangyu.scmjobnotifier.scraper.PaycomScraper} drives the rendered
 * board with Playwright.
 */
@ConfigurationProperties(prefix = "job.paycom")
@Getter
@Setter
public class PaycomProperties {

    private List<PaycomCompany> companies = new ArrayList<>();

    /** Looks up a company config by name for scraper initialization. */
    public Optional<PaycomCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single Paycom career page. */
    @Getter
    @Setter
    public static class PaycomCompany {
        private String name;

        /** Opaque portal key, e.g. {@code D30AA53E68D47C00010BA94FEF731F65}. */
        private String portalId;

        /** The career-page URL the scraper drives. */
        public String careerPageUrl() {
            return String.format(
                    "https://www.paycomonline.net/v4/ats/web.php/portal/%s/career-page", portalId);
        }
    }
}
