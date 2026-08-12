package com.github.jingyangyu.scmjobnotifier.controller;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.notification.EmailNotifier;
import com.github.jingyangyu.scmjobnotifier.scraper.JobScraper;
import com.github.jingyangyu.scmjobnotifier.service.JobPollingService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public ScrapeTestController(
            List<JobScraper> scrapers,
            JobPollingService pollingService,
            EmailNotifier emailNotifier) {
        this.scrapers = scrapers;
        this.pollingService = pollingService;
        this.emailNotifier = emailNotifier;
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
