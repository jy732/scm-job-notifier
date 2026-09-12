package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.WorkdayProperties;
import com.github.jingyangyu.scmjobnotifier.config.WorkdayProperties.WorkdayCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

/**
 * Scraper for companies that use Workday as their ATS. Posts search requests to the Workday CXS
 * career site API ({@code {subdomain}.wd{N}.myworkdayjobs.com/wday/cxs/{subdomain}/{site}/jobs})
 * and paginates through all results.
 */
@Slf4j
@Component
public class WorkdayScraper implements JobScraper {

    private static final int PAGE_SIZE = 20;

    /**
     * Recency window. Workday returns jobs newest-first, so we paginate only until a page is
     * entirely older than this — we just need recent postings (the 15-min poll + dedup captures
     * every new one while it's fresh). This bounds each company by recency, not an arbitrary page
     * count: fast-posting firms fetch more pages, slow ones fewer.
     */
    private static final int MAX_DAYS_POSTED = 30;

    /** Safety backstop so a misbehaving tenant can't loop forever. */
    private static final int MAX_PAGES = 200;

    /** Extracts the leading number from Workday's relative "postedOn" string. */
    private static final Pattern POSTED_DAYS = Pattern.compile("(\\d+)");

    /**
     * Matches a California location-facet descriptor — e.g. {@code "US - CA, Sunnyvale"}, {@code
     * "US - Remote, CA"}, {@code "San Francisco Bay Area, CA"}, {@code "California"}. Used to pick
     * a tenant's CA location facet(s) so multi-location postings (whose {@code locationsText} is
     * only a count) are recognized as California.
     */
    private static final Pattern CA_FACET = Pattern.compile("(?i)[,\\-]\\s*ca\\b|\\bcalifornia\\b");

    /**
     * Whether a job's location text already reads as California (mirrors the pipeline's California
     * check), so we don't redundantly append "· California" to jobs that already qualify.
     */
    private static final Pattern LOC_HAS_CA =
            Pattern.compile("(?i)(?:^|[,\\s/\\-])ca\\b|\\bcalifornia\\b");

    /**
     * Retry on HTTP 429 with exponential backoff + jitter. Necessary because ~40 Workday companies
     * poll in parallel (8-thread pool) and Workday's CXS API rate-limits under that load, which
     * would otherwise cut pagination short (partial ~40 jobs per company). Jitter de-synchronizes
     * the retries so the pool doesn't stampede.
     */
    private static final Retry RATE_LIMIT_RETRY =
            Retry.backoff(4, Duration.ofSeconds(2))
                    .maxBackoff(Duration.ofSeconds(20))
                    .jitter(0.5)
                    .filter(WorkdayScraper::isRateLimited);

    private final WebClient webClient;
    private final WorkdayProperties properties;

    private static boolean isRateLimited(Throwable t) {
        return t instanceof WebClientResponseException e && e.getStatusCode().value() == 429;
    }

