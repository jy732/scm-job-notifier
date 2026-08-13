package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.IcimsProperties;
import com.github.jingyangyu.scmjobnotifier.config.IcimsProperties.IcimsCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
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
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for iCIMS career portals.
 *
 * <p>Unlike swe-job-notifier's Playwright port, this reads the iCIMS <em>legacy</em> search
 * fragment ({@code {sub}.icims.com/jobs/search?pr={page}&in_iframe=1}) over plain HTTP — it returns
 * a compact server-rendered list of ~50 job cards per page, so no headless browser is needed. Each
 * card gives a job id, slug, title, and a {@code US-{ST}-{City}} location, which we normalize to
 * {@code City, ST} so the California filter matches any CA city. Descriptions are deferred to the
 * two-phase {@link #fetchDescriptions} step (also plain HTTP).
 */
@Slf4j
@Component
public class IcimsScraper implements JobScraper {

    /** Safety cap on pagination (pr is a 0-indexed page of ~50 jobs). */
    private static final int MAX_PAGES = 40;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/136.0.0.0 Safari/537.36";

    /** A job card's anchor: captures id, slug, and the visible {@code <h3>} title. */
    private static final Pattern JOB_ANCHOR =
            Pattern.compile(
                    "<a href=\"[^\"]*?/jobs/(\\d+)/([^/\"]+)/job[^\"]*?\"[^>]*?>\\s*"
                            + "(?:<span[^>]*>[^<]*</span>\\s*)?<h3[^>]*>\\s*([^<]+?)\\s*</h3>",
                    Pattern.DOTALL);

    /**
     * The location span that precedes each job card. iCIMS templates vary by tenant: some render
     * the label as {@code Location : Location</span>} (AIT, Nikkiso), others as a plural {@code
     * Locations</span>} (Snap-on). Both are followed by the value span, so we accept either label —
     * otherwise a tenant's jobs parse with no location and get dropped by the California filter.
     */
    private static final Pattern LOCATION =
            Pattern.compile(
                    "Location(?:s| : Location)?</span>\\s*<span[^>]*>\\s*([^<]+?)\\s*</span>",
                    Pattern.DOTALL);

    /**
     * iCIMS location shape {@code US-CA-San Diego}; normalized to {@code San Diego, CA}. The optional
     * trailing {@code , ST} absorbs the redundant suffix iCIMS sometimes appends ({@code
     * US-CA-San Bernadino, CA}) so we don't double it.
     */
    private static final Pattern US_LOCATION =
            Pattern.compile("^US-([A-Z]{2})-(.+?)(?:,\\s*[A-Z]{2})?$");

    /** Job-description container on the detail fragment, tried in order. */
    private static final Pattern[] DESCRIPTION_MARKERS = {
        Pattern.compile("<div[^>]*class=\"[^\"]*iCIMS_JobContent[^\"]*\"[^>]*>"),
        Pattern.compile("<div[^>]*class=\"[^\"]*iCIMS_InfoMsg_Job[^\"]*\"[^>]*>"),
        Pattern.compile("<div[^>]*class=\"[^\"]*iCIMS_Expandable_Text[^\"]*\"[^>]*>")
    };

    private final WebClient webClient;
    private final IcimsProperties properties;

    public IcimsScraper(WebClient.Builder webClientBuilder, IcimsProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        log.info(
                "iCIMS scraper initialized with {} company(ies)", properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "icims";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(IcimsCompany::getName).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Paginates the legacy search fragment ({@value #MAX_PAGES} pages max), parsing job cards
     * and de-duplicating by id. Stops when a page returns no jobs. On failure mid-scrape, returns
     * partial results.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        Optional<IcimsCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No iCIMS config found for company: {}", company);
            return Collections.emptyList();
        }

        IcimsCompany config = configOpt.get();
        Map<String, JobPosting> byId = new LinkedHashMap<>();

        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                String html = fetch(config.searchUrl(page));
                if (html == null || html.isBlank()) {
                    break;
                }
                List<JobPosting> jobs = parseJobs(company, config, html);
                if (jobs.isEmpty()) {
                    break;
                }
                for (JobPosting job : jobs) {
                    byId.putIfAbsent(job.getExternalId(), job);
                }
            }
            log.info("iCIMS [{}]: scraped {} total job(s)", company, byId.size());
            return new ArrayList<>(byId.values());
        } catch (Exception e) {
            log.error("Failed to scrape iCIMS for company: {}", company, e);
            return new ArrayList<>(byId.values());
        }
    }

    private List<JobPosting> parseJobs(String company, IcimsCompany config, String html) {
        List<JobPosting> jobs = new ArrayList<>();
        Matcher m = JOB_ANCHOR.matcher(html);
        while (m.find()) {
            String id = m.group(1);
            String slug = m.group(2);
            String title = m.group(3).trim();
            if (title.isEmpty()) {
                continue;
            }
            jobs.add(
                    JobPosting.builder()
                            .company(company)
                            .externalId(id)
                            .title(unescape(title))
                            .url(config.jobUrl(id, slug))
                            // The location span sits just before the card's anchor — look back a
                            // bounded window and take the closest match.
                            .location(nearestLocation(html, m.start()))
                            .description("")
                            .detectedAt(Instant.now())
                            .notified(false)
                            .build());
        }
        return jobs;
    }

    /**
     * Finds the closest preceding location and normalizes {@code US-ST-City} to {@code City, ST}.
     */
    private static String nearestLocation(String html, int anchorStart) {
        String window = html.substring(Math.max(0, anchorStart - 700), anchorStart);
        Matcher lm = LOCATION.matcher(window);
        String raw = "";
        while (lm.find()) {
            raw = lm.group(1).trim(); // keep the last (closest) match
        }
        return normalizeLocation(raw);
    }

    /**
     * Cleans an iCIMS location value: unescapes entities, collapses whitespace, and normalizes each
     * segment. Multi-location postings arrive as a {@code |}-separated blend of bare cities and raw
     * {@code US-ST-City} fragments (often duplicated, with {@code &nbsp;}), e.g. {@code "Columbus&nbsp;
     * | US-OH-West Columbus | US-OH-West Columbus | US-OH-Dublin, OH"}. We turn each into {@code City,
     * ST}, drop duplicates, and rejoin — "Columbus | West Columbus, OH | Dublin, OH". The state token
     * is preserved so the California filter still matches CA cities not in its explicit list.
     */
    private static String normalizeLocation(String raw) {
        String cleaned = unescape(raw).replaceAll("\\s+", " ").replaceAll("\\s+,", ",").trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : cleaned.split("\\s*\\|\\s*")) {
            String seg = part.trim();
            Matcher us = US_LOCATION.matcher(seg);
            if (us.matches()) {
                seg = us.group(2).trim() + ", " + us.group(1);
            }
            if (!seg.isEmpty() && !parts.contains(seg)) {
                parts.add(seg);
            }
        }
        return String.join(" | ", parts);
    }

    private String fetch(String url) {
        return webClient
                .get()
                .uri(url)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches each unseen job's detail fragment over plain HTTP and extracts the description
     * best-effort from a known iCIMS container. A miss leaves the description empty (title + Gemini
     * still classify).
     */
    @Override
    public void fetchDescriptions(List<JobPosting> jobs) {
        for (JobPosting job : jobs) {
            try {
                String html = fetch(job.getUrl() + "?in_iframe=1");
                if (html != null) {
                    job.setDescription(extractDescription(html));
                }
            } catch (Exception e) {
                log.debug(
                        "iCIMS description fetch failed for {}: {}", job.getUrl(), e.getMessage());
            }
        }
    }

    private static String extractDescription(String html) {
        for (Pattern marker : DESCRIPTION_MARKERS) {
            Matcher m = marker.matcher(html);
            if (m.find()) {
                int from = m.end();
                String window = html.substring(from, Math.min(from + 16000, html.length()));
                String text = stripTags(window);
                if (text.length() > 150) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String stripTags(String html) {
        return unescape(
                html.replaceAll("(?s)<script.*?</script>", " ")
                        .replaceAll("(?s)<style.*?</style>", " ")
                        .replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s+", " ")
                        .trim());
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
