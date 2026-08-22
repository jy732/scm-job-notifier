package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scraper for Google Careers ({@code google.com/about/careers/applications/jobs/results}), adapted
 * for SCM.
 *
 * <p>Ports swe-job-notifier's Playwright DOM scraper but searches supply-chain terms instead of
 * "software engineer". Google's career site is a JS SPA (no usable public JSON API — the old {@code
 * /api/v3/search} 404s), so this renders the results page and extracts jobs via structural
 * selectors (links to {@code jobs/results/{id}} detail pages) rather than brittle class names.
 * Google is Mountain View/Sunnyvale/SF-heavy so many results are CA (the {@code
 * isCaliforniaLocation} pre-filter enforces). Runs a small set of SCM queries and de-duplicates by
 * id.
 */
@Slf4j
@Component
public class GoogleScraper implements JobScraper {

    private static final String RESULTS_BASE =
            "https://www.google.com/about/careers/applications/jobs/results";
    private static final int MAX_PAGES = 5;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    /** SCM free-text queries. Union of results is de-duplicated by job id. */
    private static final List<String> SCM_QUERIES =
            List.of("supply chain", "procurement", "logistics");

    private final Browser browser;

    public GoogleScraper(Browser browser) {
        this.browser = browser;
        log.info("Google scraper initialized (Playwright, {} SCM queries)", SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "google";
    }

    @Override
    public List<String> companies() {
        return List.of("google");
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        // Playwright is not thread-safe and this Browser bean is shared with the Apple/Tesla
        // scrapers; the poll's 8-thread pool would corrupt the driver, so serialize on the shared
        // browser (the same singleton bean is used as the monitor across all Playwright scrapers).
        synchronized (browser) {
            try (BrowserContext context =
                    browser.newContext(
                            new Browser.NewContextOptions()
                                    .setUserAgent(USER_AGENT)
                                    .setViewportSize(1920, 1080))) {
                Page page = context.newPage();
                for (String query : SCM_QUERIES) {
                    try {
                        scrapeQuery(page, query, byId);
                    } catch (Exception e) {
                        log.debug("Google query '{}' failed: {}", query, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to scrape Google careers", e);
            }
        }
        log.info("Google: scraped {} unique SCM job(s)", byId.size());
        return new ArrayList<>(byId.values());
    }

    @SuppressWarnings("unchecked")
    private void scrapeQuery(Page page, String query, Map<String, JobPosting> byId) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
            String url = RESULTS_BASE + "?q=" + encoded + "&location=United+States&page=" + pageNum;
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            try {
                page.waitForSelector(
                        "a[href*='jobs/results/']",
                        new Page.WaitForSelectorOptions().setTimeout(15000));
            } catch (Exception e) {
                log.debug("Google query '{}' page {}: no job links, stopping", query, pageNum);
                break;
            }

            List<Map<String, String>> jobs =
                    (List<Map<String, String>>)
                            page.evaluate(
                                    "() => {\n"
                                            + "  const results = [];\n"
                                            + "  const seen = new Set();\n"
                                            + "  const links = document.querySelectorAll("
                                            + "\"a[href*='jobs/results/']\");\n"
                                            + "  links.forEach(link => {\n"
                                            + "    const href = link.getAttribute('href') || '';\n"
                                            + "    const idMatch = href.match(/results\\/(\\d+)/);\n"
                                            + "    if (!idMatch || seen.has(idMatch[1])) return;\n"
                                            + "    seen.add(idMatch[1]);\n"
                                            + "    const card = link.closest('li') || link.closest('[role]')"
                                            + " || link.parentElement;\n"
                                            + "    const titleEl = card ? card.querySelector('h3') : null;\n"
                                            + "    const title = titleEl ? titleEl.textContent.trim()"
                                            + " : link.textContent.trim();\n"
                                            + "    let location = '';\n"
                                            + "    if (card) {\n"
                                            + "      const spans = card.querySelectorAll('span');\n"
                                            + "      for (const s of spans) {\n"
                                            + "        const text = s.textContent.trim()"
                                            + ".replace(/^place/, '');\n"
                                            + "        if (text && text !== title && text.includes(',')) {\n"
                                            + "          location = text; break;\n"
                                            + "        }\n"
                                            + "      }\n"
                                            + "    }\n"
                                            + "    let fullUrl = href.startsWith('http') ? href"
                                            + " : href.startsWith('/') ? 'https://www.google.com' + href"
                                            + " : 'https://www.google.com/about/careers/applications/' + href;\n"
                                            + "    fullUrl = fullUrl.split('?')[0];\n"
                                            + "    results.push({id: idMatch[1], title: title,"
                                            + " url: fullUrl, location: location});\n"
                                            + "  });\n"
                                            + "  return results;\n"
                                            + "}");

            if (jobs == null || jobs.isEmpty()) {
                break;
            }
            for (Map<String, String> job : jobs) {
                String id = job.getOrDefault("id", "");
                if (id.isEmpty() || byId.containsKey(id)) {
                    continue;
                }
                byId.put(
                        id,
                        JobPosting.builder()
                                .company("google")
                                .externalId(id)
                                .title(job.getOrDefault("title", ""))
                                .url(job.getOrDefault("url", ""))
                                .location(job.getOrDefault("location", ""))
                                .postedDate(null)
                                .detectedAt(Instant.now())
                                .notified(false)
                                .build());
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens a fresh Playwright context and navigates to each unseen job's detail page.
     * Serialized on the shared browser (Playwright is not thread-safe). Called post-dedup so only
     * unseen jobs pay the navigation cost.
     */
    @Override
    public void fetchDescriptions(List<JobPosting> jobs) {
        if (jobs.isEmpty()) return;
        log.info("Google: fetching descriptions for {} unseen job(s)", jobs.size());
        synchronized (browser) {
            try (BrowserContext ctx =
                    browser.newContext(new Browser.NewContextOptions().setUserAgent(USER_AGENT))) {
                Page page = ctx.newPage();
                for (JobPosting job : jobs) {
                    job.setDescription(fetchJobDescription(page, job.getUrl()));
                }
            } catch (Exception e) {
                log.error("Google: failed to fetch descriptions", e);
            }
        }
    }

    private String fetchJobDescription(Page page, String jobUrl) {
        if (jobUrl == null || jobUrl.isBlank()) {
            return "";
        }
        try {
            page.navigate(
                    jobUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            Object result =
                    page.evaluate(
                            "() => {\n"
                                    + "  const sections = document.querySelectorAll("
                                    + "'section, [role=\"main\"], article');\n"
                                    + "  for (const s of sections) {\n"
                                    + "    const text = s.innerText || '';\n"
                                    + "    if (text.length > 100) return text.substring(0, 2000);\n"
                                    + "  }\n"
                                    + "  return document.body?.innerText?.substring(0, 2000) || '';\n"
                                    + "}");
            return result instanceof String s ? s.replaceAll("\\s+", " ").trim() : "";
        } catch (Exception e) {
            log.debug("Google: failed to fetch description for {}: {}", jobUrl, e.getMessage());
            return "";
        }
    }
}
