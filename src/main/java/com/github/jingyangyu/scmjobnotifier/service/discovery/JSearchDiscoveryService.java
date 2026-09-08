package com.github.jingyangyu.scmjobnotifier.service.discovery;

import com.github.jingyangyu.scmjobnotifier.scraper.JobScraper;
import com.github.jingyangyu.scmjobnotifier.service.discovery.JSearchClient.DiscoveredJob;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodic <em>discovery</em> sweep: queries JSearch for SCM/CA roles, drops employers we already
 * scrape directly and staffing agencies, and ranks the remaining <b>net-new real employers</b> as
 * direct-scraper migration candidates. Never emits alerts — this only proposes companies to add.
 *
 * <p>Deliberately not a live alert source (see the design note): JSearch overlaps ~50% with Adzuna,
 * is quota-limited, and lower-quality than direct boards. Its unique value is broad employer
 * discovery, which this captures.
 */
@Slf4j
@Service
public class JSearchDiscoveryService {

    /**
     * Precise staffing/recruiting signal — curated agencies + unambiguous tokens (no tech words).
     */
    private static final Pattern STAFFING =
            Pattern.compile(
                    "\\b(staffing|recruit|talent(s| solutions| by)|consulting group|confidential)\\b"
                            + "|aston carter|robert half|teksystems|insight global|randstad|adecco|aerotek"
                            + "|actalent|kelly services|cynet|collabera|prolim|integrated resources|ledgent"
                            + "|dsj global|kforce|beacon hill|judge group|mindlance|diverse lynx|apex systems"
                            + "|russell tobin|system one|gpac|rose international|22nd century|dgn technolog"
                            + "|woongjin|net2source|compunnel|iconma|artech|nityo|mastech|motion recruitment"
                            + "|cybercoders|jobot|vaco|synergis|pinnacle group|strategic placements"
                            + "|navigate search|software services",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern SUFFIX =
            Pattern.compile(
                    "\\b(inc|llc|corp|corporation|co|company|ltd|the|group|holdings|usa|international)\\b",
                    Pattern.CASE_INSENSITIVE);

    private final JSearchClient client;
    private final List<JobScraper> scrapers;
    private final List<String> queries;

    public JSearchDiscoveryService(
            JSearchClient client,
            List<JobScraper> scrapers,
            @Value("${jsearch.discovery.queries:}") String queriesRaw) {
        this.client = client;
        this.scrapers = scrapers;
        this.queries = parseQueries(queriesRaw);
    }

    /** Weekly by default; throttled to respect JSearch's request quota. */
    @Scheduled(cron = "${jsearch.discovery.cron:0 0 6 * * SUN}")
    public void scheduledDiscovery() {
        if (!client.isConfigured() || queries.isEmpty()) {
            log.info(
                    "JSearch discovery skipped (configured={}, queries={})",
                    client.isConfigured(),
                    queries.size());
            return;
        }
        DiscoveryReport r = discover();
        log.info(
                "=== JSearch DISCOVERY === sampled={}, alreadyCovered={}, staffing={}, NET-NEW={}",
                r.sampled(),
                r.coveredCount(),
                r.staffingCount(),
                r.candidates().size());
        r.candidates()
                .forEach(
                        c ->
                                log.info(
                                        "  candidate: {}x  {}  | {} | {}",
                                        c.roles(),
                                        c.employer(),
                                        c.sampleTitle(),
                                        c.sampleLocation()));
    }

    /** Runs the sweep and returns a ranked report (no side effects). */
    public DiscoveryReport discover() {
        Set<String> covered = coveredEmployers();
        Set<String> seen = new HashSet<>();
        Map<String, Agg> byEmployer = new LinkedHashMap<>();
        int sampled = 0;
        int coveredCount = 0;
        int staffingCount = 0;

        for (String q : queries) {
            for (DiscoveredJob j : client.search(q)) {
                sampled++;
                String nm = norm(j.employer());
                if (nm.isEmpty() || !seen.add(nm + "|" + j.title().toLowerCase())) {
                    continue; // blank or duplicate (employer,title) across queries
                }
                if (isCovered(nm, covered)) {
                    coveredCount++;
                    continue;
                }
                if (STAFFING.matcher(j.employer()).find()) {
                    staffingCount++;
                    continue;
                }
                byEmployer.computeIfAbsent(nm, k -> new Agg(j.employer(), j.title(), location(j)))
                        .roles++;
            }
        }

        List<Candidate> ranked =
                byEmployer.values().stream()
                        .sorted(Comparator.comparingInt((Agg a) -> a.roles).reversed())
                        .map(
                                a ->
                                        new Candidate(
                                                a.employer,
                                                a.roles,
                                                a.sampleTitle,
                                                a.sampleLocation))
                        .toList();
        return new DiscoveryReport(sampled, coveredCount, staffingCount, ranked);
    }

    /**
     * Normalized names of every employer we already scrape directly (excludes the Adzuna source).
     */
    private Set<String> coveredEmployers() {
        Set<String> covered = new HashSet<>();
        for (JobScraper sc : scrapers) {
            if ("adzuna".equals(sc.platform())) {
                continue;
            }
            for (String c : sc.companies()) {
                covered.add(norm(c));
            }
        }
        return covered;
    }

    private static boolean isCovered(String nm, Set<String> covered) {
        if (covered.contains(nm)) {
            return true;
        }
        for (String c : covered) {
            if (c.length() >= 6 && nm.length() >= 6 && (nm.contains(c) || c.contains(nm))) {
                return true;
            }
        }
        return false;
    }

    private static String location(DiscoveredJob j) {
        return (j.city() + " " + j.state()).trim();
    }

    private static String norm(String s) {
        String x = SUFFIX.matcher(s == null ? "" : s.toLowerCase()).replaceAll("");
        return x.replaceAll("[^a-z0-9]", "");
    }

    private static List<String> parseQueries(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String q : raw.split(";")) {
                if (!q.isBlank()) {
                    out.add(q.trim());
                }
            }
        }
        return out;
    }

    /** Mutable per-employer accumulator during a sweep. */
    private static final class Agg {
        private final String employer;
        private final String sampleTitle;
        private final String sampleLocation;
        private int roles;

        Agg(String employer, String sampleTitle, String sampleLocation) {
            this.employer = employer;
            this.sampleTitle = sampleTitle;
            this.sampleLocation = sampleLocation;
        }
    }

    /** A ranked migration candidate. */
    public record Candidate(
            String employer, int roles, String sampleTitle, String sampleLocation) {}

    /** Result of a discovery sweep. */
    public record DiscoveryReport(
            int sampled, int coveredCount, int staffingCount, List<Candidate> candidates) {}
}
