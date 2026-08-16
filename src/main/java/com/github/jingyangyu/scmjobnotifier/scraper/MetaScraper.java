package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for Meta Careers via their GraphQL API, adapted for SCM.
 *
 * <p>Meta's career site fetches job data from {@code metacareers.com/graphql}. This scraper (1)
 * bootstraps a {@code datr} cookie from facebook.com, (2) GETs the job-search page (carrying that
 * cookie) to extract a CSRF token ({@code LSD}), then (3) POSTs a GraphQL query ({@code
 * doc_id=29615178951461218}) per SCM free-text term, carrying the full accumulated cookie set. Each
 * query returns all matching jobs in one request (no pagination); results are unioned de-duplicated
 * by external id. Meta has no location facet we rely on, so the {@code isCaliforniaLocation}
 * pre-filter enforces California. Descriptions come inline (single-phase).
 *
 * <p><b>The {@code datr} cookie is required.</b> Since ~2026-06 Meta returns a generic 400 "Sorry,
 * something went wrong" error page (with no LSD token) to careers requests that lack it — which is
 * why every request logged "could not extract LSD token". Meta also serves the real page under a
 * {@code 429} soft rate-limit, so we read the response body regardless of status code rather than
 * treating non-2xx as a hard failure.
 */
@Slf4j
@Component
public class MetaScraper implements JobScraper {

    private static final String COOKIE_BOOTSTRAP_URL = "https://m.facebook.com/";
    private static final String PAGE_URL = "https://www.metacareers.com/jobs/";
    private static final String GRAPHQL_URL = "https://www.metacareers.com/graphql";
    private static final String DOC_ID = "29615178951461218";
    private static final Pattern LSD_PATTERN =
            Pattern.compile("\"LSD\",\\[],\\{\"token\":\"([^\"]+)\"");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/136.0.0.0 Safari/537.36";

    /** SCM free-text queries (title + description match). Union of results is de-duplicated. */
    private static final List<String> SCM_QUERIES =
            List.of(
                    "supply chain",
                    "procurement",
                    "logistics",
                    "sourcing",
                    "commodity manager",
                    "supply planner",
                    "inventory");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public MetaScraper(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT).build();
        this.objectMapper = objectMapper;
        log.info("Meta scraper initialized (GraphQL API, {} SCM queries)", SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "meta";
    }

    @Override
    public List<String> companies() {
        return List.of("meta");
    }

    /**
     * Bootstraps a {@code datr} cookie, fetches the LSD (CSRF) token, then runs each SCM query and
     * unions the results de-duplicated by external id. On a per-query failure, keeps whatever the
     * other queries found; if the token can't be extracted, returns empty.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        // Cookie jar threaded across all requests (name -> value). Meta gates the careers site on a
        // datr cookie; the job-search page then sets more cookies the GraphQL call needs.
        Map<String, String> cookies = new LinkedHashMap<>();
        exchangeForBody(HttpMethod.GET, COOKIE_BOOTSTRAP_URL, null, cookies, req -> {});

        String html =
                exchangeForBody(
                        HttpMethod.GET,
                        PAGE_URL,
                        null,
                        cookies,
                        req ->
                                req.header("Sec-Fetch-Dest", "document")
                                        .header("Sec-Fetch-Mode", "navigate")
                                        .header("Sec-Fetch-Site", "none")
                                        .header("Sec-Fetch-User", "?1")
                                        .header("Accept-Language", "en-US,en;q=0.9"));
        String lsd = extractLsd(html);
        if (lsd == null) {
            log.warn("Meta: could not extract LSD token (datr cookie rejected?), skipping");
            return Collections.emptyList();
        }

        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (String query : SCM_QUERIES) {
            try {
                fetchQuery(query, lsd, cookies, byId);
            } catch (Exception e) {
                log.error("Meta SCM query '{}' failed", query, e);
            }
        }
        log.info(
                "Meta: {} unique SCM candidate(s) across {} queries",
                byId.size(),
                SCM_QUERIES.size());
        return new ArrayList<>(byId.values());
    }

    private void fetchQuery(
            String query, String lsd, Map<String, String> cookies, Map<String, JobPosting> byId) {
        String variables =
                "{\"search_input\":{"
                        + "\"q\":\""
                        + query
                        + "\","
                        + "\"divisions\":[],\"offices\":[],\"roles\":[],"
                        + "\"leadership_levels\":[],\"saved_jobs\":[],"
                        + "\"saved_searches\":[],\"sub_teams\":[],\"teams\":[],"
                        + "\"is_leadership\":false,\"is_remote_only\":false,"
                        + "\"sort_by_new\":false,\"results_per_page\":null}}";

        String formBody =
                "lsd="
                        + lsd
                        + "&fb_api_caller_class=RelayModern"
                        + "&fb_api_req_friendly_name=CareersJobSearchResultsDataQuery"
                        + "&variables="
                        + URLEncoder.encode(variables, StandardCharsets.UTF_8)
                        + "&doc_id="
                        + DOC_ID;

        String body =
                exchangeForBody(
                        HttpMethod.POST,
                        GRAPHQL_URL,
                        formBody,
                        cookies,
                        req ->
                                req.header("Content-Type", "application/x-www-form-urlencoded")
                                        .header("Origin", "https://www.metacareers.com")
                                        .header("Referer", PAGE_URL)
                                        .header("Sec-Fetch-Dest", "empty")
                                        .header("Sec-Fetch-Mode", "cors")
                                        .header("Sec-Fetch-Site", "same-origin")
                                        .header("x-fb-lsd", lsd)
                                        .header("Accept", "*/*"));

