package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for Kenexa BrassRing (TGNewUI) career sites. */
@ConfigurationProperties(prefix = "job.brassring")
@Getter
@Setter
public class BrassRingProperties {

    private List<BrassRingCompany> companies = new ArrayList<>();

    /**
     * Finds a BrassRing company configuration by name.
     *
     * @param name the company name to look up
     * @return the matching company config, or empty if not found
     */
    public Optional<BrassRingCompany> findByName(String name) {
        return companies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    /** Configuration for a single BrassRing career board (a partner + one site). */
    @Getter
    @Setter
    public static class BrassRingCompany {
        private String name;
        private String partnerId;
        private String siteId;

        /**
         * QuestionName fields (in the job JSON) whose values, joined in order, form the location
         * string. BrassRing has <em>no</em> standard location field — each tenant maps it to custom
         * {@code formtext*} fields (e.g. General Atomics: {@code formtext5}=city, {@code
         * formtext4}=state). Leave empty to fall back to joining every {@code formtext*} value,
         * which guards against silent location-drops (see [[scraper-silent-location-drops]]) at the
         * cost of a slightly noisier display until the tenant's real fields are verified.
         */
        private List<String> locationFields = new ArrayList<>();

        /** Landing/search page URL that boots the TGNewUI Angular app for this board. */
        public String homeUrl() {
            return String.format(
                    "https://sjobs.brassring.com/TGnewUI/Search/Home/Home?partnerid=%s&siteid=%s",
                    partnerId, siteId);
        }
    }
}
