package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.DayforceProperties;
import com.github.jingyangyu.scmjobnotifier.config.DayforceProperties.DayforceCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for Dayforce (Ceridian) candidate portals ({@code jobs.dayforcehcm.com}).
 *
 * <p>The portal's data endpoint ({@code POST /api/geo/{tenant}/jobposting/search}) is WAF-gated: it
 * answers 403 to plain HTTP, to a session-cookie request, and even to an in-page {@code fetch} —
 * because the portal's own calls carry an {@code x-csrf-token} minted while the page boots. So this
 * scraper opens the portal with Playwright, <b>captures that token off the app's first request</b>,
 * then runs the paging itself with an in-page {@code fetch} that replays the token. Paging the API
 * directly (rather than clicking through the UI) keeps it to one request per 25 postings.
 *
 * <p>The response carries everything the pipeline needs — title, description, posting timestamp and
 * a {@code postingLocations} array with city/state — so there is no detail round-trip. Boards here
 * are small (tens of roles), so the whole board is pulled and filtered downstream rather than run
 * through per-keyword searches.
 */
@Slf4j
@Component
public class DayforceScraper implements JobScraper {

    /** Rows per page; matches the portal's own pagination step. */
    private static final int PAGE_SIZE = 25;

    /** Safety cap: 25 pages = 625 postings, far beyond any board we target. */
    private static final int MAX_PAGES = 25;

    /** How long to let the portal boot and fire its first API call, in ms. */
    private static final int BOOT_TIMEOUT_MS = 20_000;

    /**
     * Replays the portal's own search call from inside the page, so origin, cookies and the
     * captured CSRF token all match what the WAF expects.
     */
    private static final String SEARCH_JS =
            "async ([ns, board, start, csrf]) => {"
                    + " const r = await fetch('/api/geo/' + ns + '/jobposting/search', {"
                    + "   method: 'POST',"
                    + "   headers: {'Content-Type': 'application/json', 'x-csrf-token': csrf,"
                    + "             'accept': 'application/json'},"
                    + "   body: JSON.stringify({clientNamespace: ns, jobBoardCode: board,"
                    + "     cultureCode: 'en-US', distanceUnit: 0, paginationStart: start})});"
                    + " return r.status + '::' + (await r.text()); }";

    private final Browser browser;
    private final ObjectMapper objectMapper;
    private final DayforceProperties properties;

    public DayforceScraper(
            Browser browser, ObjectMapper objectMapper, DayforceProperties properties) {
        this.browser = browser;
        this.objectMapper = objectMapper;
        this.properties = properties;
        log.info(
                "Dayforce scraper initialized (Playwright, {} portal(s))",
                properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "dayforce";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(DayforceCompany::getName).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Optional<DayforceCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No Dayforce config found for company: {}", company);
            return Collections.emptyList();
        }
        DayforceCompany config = configOpt.get();
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        // Playwright objects are not thread-safe and the poller scrapes companies on a 12-thread
        // pool; concurrent use of the shared browser bean corrupts the driver ("Cannot find object
        // to call __adopt__"). Serialize on the same monitor the other Playwright scrapers use.
        synchronized (browser) {
            try (BrowserContext context = browser.newContext()) {
                Page page = context.newPage();
                List<String> tokens = new CopyOnWriteArrayList<>();
                page.onRequest(
                        request -> {
                            if (request.url().contains("jobposting/search")) {
                                String token = request.headers().get("x-csrf-token");
                                if (token != null && !token.isBlank()) {
                                    tokens.add(token);
                                }
                            }
                        });
                page.navigate(
                        config.portalUrl(),
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(BOOT_TIMEOUT_MS / 4.0);
                for (int waited = 0; tokens.isEmpty() && waited < BOOT_TIMEOUT_MS; waited += 1000) {
                    page.waitForTimeout(1000);
                }
                if (tokens.isEmpty()) {
                    log.warn(
                            "Dayforce [{}]: portal never issued a search call — no CSRF token",
                            company);
                    return Collections.emptyList();
                }
                collect(company, config, page, tokens.get(0), byId);
            } catch (Exception e) {
                log.error("Failed to scrape Dayforce for company: {}", company, e);
            }
        }
        log.info("Dayforce [{}]: scraped {} total job(s)", company, byId.size());
        return new ArrayList<>(byId.values());
    }

    /** Pages the search API from inside the page until the board is exhausted. */
    private void collect(
            String company,
            DayforceCompany config,
            Page page,
            String csrf,
            Map<String, JobPosting> byId) {
        for (int pageIndex = 0; pageIndex < MAX_PAGES; pageIndex++) {
            Object raw =
                    page.evaluate(
                            SEARCH_JS,
                            List.of(
                                    config.getTenant(),
                                    config.getBoard(),
                                    pageIndex * PAGE_SIZE,
                                    csrf));
            String out = String.valueOf(raw);
            int split = out.indexOf("::");
            if (split < 0 || !out.startsWith("200")) {
                log.warn(
                        "Dayforce [{}]: search page {} returned {}",
                        company,
                        pageIndex,
                        split < 0 ? "an unreadable response" : out.substring(0, split));
                return;
            }
            JsonNode root = objectMapper.readTree(out.substring(split + 2));
            JsonNode postings = root.path("jobPostings");
            if (!postings.isArray() || postings.isEmpty()) {
                return;
            }
            for (JsonNode posting : postings) {
                String id = posting.path("jobPostingId").asText("");
                String title = posting.path("jobTitle").asText("");
                if (id.isEmpty() || title.isBlank()) {
                    continue;
                }
                byId.putIfAbsent(
                        id,
                        JobPosting.builder()
                                .company(company)
                                .externalId(id)
                                .title(title.trim())
                                .url(config.jobUrl(id))
                                .location(locations(posting.path("postingLocations")))
                                .description(posting.path("jobDescription").asText(""))
                                .postedDate(
                                        parseDate(
                                                posting.path("postingStartTimestampUTC")
                                                        .asText("")))
                                .detectedAt(Instant.now())
                                .notified(false)
                                .build());
            }
            if (byId.size() >= root.path("maxCount").asInt(Integer.MAX_VALUE)) {
                return;
            }
        }
        log.warn("Dayforce [{}] hit the {}-page cap", company, MAX_PAGES);
    }

    /** Joins each posting location as "City, ST" so multi-site roles expose every city. */
    private static String locations(JsonNode postingLocations) {
        StringJoiner joiner = new StringJoiner("; ");
        for (JsonNode location : postingLocations) {
            String city = location.path("cityName").asText("").trim();
            String state = location.path("stateCode").asText("").trim();
            String one = city.isEmpty() ? state : (state.isEmpty() ? city : city + ", " + state);
            if (one.isEmpty()) {
                one = location.path("formattedAddress").asText("").trim();
            }
            if (!one.isEmpty()) {
                joiner.add(one);
            }
        }
        return joiner.toString();
    }

    private static Instant parseDate(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(timestamp).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