    public WorkdayScraper(WebClient.Builder webClientBuilder, WorkdayProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        log.info(
                "Workday scraper initialized with {} company(ies)",
                properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "workday";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(WorkdayCompany::getName).toList();
    }

    /**
     * Scrapes recent job postings for a Workday company via paginated API calls.
     *
     * <p>Workday returns jobs newest-first in pages of {@value #PAGE_SIZE}. We keep only postings
     * within {@link #MAX_DAYS_POSTED} days and stop once an entire page is older than the window (a
     * {@link #MAX_PAGES} backstop guards against a misbehaving tenant). A rough {@code postedDate}
     * is derived from Workday's relative "postedOn" string. On failure mid-pagination we return
     * partial results rather than nothing.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        Optional<WorkdayCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No Workday config found for company: {}", company);
            return Collections.emptyList();
        }

        WorkdayCompany config = configOpt.get();
        List<JobPosting> allJobs = new ArrayList<>();
        int offset = 0;
        // Workday's CXS reports the real "total" only on the FIRST page (0 on later pages), so we
        // capture it once and never overwrite with 0 — otherwise the loop stops after page 2 (40
        // jobs). We also stop when a page returns no postings, with a MAX_PAGES safety cap.
        int total = 0;
        // Facets come back on the first page; captured for the CA multi-location tagging pass
        // below.
        Object firstFacets = null;

        int pages = 0;
        boolean capped = true; // stays true only if the loop exhausts MAX_PAGES mid-recent-zone
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                pages++;
                Map<String, Object> response = fetchPage(config, offset);
                if (response == null) {
                    capped = false;
                    break;
                }
                if (page == 0) {
                    firstFacets = response.get("facets");
                }

                int pageTotal = ((Number) response.getOrDefault("total", 0)).intValue();
                if (pageTotal > 0) {
                    total = pageTotal;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> postings =
                        (List<Map<String, Object>>)
                                response.getOrDefault("jobPostings", Collections.emptyList());
                if (postings.isEmpty()) {
                    capped = false;
                    break;
                }

                int recentOnPage = 0;
                for (Map<String, Object> posting : postings) {
                    int days = parsePostedDays((String) posting.getOrDefault("postedOn", ""));
                    if (days <= MAX_DAYS_POSTED) {
                        recentOnPage++;
                        allJobs.add(toJobPosting(company, config, posting, days));
                    }
                }

                offset += PAGE_SIZE;
                // Newest-first: once an entire page is older than the window we've passed the
                // recent
                // zone (a lone pinned/old job on an otherwise-recent page doesn't stop us).
                if (recentOnPage == 0 || offset >= total) {
                    capped = false;
                    break;
                }
            }

            tagCaMultiLocation(config, allJobs, firstFacets);
            log.info(
                    "Workday [{}]: scraped {} recent of {} total over {} page(s)",
                    company,
                    allJobs.size(),
                    total,
                    pages);
            if (capped) {
                log.warn(
                        "Workday [{}]: TRUNCATED at page cap — {} pages, board total {};"
                                + " recent zone exceeds MAX_PAGES",
                        company,
                        MAX_PAGES,
                        total);
            }
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Workday for company: {}", company, e);
            return allJobs; // Return partial results — better than losing everything
        }
    }

    private Map<String, Object> fetchPage(WorkdayCompany config, int offset) {
        return fetchPage(config, offset, Collections.emptyMap());
    }

    private Map<String, Object> fetchPage(
            WorkdayCompany config, int offset, Map<String, Object> appliedFacets) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appliedFacets", appliedFacets);
        body.put("limit", PAGE_SIZE);
        body.put("offset", offset);
        body.put("searchText", "");

        return webClient
                .post()
                .uri(config.apiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .retryWhen(RATE_LIMIT_RETRY)
                .block();
    }

    /**
     * Recovers multi-location California postings that the {@code locationsText}/externalPath
     * heuristics miss. Workday shows a multi-location job as "N Locations" with only the primary
     * city in the path, so a role in several places including CA slips past the California filter.
     * Here we ask Workday which recent jobs match the tenant's California <em>location facet</em> —
     * the facet matches the full underlying location list, not the displayed summary — and tag
     * those jobs' location as California so they survive the pipeline. No-op when the tenant
     * exposes no CA location facet, and best-effort (any failure just leaves the jobs untagged).
     */
    private void tagCaMultiLocation(WorkdayCompany config, List<JobPosting> jobs, Object facets) {
        try {
            Map<String, List<String>> caFacets = new LinkedHashMap<>();
            if (facets instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> facetList = (List<Map<String, Object>>) facets;
                collectCaFacetIds(facetList, caFacets);
            }
            if (caFacets.isEmpty()) {
                return;
            }
            // One facet param at a time (mixing params ANDs them). Prefer city-level "locations".
            String param =
                    caFacets.containsKey("locations")
                            ? "locations"
                            : caFacets.keySet().iterator().next();
            Set<String> caPaths = fetchCaExternalPaths(config, Map.of(param, caFacets.get(param)));
            if (caPaths.isEmpty()) {
                return;
            }
            int tagged = 0;
            for (JobPosting j : jobs) {
                String loc = j.getLocation() == null ? "" : j.getLocation();
                if (caPaths.contains(j.getExternalId()) && !LOC_HAS_CA.matcher(loc).find()) {
                    j.setLocation(loc.isBlank() ? "California" : loc + " · California");
                    tagged++;
                }
            }
            if (tagged > 0) {
                log.info(
                        "Workday [{}]: recovered {} multi-location CA job(s) via location facet",
                        config.getName(),
                        tagged);
            }
        } catch (Exception e) {
            log.debug(
                    "Workday [{}]: CA-facet tagging skipped: {}", config.getName(), e.getMessage());
        }
    }

    /** City/metro-level location facet params whose values carry a usable per-location id. */
    private static boolean isCityLocationFacet(String facetParameter) {
        return "locations".equalsIgnoreCase(facetParameter)
                || "locationMetroArea".equalsIgnoreCase(facetParameter);
    }

    /**
     * Walks the (hierarchical) facet tree and collects, per city-level location facet param, the
     * ids of values whose descriptor names California.
     */
    @SuppressWarnings("unchecked")
    private static void collectCaFacetIds(
            List<Map<String, Object>> nodes, Map<String, List<String>> out) {
        if (nodes == null) {
            return;
        }
        for (Map<String, Object> node : nodes) {
            Object valsObj = node.get("values");
            if (!(valsObj instanceof List)) {
                continue;
            }
            List<Map<String, Object>> values = (List<Map<String, Object>>) valsObj;
            if (isCityLocationFacet((String) node.get("facetParameter"))) {
                for (Map<String, Object> v : values) {
                    String desc = (String) v.get("descriptor");
                    String id = (String) v.get("id");
                    if (id != null && desc != null && CA_FACET.matcher(desc).find()) {
                        out.computeIfAbsent(
                                        (String) node.get("facetParameter"), k -> new ArrayList<>())
                                .add(id);
                    }
                }
            }
            collectCaFacetIds(values, out); // recurse — location values are nested sub-facets
        }
    }

    /** Paginates the CA-facet-filtered search and collects the returned externalPaths. */
    @SuppressWarnings("unchecked")
    private Set<String> fetchCaExternalPaths(
            WorkdayCompany config, Map<String, Object> appliedFacets) {
        Set<String> paths = new HashSet<>();
        int offset = 0;
        int total = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> resp = fetchPage(config, offset, appliedFacets);
            if (resp == null) {
                break;
            }
            int pageTotal = ((Number) resp.getOrDefault("total", 0)).intValue();
            if (pageTotal > 0) {
                total = pageTotal;
            }
            List<Map<String, Object>> postings =
                    (List<Map<String, Object>>)
                            resp.getOrDefault("jobPostings", Collections.emptyList());
            if (postings.isEmpty()) {
                break;
            }
            for (Map<String, Object> p : postings) {
                String path = (String) p.getOrDefault("externalPath", "");
                if (!path.isBlank()) {
                    paths.add(path);
                }
            }
            offset += PAGE_SIZE;
            if (offset >= total) {
                break;
            }
        }
        return paths;
    }

