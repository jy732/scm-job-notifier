package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.SuccessFactorsProperties;
import com.github.jingyangyu.scmjobnotifier.config.SuccessFactorsProperties.SuccessFactorsCompany;
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
 * Scraper for SAP SuccessFactors "Career Site Builder" (CSB) sites.
 *
 * <p>SuccessFactors has no public JSON API (the OData API is per-tenant OAuth-gated), so this
 * scrapes the only public surface: the CSB tile-search endpoint {@code
 * https://{host}/tile-search-results/?q=..&startrow=N}, which returns rendered HTML "job tiles".
 * Each tile carries a job id, a title, and a relative {@code data-url}; CSB tiles do <em>not</em>
 * carry a machine-readable location or description, so location is recovered from the URL slug and
 * the description is fetched best-effort from the detail page in the two-phase {@link
 * #fetchDescriptions} step.
 *
 * <p>Because the result set is otherwise the whole company (CSB has no location facet we can
 * trust), we bound the scrape by issuing a small set of SCM keyword queries and de-duplicating by
 * job id — the same multi-query pattern the Amazon scraper uses.
 *
 * <p><strong>Caveat:</strong> this is tenant-HTML, not a uniform API. The parser is verified
 * against {@code jobs.sap.com}; other CSB tenants must be checked individually (many
 * "SuccessFactors" career sites are actually at non-CSB hosts or have migrated to other ATSs).
 */
@Slf4j
@Component
public class SuccessFactorsScraper implements JobScraper {

    private static final int PAGE_SIZE = 25;
    private static final int MAX_PAGES_PER_QUERY = 6; // 150 results/query cap

    /** SCM keyword queries that bound the scrape to supply-chain-relevant roles. */
    private static final List<String> QUERIES =
            List.of(
                    "supply chain",
                    "procurement",
                    "logistics",
                    "buyer",
                    "planner",
                    "sourcing",
                    "inventory",
                    "fulfillment");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    /** Opening tag of a CSB job tile: captures the job id and the relative detail URL. */
    private static final Pattern TILE_OPEN =
            Pattern.compile(
                    "<li class=\"job-tile job-id-(\\d+)[^\"]*\"[^>]*?data-url=\"([^\"]+)\"");

    /** The job title anchor within a tile. */
    private static final Pattern TITLE =
            Pattern.compile("class=\"jobTitle-link[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    /** Best-effort description containers on the detail page, tried in order. */
    private static final Pattern[] DESCRIPTION_MARKERS = {
        Pattern.compile(
                "data-careersite-propertyid=\"description\"[^>]*>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<div[^>]*class=\"[^\"]*job[Dd]escription[^\"]*\"[^>]*>"),
        Pattern.compile("<!--\\s*START OF JOB DESCRIPTION\\s*-->", Pattern.CASE_INSENSITIVE)
    };

    private final WebClient webClient;
    private final SuccessFactorsProperties properties;

    public SuccessFactorsScraper(
            WebClient.Builder webClientBuilder, SuccessFactorsProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        log.info(
                "SuccessFactors scraper initialized with {} company(ies)",
                properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "successfactors";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(SuccessFactorsCompany::getName).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs each SCM keyword query and paginates its tile results (25/page, {@value
     * #MAX_PAGES_PER_QUERY} pages max), de-duplicating jobs by id across queries. Descriptions are
     * deferred to {@link #fetchDescriptions}. On failure mid-scrape, returns partial results.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        Optional<SuccessFactorsCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No SuccessFactors config found for company: {}", company);
            return Collections.emptyList();
        }

        SuccessFactorsCompany config = configOpt.get();
        Map<String, JobPosting> byId = new LinkedHashMap<>();

        try {
            for (String query : QUERIES) {
                for (int page = 0; page < MAX_PAGES_PER_QUERY; page++) {
                    String html = fetch(config.searchUrl(query, page * PAGE_SIZE));
                    if (html == null || html.isBlank()) {
                        break;
                    }
                    List<JobPosting> tiles = parseTiles(company, config, html);
                    if (tiles.isEmpty()) {
                        break;
                    }
                    for (JobPosting job : tiles) {
                        byId.putIfAbsent(job.getExternalId(), job);
                    }
                }
            }
            log.info("SuccessFactors [{}]: scraped {} total job(s)", company, byId.size());
            return new ArrayList<>(byId.values());
        } catch (Exception e) {
            log.error("Failed to scrape SuccessFactors for company: {}", company, e);
            return new ArrayList<>(byId.values());
        }
    }

    private List<JobPosting> parseTiles(String company, SuccessFactorsCompany config, String html) {
        List<JobPosting> jobs = new ArrayList<>();
        Matcher m = TILE_OPEN.matcher(html);
        List<int[]> spans = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        while (m.find()) {
            ids.add(m.group(1));
            urls.add(m.group(2));
            spans.add(new int[] {m.start()});
        }
        for (int i = 0; i < spans.size(); i++) {
            int start = spans.get(i)[0];
            int end = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : html.length();
            String block = html.substring(start, end);
            Matcher tm = TITLE.matcher(block);
            String title = tm.find() ? stripTags(tm.group(1)) : "";
            if (title.isEmpty()) {
                continue;
            }
            String dataUrl = urls.get(i);
            jobs.add(
                    JobPosting.builder()
                            .company(company)
                            .externalId(ids.get(i))
                            .title(title)
                            .url(config.jobUrl(dataUrl))
                            // CSB tiles carry no structured location; recover it from the URL slug
                            // (/job/{City-Title-reqno}/{id}/). Good enough for the CA city filter.
                            .location(slugToLocation(dataUrl))
                            .description("")
                            .detectedAt(Instant.now())
                            .notified(false)
                            .build());
        }
        return jobs;
    }

    /** Extracts a location hint from a tile's {@code /job/{slug}/{id}/} data-url. */
    private static String slugToLocation(String dataUrl) {
        String[] parts = dataUrl.split("/");
        String slug = parts.length > 2 ? parts[2] : "";
        return slug.replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
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
     * <p>Fetches each job's CSB detail page and pulls the description best-effort from a known
     * container. SuccessFactors detail markup varies by tenant, so a miss leaves the description
     * empty — the title filter and Gemini can still classify (as with the Microsoft scraper).
     */
    @Override
    public void fetchDescriptions(List<JobPosting> jobs) {
        for (JobPosting job : jobs) {
            try {
                String html = fetch(job.getUrl());
                if (html != null) {
                    job.setDescription(extractDescription(html));
                }
            } catch (Exception e) {
                log.debug(
                        "SuccessFactors description fetch failed for {}: {}",
                        job.getUrl(),
                        e.getMessage());
            }
        }
    }

    private static String extractDescription(String html) {
        for (Pattern marker : DESCRIPTION_MARKERS) {
            Matcher m = marker.matcher(html);
            if (m.find()) {
                int from = m.end();
                String window = html.substring(from, Math.min(from + 12000, html.length()));
                String text = stripTags(window);
                if (text.length() > 200) {
                    return text.length() > 8000 ? text.substring(0, 8000) : text;
                }
            }
        }
        return "";
    }

    private static String stripTags(String html) {
        return html.replaceAll("(?s)<script.*?</script>", " ")
                .replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
