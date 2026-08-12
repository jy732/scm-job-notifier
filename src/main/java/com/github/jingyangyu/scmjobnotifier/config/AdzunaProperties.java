package com.github.jingyangyu.scmjobnotifier.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Adzuna aggregator source. Adzuna indexes postings across every ATS/board,
 * so it acts as a "long-tail net" for California SCM roles at employers we don't scrape directly.
 * It is throttled (own cadence, not per-poll) to stay within the free API tier, and excludes
 * employers already covered by the direct scrapers.
 */
@ConfigurationProperties(prefix = "job.adzuna")
@Getter
@Setter
public class AdzunaProperties {

    /** Master switch. Also implicitly disabled if appId/appKey are blank. */
    private boolean enabled = true;

    private String appId;
    private String appKey;

    /** Minimum minutes between live Adzuna fetches (self-throttle across polls). */
    private int throttleMinutes = 60;

    /** Recency window and pagination — kept small to respect the ~250 calls/day free tier. */
    private int maxDaysOld = 30;

    private int pages = 1;
    private String country = "us";

    /** SCM title phrases queried against {@code where=California}. */
    private List<String> queries =
            List.of(
                    "supply chain",
                    "procurement",
                    "logistics coordinator",
                    "demand planner",
                    "materials planner",
                    "buyer",
                    "inventory analyst",
                    "sourcing analyst");

    public boolean isConfigured() {
        return enabled && appId != null && !appId.isBlank() && appKey != null && !appKey.isBlank();
    }
}
