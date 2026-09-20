package com.github.jingyangyu.scmjobnotifier.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for JazzHR job boards.
 *
 * <p>JazzHR boards live at {@code {subdomain}.applytojob.com/apply/} and server-render every open
 * role as a list item (title link + "City, ST"), so a single page fetch per employer is enough —
 * see {@link com.github.jingyangyu.scmjobnotifier.scraper.JazzHrScraper}. Only the subdomain is
 * needed, and it is visible in the employer's own apply link, so this is a plain CSV list rather
 * than a per-company block.
 */
@ConfigurationProperties(prefix = "job.jazzhr")
@Getter
@Setter
public class JazzHrProperties {

    /** Board subdomains, e.g. {@code esaero} for {@code esaero.applytojob.com}. */
    private List<String> companies = new ArrayList<>();
}
