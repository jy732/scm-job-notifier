package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.BrassRingProperties;
import com.github.jingyangyu.scmjobnotifier.config.BrassRingProperties.BrassRingCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for companies that use Kenexa BrassRing (the {@code sjobs.brassring.com} TGNewUI Angular
 * app) as their ATS — e.g. General Atomics, Harbor Freight, Lockheed Martin.
 *
 * <p>BrassRing gates its full job list behind a stateful, CSRF-protected search ({@code
 * PowerSearchJobs}) whose payload is impractical to replicate over plain HTTP. So — like {@link
 * AppleScraper}/{@link TeslaScraper} — this drives the real UI with Playwright: it navigates to the
 * board's Home page, types each SCM term into the power-search box, submits, and <b>captures the
 * {@code PowerSearchJobs} JSON response</b> the app fires (rather than scraping fragile DOM tiles).
 * The response is {@code {Jobs:{Job:[...]}}}, where each job carries a {@code Questions} array of
 * {@code {QuestionName, Value}} pairs ({@code reqid}, {@code jobtitle}, {@code jobdescription}, and
 * tenant-specific {@code formtext*} location fields) plus a {@code Link}. Results across queries are
 * de-duplicated by req id; descriptions come inline (single-phase).
 */
@Slf4j
@Component
public class BrassRingScraper implements JobScraper {

    // Keyword search box in BrassRing's *power/advanced* search panel. The home-hero box submits to
    // searchMatchedJobs (a résumé/profile match that returns nothing when not logged in); the real
    // keyword search is powerSearchJobs, whose input lives in the advanced panel we reveal first.
    private static final String KEYWORD_WIDGET = "#powerSearchKeyWord input, #powerSearchKeyWord";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/136.0.0.0 Safari/537.36";

    /** SCM search terms. Union of results across queries is de-duplicated by req id. */
    private static final List<String> SCM_QUERIES =
            List.of("supply chain", "procurement", "logistics", "planner", "buyer");

    private final Browser browser;
    private final ObjectMapper objectMapper;
    private final BrassRingProperties properties;

