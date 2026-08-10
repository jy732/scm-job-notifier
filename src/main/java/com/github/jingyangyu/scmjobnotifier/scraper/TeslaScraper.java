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
 * Scraper for Tesla Careers ({@code tesla.com/careers/search}), adapted for SCM.
 *
 * <p>Ports swe-job-notifier's Playwright DOM scraper but searches supply-chain terms instead of
 * "software engineer". Tesla is Fremont-heavy so most results are CA (the CA pre-filter enforces).
 * Tesla uses Akamai bot detection that frequently blocks headless browsers — when blocked, this
 * scraper gracefully returns whatever it has instead of throwing.
 */
@Slf4j
@Component
public class TeslaScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://www.tesla.com/careers/search/?query=%s&country=US";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    /** SCM search terms. Union of results is de-duplicated by job id. */
    private static final List<String> SCM_QUERIES =
            List.of("supply chain", "procurement", "logistics");

    private final Browser browser;

    public TeslaScraper(Browser browser) {
        this.browser = browser;
        log.info("Tesla scraper initialized (Playwright, {} SCM queries)", SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "tesla";
    }

    @Override
    public List<String> companies() {
        return List.of("tesla");
    }

    /**
     * Renders Tesla's career page per SCM query via Playwright with a custom user agent and
     * 1920x1080 viewport to avoid bot detection, extracting jobs from the JS-rendered DOM. Results
     * across queries are de-duplicated by job id. On a WAF block or missing links, that query is
     * skipped.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        Map<String, JobPosting> byId = new LinkedHashMap<>();
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
                    log.debug("Tesla query '{}' failed: {}", query, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to scrape Tesla careers", e);
        }
        log.info("Tesla: scraped {} unique SCM job(s)", byId.size());
        return new ArrayList<>(byId.values());
    }

    @SuppressWarnings("unchecked")
    private void scrapeQuery(Page page, String query, Map<String, JobPosting> byId) {
        String url = String.format(SEARCH_URL, URLEncoder.encode(query, StandardCharsets.UTF_8));
        page.navigate(
                url,
                new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE)
                        .setTimeout(30000));

        String bodyStart =
                (String) page.evaluate("() => document.body?.innerText?.substring(0, 200) || ''");
        if (bodyStart.contains("Access Denied")) {
            log.debug("Tesla: blocked by WAF for query '{}', skipping", query);
            return;
        }

        try {
            page.waitForSelector(
                    "a[href*='/careers/job/']",
                    new Page.WaitForSelectorOptions().setTimeout(15000));
        } catch (Exception e) {
            log.debug("Tesla: no job links for query '{}'", query);
            return;
        }

        List<Map<String, String>> jobs =
                (List<Map<String, String>>)
                        page.evaluate(
                                "() => {\n"
                                        + "  const results = [];\n"
                                        + "  const seen = new Set();\n"
                                        + "  const links = document.querySelectorAll("
                                        + "\"a[href*='/careers/job/']\");\n"
                                        + "  links.forEach(link => {\n"
                                        + "    const href = link.getAttribute('href') || '';\n"
                                        + "    const idMatch = href.match(/\\/job\\/(\\d+)/);\n"
                                        + "    if (!idMatch || seen.has(idMatch[1])) return;\n"
                                        + "    seen.add(idMatch[1]);\n"
                                        + "    const card = link.closest('li')"
                                        + " || link.closest('div') || link;\n"
                                        + "    const titleEl = card.querySelector('h2, h3') || link;\n"
                                        + "    const title = titleEl.textContent.trim();\n"
                                        + "    if (!title || title.length < 3) return;\n"
                                        + "    let location = '';\n"
                                        + "    const spans = card.querySelectorAll('span');\n"
                                        + "    for (const s of spans) {\n"
                                        + "      const text = s.textContent.trim();\n"
                                        + "      if (text && text !== title && text.length < 100) {\n"
                                        + "        location = text; break;\n"
                                        + "      }\n"
                                        + "    }\n"
                                        + "    results.push({\n"
                                        + "      id: idMatch[1],\n"
                                        + "      title: title,\n"
                                        + "      url: href.startsWith('http') ? href"
                                        + " : 'https://www.tesla.com' + href,\n"
                                        + "      location: location\n"
                                        + "    });\n"
                                        + "  });\n"
                                        + "  return results;\n"
                                        + "}");

        if (jobs == null) {
            return;
        }
        for (Map<String, String> job : jobs) {
            String id = job.getOrDefault("id", "");
            if (id.isEmpty()) {
                continue;
            }
            byId.putIfAbsent(
                    id,
                    JobPosting.builder()
                            .company("tesla")
                            .externalId(id)
                            .title(job.getOrDefault("title", ""))
                            .url(job.getOrDefault("url", ""))
                            .location(job.getOrDefault("location", ""))
                            .postedDate(null)
                            .detectedAt(Instant.now())
                            .build());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens a fresh Playwright context and navigates to each unseen job's detail page. Called
     * post-dedup so only unseen jobs pay the navigation cost.
     */
    @Override
    public void fetchDescriptions(List<JobPosting> jobs) {
        if (jobs.isEmpty()) return;
        log.info("Tesla: fetching descriptions for {} unseen job(s)", jobs.size());
        try (BrowserContext ctx =
                browser.newContext(
                        new Browser.NewContextOptions()
                                .setUserAgent(USER_AGENT)
                                .setViewportSize(1920, 1080))) {
            Page page = ctx.newPage();
            for (JobPosting job : jobs) {
                job.setDescription(fetchJobDescription(page, job.getUrl()));
            }
        } catch (Exception e) {
            log.error("Tesla: failed to fetch descriptions", e);
        }
    }

    private String fetchJobDescription(Page page, String jobUrl) {
        if (jobUrl == null || jobUrl.isBlank()) {
            return "";
        }
        try {
            page.navigate(
                    jobUrl,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.NETWORKIDLE)
                            .setTimeout(15000));
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
            log.debug("Tesla: failed to fetch description for {}: {}", jobUrl, e.getMessage());
            return "";
        }
    }
}
