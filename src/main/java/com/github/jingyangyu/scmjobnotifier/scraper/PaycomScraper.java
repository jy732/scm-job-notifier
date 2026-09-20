package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.PaycomProperties;
import com.github.jingyangyu.scmjobnotifier.config.PaycomProperties.PaycomCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scraper for Paycom career pages ({@code
 * paycomonline.net/v4/ats/web.php/portal/{id}/career-page}).
 *
 * <p>Paycom ships no job API — the board is a React app whose bundle exposes UI routes only, and
 * the server-rendered HTML is just a "Loading…" shell. So this drives the real page with
 * Playwright: it types each SCM term into the board's keyword box and reads the rendered cards.
 * Searching (rather than paging the whole board) keeps each employer to a handful of renders, which
 * matters because these tenants are dealerships/manufacturers with hundreds of hourly roles.
 *
 * <p>A card's first line is {@code "Title (reqId)"} and its link carries the posting id, but the
 * remaining lines are tenant-specific — some lead with badges ("Hot Job", "Full Time"), some prefix
 * the address with a site name — so the location is matched by shape rather than by line position.
 * Cards expose no posting date, so {@code postedDate} stays null and the pipeline falls back to
 * first-seen recency.
 *
 * <p>The visible keyword control is {@code #search-by-keyword-suggestion}; the similarly named
 * {@code #search-by-keyword} is a hidden mirror input that never accepts a fill.
 */
@Slf4j
@Component
public class PaycomScraper implements JobScraper {

    private static final String KEYWORD_BOX = "#search-by-keyword-suggestion";
    private static final String JOB_LINK = "a[href*='/jobs/']";

    /** Board boot, then per-search settle time (ms). */
    private static final int BOOT_MS = 8_000;

    private static final int SEARCH_SETTLE_MS = 5_000;

    private static final List<String> SCM_QUERIES =
            List.of(
                    "supply chain",
                    "procurement",
                    "purchasing",
                    "buyer",
                    "planner",
                    "logistics",
                    "inventory",
                    "materials");

    /** Posting id out of {@code /portal/{key}/jobs/{id}}. */
    private static final Pattern JOB_ID = Pattern.compile("/jobs/(\\d+)");

    /**
     * A card line that carries a real address: "City, ST ZIP", optionally behind a site-name prefix
     * ("Fulgent 4373 El Monte - El Monte, CA 91731") and optionally repeated for multi-site roles
     * ("Claude, TX 79019; Chandler, AZ 85248"). Tenants order their card lines differently and
     * interleave badges ("Hot Job", "Full Time"), so the location is found by shape rather than by
     * position.
     */
    private static final Pattern LOCATION_LINE =
            Pattern.compile("[A-Za-z][A-Za-z .'-]*,\\s*[A-Z]{2}\\s*\\d{5}");

    /** Site-name prefix ahead of the address on some tenants' cards. */
    private static final Pattern SITE_PREFIX = Pattern.compile("^.*?\\s+-\\s+(?=[A-Za-z])");

    /** Trailing requisition number Paycom appends to card titles, e.g. "Buyer 2 (52201)". */
    private static final Pattern TRAILING_REQ = Pattern.compile("\\s*\\(\\d+\\)\\s*$");

    private final Browser browser;
    private final PaycomProperties properties;

    public PaycomScraper(Browser browser, PaycomProperties properties) {
        this.browser = browser;
        this.properties = properties;
        log.info(
                "Paycom scraper initialized (Playwright, {} board(s), {} SCM queries)",
                properties.getCompanies().size(),
                SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "paycom";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(PaycomCompany::getName).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Optional<PaycomCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No Paycom config found for company: {}", company);
            return Collections.emptyList();
        }
        PaycomCompany config = configOpt.get();
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        // Playwright objects are not thread-safe and the poller scrapes companies on a 12-thread
        // pool; concurrent use of the shared browser bean corrupts the driver ("Cannot find object
        // to call __adopt__"). Serialize on the same monitor the other Playwright scrapers use.
        synchronized (browser) {
            try (BrowserContext context = browser.newContext()) {
                Page page = context.newPage();
                page.navigate(
                        config.careerPageUrl(),
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(BOOT_MS);
                for (String query : SCM_QUERIES) {
                    try {
                        search(page, query);
                        harvest(company, page, byId);
                    } catch (Exception e) {
                        log.warn(
                                "Paycom [{}] query '{}' failed: {}",
                                company,
                                query,
                                e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to scrape Paycom for company: {}", company, e);
            }
        }
        log.info("Paycom [{}]: scraped {} SCM candidate(s) via keyword", company, byId.size());
        return new ArrayList<>(byId.values());
    }

    /** Types one term into the board's keyword box and lets the result list re-render. */
    private void search(Page page, String query) {
        Locator box = page.locator(KEYWORD_BOX);
        box.fill(query);
        box.press("Enter");
        page.waitForTimeout(SEARCH_SETTLE_MS);
    }

    /**
     * Picks the card's address line by shape and strips any site-name prefix, so the downstream
     * California check sees a plain "City, ST ZIP" (or a "; "-joined list for multi-site roles).
     */
    private static String location(List<String> lines) {
        for (String line : lines) {
            if (LOCATION_LINE.matcher(line).find()) {
                return SITE_PREFIX.matcher(line).replaceFirst("").trim();
            }
        }
        return "";
    }

    /** Reads the rendered result cards into the de-duplicating accumulator. */
    private void harvest(String company, Page page, Map<String, JobPosting> byId) {
        Locator links = page.locator(JOB_LINK);
        int count = links.count();
        for (int i = 0; i < count; i++) {
            Locator link = links.nth(i);
            String href = link.getAttribute("href");
            if (href == null) {
                continue;
            }
            Matcher idMatch = JOB_ID.matcher(href);
            if (!idMatch.find()) {
                continue;
            }
            String id = idMatch.group(1);
            if (byId.containsKey(id)) {
                continue;
            }
            List<String> lines =
                    link.innerText().lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
            if (lines.isEmpty()) {
                continue;
            }
            String title = TRAILING_REQ.matcher(lines.get(0)).replaceAll("").trim();
            if (title.isEmpty()) {
                continue;
            }
            byId.put(
                    id,
                    JobPosting.builder()
                            .company(company)
                            .externalId(id)
                            .title(title)
                            .url("https://www.paycomonline.net" + href)
                            .location(location(lines))
                            .description(String.join(" ", lines.subList(1, lines.size())))
                            .postedDate(null)
                            .detectedAt(Instant.now())
                            .notified(false)
                            .build());
        }
    }
}