    public BrassRingScraper(
            Browser browser, ObjectMapper objectMapper, BrassRingProperties properties) {
        this.browser = browser;
        this.objectMapper = objectMapper;
        this.properties = properties;
        log.info(
                "BrassRing scraper initialized (Playwright, {} companies, {} SCM queries)",
                properties.getCompanies().size(),
                SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "brassring";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(BrassRingCompany::getName).toList();
    }

    /**
     * Renders the company's BrassRing board via Playwright, runs each SCM power-search, and unions
     * the captured {@code PowerSearchJobs} results de-duplicated by req id. On a per-query failure
     * (block, selector timeout, no search response), keeps whatever the other queries found.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        BrassRingCompany cfg = properties.findByName(company).orElse(null);
        if (cfg == null) {
            log.warn("BrassRing: no config for '{}'", company);
            return List.of();
        }
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        // Playwright's Connection is NOT thread-safe and this Browser bean is shared with the Apple
        // and Tesla scrapers; the poll runs companies on an 8-thread pool, so concurrent use corrupts
        // the driver ("Object doesn't exist: tracing@…" / "Cannot find object __adopt__"). Serialize
        // all Playwright work on the shared browser (the same singleton bean is used as the monitor
        // in AppleScraper/TeslaScraper too).
        synchronized (browser) {
            try (BrowserContext context =
                    browser.newContext(
                            new Browser.NewContextOptions()
                                    .setUserAgent(USER_AGENT)
                                    .setViewportSize(1920, 1080)
                                    .setLocale("en-US")
                                    .setTimezoneId("America/Los_Angeles"))) {
                // Hide the navigator.webdriver flag that BrassRing's anti-bot layer checks.
                context.addInitScript(
                        "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});");
                Page page = context.newPage();
                for (String query : SCM_QUERIES) {
                    try {
                        scrapeQuery(page, cfg, query, byId);
                    } catch (Exception e) {
                        log.debug(
                                "BrassRing {} query '{}' failed: {}",
                                company,
                                query,
                                e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to scrape BrassRing company {}", company, e);
            }
        }
        log.info("BrassRing {}: scraped {} unique SCM job(s)", company, byId.size());
        return new ArrayList<>(byId.values());
    }

    private void scrapeQuery(
            Page page, BrassRingCompany cfg, String query, Map<String, JobPosting> byId) {
        page.navigate(
                cfg.homeUrl(),
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE)
                        .setTimeout(30000));
        // A privacy/cookie consent modal (ngDialog) overlays the page and intercepts pointer events
        // (clicks on the search box time out). Accept it if there's an agree button, then remove any
        // residual overlay so interactions land.
        try {
            page.click(
                    "button[ng-click*='nextPrivacyFlow'], button[aria-label*='Agree']",
                    new Page.ClickOptions().setTimeout(4000));
        } catch (RuntimeException ignore) {
            // no consent dialog on this load
        }
        try {
            page.evaluate(
                    "() => document.querySelectorAll('.ngdialog-overlay, .ngdialog')"
                            + ".forEach(e => e.remove())");
        } catch (RuntimeException ignore) {
            // nothing to remove
        }
        // Reveal the power/advanced search panel — that's where the real keyword search
        // (powerSearchJobs) and its #powerSearchKeyWord box live. Best-effort.
        try {
            page.click(
                    "a[ng-click*='getPowerSearchQuestions'], a[ng-click*='toggleAdvancedOptions']",
                    new Page.ClickOptions().setTimeout(5000));
        } catch (RuntimeException ignore) {
            // already open, or a layout without the reveal link
        }
        // Wait for the power-search keyword input to render, then click to focus and type the query.
        try {
            page.waitForSelector(
                    KEYWORD_WIDGET,
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(15000));
        } catch (RuntimeException e) {
            // Diagnostic: BrassRing's anti-bot may serve headless Chromium a widget-less challenge
            // page. Log what actually loaded so a 0-job run reveals block-page vs selector issue.
            log.warn(
                    "BrassRing {} '{}': keyword widget not found. url='{}' title='{}' body~='{}'",
                    cfg.getName(),
                    query,
                    page.url(),
                    safeTitle(page),
                    safeBodyStart(page));
            throw e;
        }
        page.click(KEYWORD_WIDGET);
        page.keyboard().type(query);

        // Capture every Search/Ajax response the submit triggers so a 0-job run reveals exactly which
        // endpoint (if any) the search fired; parse the job-results payload and log the rest.
        List<Response> captured = new CopyOnWriteArrayList<>();
        Consumer<Response> listener =
                r -> {
                    if (r.url().contains("/Search/Ajax/")) {
                        captured.add(r);
                    }
                };
        page.onResponse(listener);
        try {
            submitSearch(page);
            page.waitForTimeout(6000);
        } finally {
            page.offResponse(listener);
        }
        int before = byId.size();
        boolean parsed = false;
        String sample = "";
        for (Response r : captured) {
            String ep = endpoint(r);
            if (ep.contains("PowerSearchJobs")
                    || ep.contains("ProcessSortAndShowMoreJobs")
                    || ep.equals("MatchedJobs")) {
                String body = safeText(r);
                if (sample.isEmpty() && !body.isEmpty()) {
                    sample = body.substring(0, Math.min(140, body.length())).replaceAll("\\s+", " ");
                }
                parseJobs(body, cfg, byId);
                parsed = true;
            }
        }
        log.debug(
                "BrassRing {} '{}': Ajax seen {}, parsed={}, +{} job(s), sample='{}'",
                cfg.getName(),
                query,
                captured.stream().map(BrassRingScraper::endpoint).toList(),
                parsed,
                byId.size() - before,
                sample);
    }

    private static String safeText(Response r) {
        try {
            return r.text();
        } catch (Exception e) {
            return "";
        }
    }

    /** Last path segment of a Search/Ajax URL (endpoint name), for diagnostic logging. */
    private static String endpoint(Response r) {
        String u = r.url().replaceAll("\\?.*$", "");
        int i = u.lastIndexOf('/');
        return i >= 0 ? u.substring(i + 1) : u;
    }

    /**
     * Submits the keyword search by clicking the power-search button ({@code
     * ng-click="powerSearchJobs(this)"} → the {@code PowerSearchJobs} results call), falling back to
     * Enter if the button isn't present.
     */
    private void submitSearch(Page page) {
        try {
            page.click(
                    "button[ng-click*='powerSearchJobs']",
                    new Page.ClickOptions().setTimeout(5000));
        } catch (RuntimeException e) {
            page.keyboard().press("Enter");
        }
    }

    private void parseJobs(String body, BrassRingCompany cfg, Map<String, JobPosting> byId) {
        if (body == null || !body.stripLeading().startsWith("{")) {
            return;
        }
        Map<String, Object> root =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        for (Map<String, Object> job : jobList(root)) {
            Map<String, String> q = questions(job);
            String reqId = q.getOrDefault("reqid", "");
            if (reqId.isEmpty() || byId.containsKey(reqId)) {
                continue;
            }
            byId.put(
                    reqId,
                    JobPosting.builder()
                            .company(cfg.getName())
                            .externalId(reqId)
                            .title(q.getOrDefault("jobtitle", ""))
                            .url(strOrEmpty(job.get("Link")))
                            .location(location(q, cfg))
                            .description(stripHtml(q.getOrDefault("jobdescription", "")))
                            .postedDate(null)
                            .detectedAt(Instant.now())
                            .build());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> jobList(Map<String, Object> root) {
        Object jobs = root.get("Jobs");
        if (jobs instanceof Map<?, ?> jobsMap && jobsMap.get("Job") instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    /** Flattens a job's {@code Questions} array into an ordered {@code QuestionName -> Value} map. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> questions(Map<String, Object> job) {
        Map<String, String> out = new LinkedHashMap<>();
        if (job.get("Questions") instanceof List<?> qs) {
            for (Object o : qs) {
                if (o instanceof Map<?, ?> m) {
                    Object name = m.get("QuestionName");
                    if (name != null) {
                        out.put(name.toString(), strOrEmpty(m.get("Value")).trim());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Builds the location from the configured {@code locationFields}, or — when none are configured
     * — from every non-empty {@code formtext*} value (guards against silent location-drops on
     * tenants whose fields aren't mapped yet).
     */
    private static String location(Map<String, String> q, BrassRingCompany cfg) {
        List<String> parts = new ArrayList<>();
        List<String> fields = cfg.getLocationFields();
        if (fields != null && !fields.isEmpty()) {
            for (String f : fields) {
                String v = q.get(f);
                if (v != null && !v.isBlank()) {
                    parts.add(v);
                }
            }
        } else {
            q.forEach(
                    (k, v) -> {
                        if (k.startsWith("formtext") && v != null && !v.isBlank()) {
                            parts.add(v);
                        }
                    });
        }
        return String.join(", ", parts);
    }

    private static String safeTitle(Page page) {
        try {
            return page.title();
        } catch (Exception e) {
            return "?";
        }
    }

    private static String safeBodyStart(Page page) {
        try {
            Object t =
                    page.evaluate(
                            "() => document.body ? document.body.innerText.slice(0, 160) : ''");
            return t == null ? "" : t.toString().replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            return "?";
        }
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String strOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }
}
