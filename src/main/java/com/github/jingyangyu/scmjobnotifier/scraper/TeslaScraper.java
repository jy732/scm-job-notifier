package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.TeslaProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for Tesla Careers, via Bright Data's Web Unlocker.
 *
 * <p>Tesla serves its whole board in one JSON call — {@code /cua-api/apps/careers/state} returns
 * ~7.8k listings plus a {@code lookup} dictionary — but that endpoint is guarded by Akamai Bot
 * Manager and an app-level {@code cpr_chlge} challenge that rejects direct HTTP clients and every
 * Playwright mode we tried ("Access Denied"). We therefore fetch it through the Web Unlocker, which
 * mints Akamai-valid cookies and returns the JSON, then hand the payload to {@link
 * TeslaStateParser}. The whole board comes back in one request, so unlike the old per-query DOM
 * scrape this yields canonical Tesla ids (dupe-clean) and complete CA coverage.
 *
 * <p>The Web Unlocker occasionally returns the {@code cpr_chlge} stub (tiny/empty) instead of the
 * board when its rotating IP hasn't solved the challenge; a genuine board is ~1.5 MB. We retry,
 * with spacing (back-to-back calls get throttled), until the body actually contains {@code
 * listings}. Fetching is throttled to at most hourly since Tesla doesn't post fast and to stay
 * within the Web Unlocker free-credit allowance. No-op when unconfigured.
 */
@Slf4j
@Component
public class TeslaScraper implements JobScraper {

    private static final String STATE_URL = "https://www.tesla.com/cua-api/apps/careers/state";
    private static final String UNLOCKER_API = "https://api.brightdata.com/request";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(8);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    /** A real board is ~1.5 MB; the challenge stub / page shell is far smaller. */
    private static final int MIN_BOARD_BYTES = 100_000;

    /** Tesla doesn't post fast — fetch at most this often to conserve Web Unlocker credits. */
    private static final Duration MIN_INTERVAL = Duration.ofMinutes(55);

    private final WebClient webClient;
    private final TeslaProperties props;
    private volatile Instant lastFetch = Instant.EPOCH;

    public TeslaScraper(WebClient.Builder webClientBuilder, TeslaProperties props) {
        // The board is ~1.5 MB, well over WebClient's default 256 KB buffer — lift it so the
        // response isn't truncated into a parse failure.
        this.webClient =
                webClientBuilder
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        this.props = props;
        log.info(
                "Tesla scraper initialized (Bright Data Web Unlocker, configured={})",
                props.isConfigured());
    }

    @Override
    public String platform() {
        return "tesla";
    }

    @Override
    public List<String> companies() {
        return List.of("tesla");
    }

    @Override
    public List<JobPosting> scrape(String company) {
        if (!props.isConfigured()) {
            return List.of();
        }
        if (Duration.between(lastFetch, Instant.now()).compareTo(MIN_INTERVAL) < 0) {
            log.info("Tesla: throttled (fetched < {} min ago), skipping", MIN_INTERVAL.toMinutes());
            return List.of();
        }
        String board = fetchBoard();
        if (board == null) {
            log.warn("Tesla: Web Unlocker returned no board after {} attempts", MAX_ATTEMPTS);
            return List.of();
        }
        lastFetch = Instant.now();
        List<JobPosting> jobs = TeslaStateParser.parse(board);
        log.info("Tesla: scraped {} job(s) via Web Unlocker", jobs.size());
        return jobs;
    }

    /** POSTs to the Web Unlocker, retrying (spaced) past the {@code cpr_chlge} empties. */
    private String fetchBoard() {
        String body =
                "{\"zone\":\""
                        + props.getBrightdataZone()
                        + "\",\"url\":\""
                        + STATE_URL
                        + "\",\"format\":\"raw\"}";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String resp =
                        webClient
                                .post()
                                .uri(UNLOCKER_API)
                                .header("Authorization", "Bearer " + props.getBrightdataToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(body)
                                .retrieve()
                                .bodyToMono(String.class)
                                .block(CALL_TIMEOUT);
                if (resp != null
                        && resp.length() >= MIN_BOARD_BYTES
                        && resp.contains("\"listings\"")) {
                    return resp;
                }
                log.debug(
                        "Tesla: attempt {}/{} got {} bytes (challenge), retrying",
                        attempt,
                        MAX_ATTEMPTS,
                        resp == null ? 0 : resp.length());
            } catch (Exception e) {
                log.debug("Tesla: attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, e.getMessage());
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }
}
