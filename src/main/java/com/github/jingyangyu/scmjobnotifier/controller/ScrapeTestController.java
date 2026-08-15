package com.github.jingyangyu.scmjobnotifier.controller;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.notification.EmailNotifier;
import com.github.jingyangyu.scmjobnotifier.scraper.JobScraper;
import com.github.jingyangyu.scmjobnotifier.service.JobPollingService;
import com.github.jingyangyu.scmjobnotifier.service.classification.JobTitleFilter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Test endpoints for triggering scrapers/poll on demand without waiting for the poll cycle.
 *
 * <p>Handlers run on {@link Schedulers#boundedElastic()} because the scrapers and Gemini client
 * call {@code WebClient.block()}, which is illegal on the WebFlux Netty event-loop thread. (The
 * scheduled poll is unaffected — it already runs scrapes on a dedicated thread pool.)
 *
 * <ul>
 *   <li>{@code POST /api/test/scrape/greenhouse/spacex} — scrape SpaceX via Greenhouse
 *   <li>{@code POST /api/test/scrape/workday/chipotle} — scrape Chipotle via Workday
 *   <li>{@code POST /api/test/scrape-all} — scrape all configured companies, return a summary
 *   <li>{@code POST /api/test/poll} — run one full poll cycle (scrape → filter → classify →
 *       persist)
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
public class ScrapeTestController {

    private final List<JobScraper> scrapers;
    private final JobPollingService pollingService;
    private final EmailNotifier emailNotifier;
    private final JobTitleFilter titleFilter;

    public ScrapeTestController(
            List<JobScraper> scrapers,
            JobPollingService pollingService,
            EmailNotifier emailNotifier,
            JobTitleFilter titleFilter) {
        this.scrapers = scrapers;
        this.pollingService = pollingService;
        this.emailNotifier = emailNotifier;
        this.titleFilter = titleFilter;
    }

    /** Hardcoded test recipient — deliberately NOT the configured (real) NOTIFICATION_EMAIL. */
    private static final String TEST_EMAIL_TO = "jy63@illinois.edu";

    /**
     * Sends a sample alert email (2 direct + 1 Adzuna posting) to the hardcoded test address
     * {@value #TEST_EMAIL_TO} — for testing SMTP + the template (Hello Kitty mascot + separate
     * Adzuna section) without a poll and without touching the real recipient. {@code POST
     * /api/test/email}.
     */
    @PostMapping("/email")
    public Mono<Map<String, Object>> testEmail() {
        return Mono.fromCallable(
                        () -> {
                            List<JobPosting> sample =
                                    List.of(
                                            JobPosting.builder()
                                                    .company("Anduril Industries")
                                                    .externalId("test-1")
                                                    .title("Supply Chain Coordinator")
                                                    .url("https://www.anduril.com/careers")
                                                    .location("Costa Mesa, CA")
                                                    .level("ENTRY_LEVEL")
                                                    .detectedAt(Instant.now())
                                                    .build(),
                                            JobPosting.builder()
                                                    .company("Rocket Lab")
                                                    .externalId("test-2")
                                                    .title("Materials Planner Intern")
                                                    .url("https://www.rocketlabusa.com/careers")
                                                    .location("Long Beach, CA")
                                                    .level("INTERNSHIP")
                                                    .detectedAt(Instant.now())
                                                    .build(),
                                            JobPosting.builder()
                                                    .company("DGN Technologies")
                                                    .externalId("test-3")
                                                    .title("Production Planner / Master Scheduler")
                                                    .url("https://example.com/job")
                                                    .location("Redlands, San Bernardino County")
                                                    .level("UNSURE")
                                                    .source("adzuna")
                                                    .detectedAt(Instant.now())
                                                    .build());
                            boolean sent = emailNotifier.sendTestAlert(sample, TEST_EMAIL_TO);
                            return Map.<String, Object>of(
                                    "sent", sent, "to", TEST_EMAIL_TO, "sampleJobs", sample.size());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/scrape/{platform}/{company}")
    public Mono<Map<String, Object>> scrapeSingle(
            @PathVariable String platform, @PathVariable String company) {
        return Mono.fromCallable(() -> doScrapeSingle(platform, company))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> doScrapeSingle(String platform, String company) {
        JobScraper scraper =
                scrapers.stream()
                        .filter(s -> s.platform().equalsIgnoreCase(platform))
                        .findFirst()
                        .orElse(null);

        if (scraper == null) {
            return Map.of(
                    "error",
                    "Unknown platform: " + platform,
                    "available",
                    scrapers.stream().map(JobScraper::platform).toList());
        }

        log.info("Test scrape: platform={}, company={}", platform, company);
        long start = System.currentTimeMillis();
        List<JobPosting> jobs = scraper.scrape(company);
        long elapsed = System.currentTimeMillis() - start;

        List<Map<String, String>> sample =
                jobs.stream()
                        .limit(8)
                        .map(
                                j ->
                                        Map.of(
                                                "title",
                                                j.getTitle(),
                                                "location",
                                                j.getLocation() != null ? j.getLocation() : ""))
                        .toList();

        return Map.of(
                "platform", platform,
                "company", company,
                "count", jobs.size(),
                "elapsedMs", elapsed,
                "sample", sample);
    }

    @PostMapping("/scrape-all")
    public Mono<Map<String, Object>> scrapeAll() {
        return Mono.fromCallable(this::doScrapeAll).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> doScrapeAll() {
        log.info("Test scrape-all triggered");
        Map<String, Object> results = new LinkedHashMap<>();
        long totalStart = System.currentTimeMillis();

        for (JobScraper scraper : scrapers) {
            for (String company : scraper.companies()) {
                String key = scraper.platform() + "/" + company;
                long start = System.currentTimeMillis();
                try {
                    List<JobPosting> jobs = scraper.scrape(company);
                    long elapsed = System.currentTimeMillis() - start;
                    results.put(key, Map.of("count", jobs.size(), "elapsedMs", elapsed));
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    results.put(
                            key, Map.of("count", 0, "elapsedMs", elapsed, "error", e.getMessage()));
                }
            }
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        results.put("_totalElapsedMs", totalElapsed);
        return results;
    }

    /**
     * Location-parsing audit: scrapes every configured company and writes one CSV row per job
     * (platform, company, caDetected, location, title) to {@code ./location-audit.csv}, plus a
     * per-company summary and a flag list. Surfaces tenants whose location parsing silently breaks —
     * e.g. many jobs scraped but all locations blank, or lots of jobs yet zero California detected
     * (the Snap-on / Workday-multi-location signatures). {@code POST /api/test/location-audit}.
     */
    @PostMapping("/location-audit")
    public Mono<Map<String, Object>> locationAudit() {
        return Mono.fromCallable(this::doLocationAudit).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> doLocationAudit() {
        log.info("Location audit triggered");
        Path csv = Path.of("location-audit.csv");
        Map<String, int[]> agg = new TreeMap<>(); // "platform/company" -> [jobs, blankLoc, caDetected]
        int totalJobs = 0;
        try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            w.write("platform,company,caDetected,location,title");
            w.newLine();
            for (JobScraper scraper : scrapers) {
                String platform = scraper.platform();
                for (String company : scraper.companies()) {
                    List<JobPosting> jobs;
                    try {
                        jobs = scraper.scrape(company);
                    } catch (Exception e) {
                        log.warn("audit: scrape failed {}/{}: {}", platform, company, e.getMessage());
                        continue;
                    }
                    int[] a = agg.computeIfAbsent(platform + "/" + company, k -> new int[3]);
                    for (JobPosting j : jobs) {
                        String loc = j.getLocation() == null ? "" : j.getLocation();
                        boolean ca = titleFilter.isCaliforniaLocation(j);
                        a[0]++;
                        if (loc.isBlank()) {
                            a[1]++;
                        }
                        if (ca) {
                            a[2]++;
                        }
                        totalJobs++;
                        // Real employer per job (j.getCompany()), not the config/loop company —
                        // for synthetic multi-employer scrapers (adzuna, amazon, apple) the loop
                        // company is a placeholder, so use it only as a fallback.
                        String employer =
                                j.getCompany() == null || j.getCompany().isBlank()
                                        ? company
                                        : j.getCompany();
                        w.write(
                                csvCell(platform)
                                        + ","
                                        + csvCell(employer)
                                        + ","
                                        + (ca ? "1" : "0")
                                        + ","
                                        + csvCell(loc)
                                        + ","
                                        + csvCell(j.getTitle() == null ? "" : j.getTitle()));
                        w.newLine();
                    }
                }
            }
        } catch (IOException e) {
            return Map.of("error", e.getMessage());
        }
        // Flag the two silent-drop signatures for human review.
        List<String> parseLikelyBroken = new java.util.ArrayList<>();
        List<String> zeroCaWorthChecking = new java.util.ArrayList<>();
        for (Map.Entry<String, int[]> e : agg.entrySet()) {
            int jobs = e.getValue()[0], blank = e.getValue()[1], ca = e.getValue()[2];
            if (jobs >= 10 && blank * 2 > jobs) {
                parseLikelyBroken.add(e.getKey() + " — " + blank + "/" + jobs + " blank locations");
            } else if (jobs >= 20 && ca == 0) {
                zeroCaWorthChecking.add(e.getKey() + " — " + jobs + " jobs, 0 CA");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("csv", csv.toAbsolutePath().toString());
        out.put("totalJobs", totalJobs);
        out.put("companies", agg.size());
        out.put("parseLikelyBroken", parseLikelyBroken);
        out.put("zeroCaWorthChecking", zeroCaWorthChecking);
        return out;
    }

    /** CSV cell: quote-wrapped, embedded quotes doubled, newlines flattened. */
    private static String csvCell(String s) {
        return "\"" + s.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\"";
    }

    /**
     * End-to-end pre-filter audit: scrapes every company and records, per job, the outcome at each
     * pipeline stage (freshness, exclude tier, California, SCM-relevance, auto-level) plus the final
     * {@code disposition} — which stage dropped it, or {@code PASSED} (would reach Gemini/email).
     * Writes {@code filter-audit.csv} and returns per-disposition counts. Deterministic (no Gemini
     * calls); the Gemini stage is audited separately from its persisted decisions in H2. {@code POST
     * /api/test/filter-audit}.
     */
    @PostMapping("/filter-audit")
    public Mono<Map<String, Object>> filterAudit() {
        return Mono.fromCallable(this::doFilterAudit).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> doFilterAudit() {
        log.info("Filter audit triggered");
        Path csv = Path.of("filter-audit.csv");
        Map<String, Integer> byDisposition = new TreeMap<>();
        int total = 0;
        try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            w.write(
                    "platform,company,disposition,excludeReason,fresh,california,scmRelevant,"
                            + "autoLevel,location,title");
            w.newLine();
            for (JobScraper scraper : scrapers) {
                String platform = scraper.platform();
                for (String company : scraper.companies()) {
                    List<JobPosting> jobs;
                    try {
                        jobs = scraper.scrape(company);
                    } catch (Exception e) {
                        log.warn(
                                "filter-audit: scrape failed {}/{}: {}",
                                platform,
                                company,
                                e.getMessage());
                        continue;
                    }
                    for (JobPosting j : jobs) {
                        boolean fresh = titleFilter.isFresh(j);
                        String reason = titleFilter.excludeReason(j);
                        boolean ca = titleFilter.isCaliforniaLocation(j);
                        boolean scm = titleFilter.isScmRelevant(j);
                        String autoLevel = titleFilter.autoClassifyLevel(j);
                        String disposition;
                        if (!fresh) {
                            disposition = "DROPPED_STALE";
                        } else if (reason != null) {
                            disposition =
                                    "DROPPED_" + reason.toUpperCase(Locale.ROOT).replace('-', '_');
                        } else if (!ca) {
                            disposition = "DROPPED_NON_CA";
                        } else if (!scm) {
                            disposition = "DROPPED_NON_SCM";
                        } else {
                            disposition = "PASSED";
                        }
                        byDisposition.merge(disposition, 1, Integer::sum);
                        total++;
                        String employer =
                                j.getCompany() == null || j.getCompany().isBlank()
                                        ? company
                                        : j.getCompany();
                        w.write(
                                csvCell(platform)
                                        + ","
                                        + csvCell(employer)
                                        + ","
                                        + csvCell(disposition)
                                        + ","
                                        + csvCell(reason == null ? "" : reason)
                                        + ","
                                        + (fresh ? "1" : "0")
                                        + ","
                                        + (ca ? "1" : "0")
                                        + ","
                                        + (scm ? "1" : "0")
                                        + ","
                                        + csvCell(autoLevel == null ? "" : autoLevel)
                                        + ","
                                        + csvCell(j.getLocation() == null ? "" : j.getLocation())
                                        + ","
                                        + csvCell(j.getTitle() == null ? "" : j.getTitle()));
                        w.newLine();
                    }
                }
            }
        } catch (IOException e) {
            return Map.of("error", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("csv", csv.toAbsolutePath().toString());
        out.put("totalJobs", total);
        out.put("byDisposition", byDisposition);
        return out;
    }

    /**
     * Runs one full poll cycle synchronously (off the event loop) for manual end-to-end testing.
     */
    @PostMapping("/poll")
    public Mono<Map<String, Object>> triggerPoll() {
        return Mono.fromCallable(
                        () -> {
                            long start = System.currentTimeMillis();
                            pollingService.poll();
                            return Map.<String, Object>of(
                                    "status",
                                    "poll complete",
                                    "elapsedMs",
                                    System.currentTimeMillis() - start);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
