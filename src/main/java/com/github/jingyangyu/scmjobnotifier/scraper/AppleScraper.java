package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scraper for Apple Jobs ({@code jobs.apple.com}), adapted for SCM.
 *
 * <p>Ports swe-job-notifier's scraper (which reads the embedded {@code
 * window.__staticRouterHydrationData} JSON rather than the DOM) but searches supply-chain terms
 * instead of "software engineer". Apple is Cupertino-heavy so most results are CA (the CA
 * pre-filter enforces). Descriptions come inline in the hydration JSON (single-phase); results
 * across queries are de-duplicated by position id.
 */
@Slf4j
@Component
public class AppleScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://jobs.apple.com/en-us/search?search=%s&sort=newest&location=united-states-USA&page=%d";
    private static final int MAX_PAGES = 5;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US);

    /** SCM search terms. Union of results is de-duplicated by position id. */
    private static final List<String> SCM_QUERIES =
            List.of("supply chain", "procurement", "operations");

    private final Browser browser;

    public AppleScraper(Browser browser) {
        this.browser = browser;
        log.info("Apple scraper initialized (Playwright, {} SCM queries)", SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "apple";
    }

    @Override
    public List<String> companies() {
        return List.of("apple");
    }

    /**
     * Renders Apple's career SPA per SCM query via Playwright and extracts job data from the
     * embedded hydration JSON (resilient to CSS changes). Paginates each query up to {@value
     * #MAX_PAGES} pages; results across queries are de-duplicated by position id.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            for (String query : SCM_QUERIES) {
                try {
                    scrapeQuery(page, query, byId);
                } catch (Exception e) {
                    log.debug("Apple query '{}' failed: {}", query, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to scrape Apple jobs", e);
        }
        log.info("Apple: scraped {} unique SCM job(s)", byId.size());
        return new ArrayList<>(byId.values());
    }

    @SuppressWarnings("unchecked")
    private void scrapeQuery(Page page, String query, Map<String, JobPosting> byId) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        for (int pageNum = 1; pageNum <= MAX_PAGES; pageNum++) {
            String url = String.format(SEARCH_URL, encoded, pageNum);
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

            List<Map<String, Object>> jobs =
                    (List<Map<String, Object>>)
                            page.evaluate(
                                    "() => {\n"
                                            + "  function findJobArrays(obj, depth) {\n"
                                            + "    if (depth > 5 || !obj) return null;\n"
                                            + "    if (Array.isArray(obj) && obj.length > 0 && obj[0]"
                                            + " && (obj[0].postingTitle || obj[0].jobTitle"
                                            + " || obj[0].transformedPostingTitle)) return obj;\n"
                                            + "    if (typeof obj === 'object') {\n"
                                            + "      for (const v of Object.values(obj)) {\n"
                                            + "        const found = findJobArrays(v, depth + 1);\n"
                                            + "        if (found) return found;\n"
                                            + "      }\n"
                                            + "    }\n"
                                            + "    return null;\n"
                                            + "  }\n"
                                            + "  try {\n"
                                            + "    const data = window.__staticRouterHydrationData;\n"
                                            + "    if (!data || !data.loaderData) return [];\n"
                                            + "    const arr = findJobArrays(data.loaderData, 0);\n"
                                            + "    if (!arr) return [];\n"
                                            + "    return arr.map(j => {\n"
                                            + "      let loc = '';\n"
                                            + "      if (Array.isArray(j.locations) && j.locations.length > 0) {\n"
                                            + "        loc = j.locations.map(l => l.name || l).join('; ');\n"
                                            + "      } else if (typeof j.locations === 'string') {\n"
                                            + "        loc = j.locations;\n"
                                            + "      }\n"
                                            + "      const pid = j.positionId || j.reqId || '';\n"
                                            + "      let desc = '';\n"
                                            + "      if (typeof j.jobSummary === 'string') desc = j.jobSummary;\n"
                                            + "      else if (typeof j.description === 'string') desc = j.description;\n"
                                            + "      return {\n"
                                            + "        id: String(pid),\n"
                                            + "        title: j.postingTitle || j.transformedPostingTitle || '',\n"
                                            + "        url: '/en-us/details/' + pid,\n"
                                            + "        location: loc,\n"
                                            + "        date: j.postingDate || j.postDateInGMT || '',\n"
                                            + "        description: desc\n"
                                            + "      };\n"
                                            + "    });\n"
                                            + "  } catch (e) {\n"
                                            + "    return [];\n"
                                            + "  }\n"
                                            + "}");

            if (jobs == null || jobs.isEmpty()) {
                break;
            }
            for (Map<String, Object> job : jobs) {
                String id = String.valueOf(job.getOrDefault("id", ""));
                if (id.isEmpty() || byId.containsKey(id)) {
                    continue;
                }
                String jobUrl = String.valueOf(job.getOrDefault("url", ""));
                if (!jobUrl.startsWith("http")) {
                    jobUrl = "https://jobs.apple.com" + jobUrl;
                }
                byId.put(
                        id,
                        JobPosting.builder()
                                .company("apple")
                                .externalId(id)
                                .title(String.valueOf(job.getOrDefault("title", "")))
                                .url(jobUrl)
                                .location(String.valueOf(job.getOrDefault("location", "")))
                                .description(
                                        stripHtml(
                                                String.valueOf(
                                                        job.getOrDefault("description", ""))))
                                .postedDate(parseDate(String.valueOf(job.getOrDefault("date", ""))))
                                .detectedAt(Instant.now())
                                .build());
            }
        }
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static Instant parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim(), DATE_FMT)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