    private JobPosting toJobPosting(
            String company, WorkdayCompany config, Map<String, Object> posting, int postedDaysAgo) {
        String title = (String) posting.getOrDefault("title", "");
        String externalPath = (String) posting.getOrDefault("externalPath", "");
        String location = (String) posting.getOrDefault("locationsText", "");
        // Multi-location postings report a useless summary ("2 Locations") instead of a city, which
        // would slip past the California filter. Workday embeds the PRIMARY location in the
        // externalPath (/job/{Location-Slug}/{title}_{id}), so fall back to that when locationsText
        // isn't a real place — e.g. "/job/Woodland-Hills-California/..." -> "Woodland Hills
        // California".
        if (location.isBlank() || location.matches("(?i)\\d+\\s+locations?")) {
            location = locationFromPath(externalPath);
        }
        // Metadata only — the description is deferred to fetchDescriptions() (post-dedup) so we
        // don't hammer Workday with one detail request per job (which triggers HTTP 429). None of
        // the pre-filters (freshness/exclude/California/SCM) need the description.
        return JobPosting.builder()
                .company(company)
                .externalId(externalPath)
                .title(title)
                .url(config.jobUrl(externalPath))
                .location(location)
                .description("")
                // Approx posted date from the relative "postedOn" — lets the freshness filter and
                // the 90-day cleanup work for Workday jobs (previously null → never cleaned up).
                .postedDate(Instant.now().minus(postedDaysAgo, ChronoUnit.DAYS))
                .detectedAt(Instant.now())
                .notified(false)
                .build();
    }