        if (body == null || !body.stripLeading().startsWith("{")) {
            log.warn("Meta: non-JSON GraphQL response for query '{}' (blocked/rate-limited?)", query);
            return;
        }
        Map<String, Object> response =
                objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        if (response.containsKey("errors")) {
            log.warn("Meta: GraphQL errors for query '{}': {}", query, response.get("errors"));
            return;
        }
        for (Map<String, Object> job : extractJobs(response)) {
            JobPosting posting = toJobPosting(job);
            if (!posting.getExternalId().isEmpty()) {
                byId.putIfAbsent(posting.getExternalId(), posting);
            }
        }
    }

    /** HTTP methods this scraper issues (kept local to avoid importing the full enum surface). */
    private enum HttpMethod {
        GET,
        POST
    }

    /**
     * Issues one request carrying the current cookie jar, merges any {@code Set-Cookie} from the
     * response back into the jar, and returns the body as a String <em>regardless of status code</em>
     * (Meta serves the real page under a 429, and the datr-less 400 error page still needs reading
     * so token extraction can fail gracefully). Returns an empty string on transport failure.
     */
    private String exchangeForBody(
            HttpMethod method,
            String url,
            String formBody,
            Map<String, String> cookies,
            Consumer<WebClient.RequestHeadersSpec<?>> headerCustomizer) {
        try {
            WebClient.RequestHeadersSpec<?> spec;
            if (method == HttpMethod.POST) {
                spec = webClient.post().uri(url).bodyValue(formBody == null ? "" : formBody);
            } else {
                spec = webClient.get().uri(url);
            }
            if (!cookies.isEmpty()) {
                spec = spec.header(HttpHeaders.COOKIE, cookieHeader(cookies));
            }
            headerCustomizer.accept(spec);
            return spec.exchangeToMono(
                            resp -> {
                                resp.cookies()
                                        .forEach(
                                                (name, list) -> {
                                                    if (!list.isEmpty()) {
                                                        cookies.put(name, list.get(0).getValue());
                                                    }
                                                });
                                return resp.bodyToMono(String.class).defaultIfEmpty("");
                            })
                    .block();
        } catch (Exception e) {
            log.error("Meta: request to {} failed", url, e);
            return "";
        }
    }

    private static String cookieHeader(Map<String, String> cookies) {
        return cookies.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }

    private static String extractLsd(String html) {
        if (html == null) {
            return null;
        }
        Matcher m = LSD_PATTERN.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractJobs(Map<String, Object> response) {
        try {
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) {
                return Collections.emptyList();
            }
            Map<String, Object> search =
                    (Map<String, Object>) data.get("job_search_with_featured_jobs");
            if (search == null) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> jobs = (List<Map<String, Object>>) search.get("all_jobs");
            return jobs != null ? jobs : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Meta: unexpected response structure: {}", response.keySet());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private JobPosting toJobPosting(Map<String, Object> job) {
        String id = strOrEmpty(job.get("id"));
        String location = "";
        Object locs = job.get("locations");
        if (locs instanceof List<?> list) {
            location = String.join("; ", (List<String>) list);
        }
        return JobPosting.builder()
                .company("meta")
                .externalId(id)
                .title(strOrEmpty(job.get("title")))
                .url("https://www.metacareers.com/profile/job_details/" + id)
                .location(location)
                .description(stripHtml(strOrEmpty(job.get("description"))))
                .postedDate(null)
                .detectedAt(Instant.now())
                .build();
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String strOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }
}
