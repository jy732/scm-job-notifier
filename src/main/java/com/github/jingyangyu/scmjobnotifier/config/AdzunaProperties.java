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

    /**
     * SCM title phrases queried against {@code where=California} (exact-phrase each). Mirrors the
     * Stage-1 entry/intern title taxonomy (see technical-design.html Appendix A) across every SCM
     * family, with an entry-level skew: since we only pull the newest ~50 per phrase, a specific
     * phrase ("supply chain coordinator") surfaces more entry roles than a broad one ("supply
     * chain", whose newest 50 skew senior → filtered out). The two coordinator/assistant patterns
     * repeat across families because those are the reliable entry markers. Budget: 30 phrases × 1
     * page every {@link #throttleMinutes}=240 min (6 runs/day) ≈ 180 calls/day, under the ~250 free
     * tier.
     */
    private List<String> queries =
            List.of(
                    // supply chain (broad catches rotational/leadership/development programs +
                    // new-grad)
                    "supply chain",
                    "supply chain coordinator",
                    "supply chain analyst",
                    "supply chain intern",
                    // logistics / operations / transportation
                    "logistics coordinator",
                    "logistics analyst",
                    "operations coordinator",
                    "operations analyst",
                    "transportation coordinator",
                    // procurement / sourcing / purchasing / buying
                    "procurement coordinator",
                    "procurement specialist",
                    "procurement assistant",
                    "purchasing coordinator",
                    "purchasing assistant",
                    "purchasing agent",
                    "sourcing specialist",
                    "associate buyer",
                    "assistant buyer",
                    // planning & scheduling
                    "demand planner",
                    "supply planner",
                    "production planner",
                    "materials planner",
                    "master scheduler",
                    "planning analyst",
                    // inventory
                    "inventory analyst",
                    "inventory coordinator",
                    // warehouse / fulfillment / distribution
                    "warehouse coordinator",
                    "fulfillment coordinator",
                    "distribution coordinator",
                    // retail / merchandising SCM
                    "merchandise planner");

    public boolean isConfigured() {
        return enabled && appId != null && !appId.isBlank() && appKey != null && !appKey.isBlank();
    }
}