    /**
     * Derives a human location from a Workday {@code externalPath} like {@code
     * /job/Woodland-Hills-California/Analyst--Transportation_UMG-27299} — the second path segment
     * is the <em>primary</em> location slug (hyphens → spaces). Used as a fallback for
     * multi-location postings whose {@code locationsText} is only a count ("2 Locations").
     *
     * <p>Known limitation: this yields only the primary location. A multi-location role whose
     * primary is out-of-state but whose secondary is California is still dropped by the CA filter —
     * the full location list lives only on the per-job detail endpoint (deferred to avoid HTTP
     * 429). Slug state encoding also varies by tenant ({@code ...-California} vs {@code
     * US-CA-City}); the former matches on "california", the latter relies on the CA-cities list.
     *
     * @return the primary location, or "" if the path isn't the expected {@code /job/<loc>/...}
     *     shape
     */
    private static String locationFromPath(String externalPath) {
        if (externalPath == null) {
            return "";
        }
        String[] parts = externalPath.split("/");
        // Expected: ["", "job", "<Location-Slug>", "<title>_<id>"]
        if (parts.length >= 3 && "job".equals(parts[1])) {
            return parts[2].replace('-', ' ').trim();
        }
        return "";
    }

    /**
     * Parses Workday's relative {@code postedOn} ("Posted Today" / "Posted Yesterday" / "Posted N
     * Days Ago" / "Posted 30+ Days Ago") into an age in days. A trailing "+" (Workday caps the
     * display at "30+") is treated as older than the number, so "30+" &gt; a 30-day window. Unknown
     * or blank returns 0 (treated as recent) so a parse miss never drops a job or stops early.
     */
    private static int parsePostedDays(String postedOn) {
        if (postedOn == null || postedOn.isBlank()) {
            return 0;
        }
        String s = postedOn.toLowerCase();
        if (s.contains("today")) {
            return 0;
        }
        if (s.contains("yesterday")) {
            return 1;
        }
        Matcher m = POSTED_DAYS.matcher(s);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            return s.contains("+") ? n + 1 : n; // "30+" → 31 (older than a 30-day window)
        }
        return 0;
    }

    /**
     * Fetches full descriptions for the given (already pre-filtered + deduped) jobs. Called by the
     * poll after dedup, so only the small surviving set pays for detail requests — a handful per
     * company instead of thousands. Each job's {@code externalId} is its Workday {@code
     * externalPath}; its company config is looked up by name.
     */
    @Override
    public void fetchDescriptions(List<JobPosting> jobs) {
        for (JobPosting job : jobs) {
            Optional<WorkdayCompany> configOpt = properties.findByName(job.getCompany());
            if (configOpt.isEmpty()) {
                continue;
            }
            job.setDescription(fetchJobDescription(configOpt.get(), job.getExternalId()));
        }
    }

    /**
     * Fetches the full job description from Workday's detail endpoint. Returns empty string on any
     * failure — a missing description is acceptable since the title filter and Gemini can still
     * classify based on title alone.
     */
    @SuppressWarnings("unchecked")
    private String fetchJobDescription(WorkdayCompany config, String externalPath) {
        try {
            // Detail URL: {baseUrl}/wday/cxs/{subdomain}/{site}{externalPath}
            String detailUrl =
                    String.format(
                            "%s/wday/cxs/%s/%s%s",
                            config.baseUrl(),
                            config.getSubdomain(),
                            config.getSite(),
                            externalPath);
            Map<String, Object> detail =
                    webClient
                            .get()
                            .uri(detailUrl)
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .retryWhen(RATE_LIMIT_RETRY)
                            .block();
            if (detail == null) {
                return "";
            }
            Map<String, Object> jobInfo = (Map<String, Object>) detail.get("jobPostingInfo");
            if (jobInfo == null) {
                return "";
            }
            Object desc = jobInfo.get("jobDescription");
            if (desc instanceof String descStr) {
                return stripHtml(descStr);
            }
            return "";
        } catch (Exception e) {
            log.debug(
                    "Failed to fetch Workday job detail for {}: {}", externalPath, e.getMessage());
            return "";
        }
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
