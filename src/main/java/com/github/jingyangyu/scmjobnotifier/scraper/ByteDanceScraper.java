package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for the ByteDance job family (ByteDance corp + TikTok) via their public job-search API.
 *
 * <p>ByteDance runs its own recruiting platform (not a supported ATS). Each brand exposes an open
 * JSON search endpoint (no auth, no bot wall) that takes a keyword + pagination and returns coded
 * job rows:
 *
 * <ul>
 *   <li><b>bytedance</b> — {@code jobs.bytedance.com/api/v1/search/job/posts}
 *   <li><b>tiktok</b> — {@code api.lifeattiktok.com/api/v1/public/supplier/search/job/posts} with a
 *       {@code website-path: tiktok} header (the brand selector — without it the call 405s)
 * </ul>
 *
 * We run each SCM free-text query per brand, paginate via {@code offset} until the reported {@code
 * count} is exhausted, and union de-duplicated by id. The boards are global, so location comes from
 * each row's {@code city_list} (ByteDance) or {@code city_info} (TikTok) — English city names — and
 * the downstream {@code isCaliforniaLocation} pre-filter enforces CA (US SCM hubs: San Jose, LA,
 * Fontana). Descriptions come inline (single-phase).
 */
@Slf4j
@Component
public class ByteDanceScraper implements JobScraper {

    private static final int PAGE_SIZE = 50;

    /** Safety cap; SCM keyword results are well under this many pages. */
    private static final int MAX_PAGES = 20;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    /** SCM free-text queries (title + description match). Union of results is de-duplicated. */
    private static final List<String> SCM_QUERIES =
            List.of(
                    "supply chain",
                    "procurement",
                    "logistics",
                    "sourcing",
                    "supply planner",
                    "inventory",
                    "commodity manager");

    /**
     * One brand's search endpoint. {@code websitePath} is the {@code website-path} header (or
     * null).
     */
    private record Portal(String company, String api, String jobUrlTemplate, String websitePath) {}

    private static final List<Portal> PORTALS =
            List.of(
                    new Portal(
                            "bytedance",
                            "https://jobs.bytedance.com/api/v1/search/job/posts",
                            "https://jobs.bytedance.com/en/position/%s/detail",
                            null),
                    new Portal(
                            "tiktok",
                            "https://api.lifeattiktok.com/api/v1/public/supplier/search/job/posts",
                            "https://lifeattiktok.com/position/%s/detail",
                            "tiktok"));

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ByteDanceScraper(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        // Responses can be a few hundred KB per page — lift the default 256 KB buffer.
        this.webClient =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                        .build();
        this.objectMapper = objectMapper;
        log.info(
                "ByteDance scraper initialized ({} brands, {} SCM queries)",
                PORTALS.size(),
                SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "bytedance";
    }

    @Override
    public List<String> companies() {
        return PORTALS.stream().map(Portal::company).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Portal portal =
                PORTALS.stream().filter(p -> p.company().equals(company)).findFirst().orElse(null);
        if (portal == null) {
            log.warn("ByteDance: no portal for company '{}'", company);
            return List.of();
        }
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (String query : SCM_QUERIES) {
            try {
                fetchQuery(portal, query, byId);
            } catch (Exception e) {
                log.error("ByteDance [{}] SCM query '{}' failed", company, query, e);
            }
        }
        log.info(
                "ByteDance [{}]: {} unique SCM candidate(s) across {} queries",
                company,
                byId.size(),
                SCM_QUERIES.size());
        return new ArrayList<>(byId.values());
    }

    private void fetchQuery(Portal portal, String query, Map<String, JobPosting> byId) {
        int offset = 0;
        int count = Integer.MAX_VALUE;
        for (int page = 0; page < MAX_PAGES && offset < count; page++) {
            String uri =
                    portal.api()
                            + "?keyword="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8)
                            + "&limit="
                            + PAGE_SIZE
                            + "&offset="
                            + offset;
            String body =
                    "{\"keyword\":\""
                            + query
                            + "\",\"limit\":"
                            + PAGE_SIZE
                            + ",\"offset\":"
                            + offset
                            + "}";
            String resp =
                    webClient
                            .post()
                            .uri(uri)
                            .header(HttpHeaders.REFERER, "https://jobs.bytedance.com/")
                            .headers(
                                    h -> {
                                        if (portal.websitePath() != null) {
                                            h.set("website-path", portal.websitePath());
                                        }
                                    })
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
            if (resp == null) {
                break;
            }
            JsonNode root = objectMapper.readTree(resp);
            if (root.path("code").asInt(-1) != 0) {
                log.warn(
                        "ByteDance [{}]: non-zero code for query '{}': {}",
                        portal.company(),
                        query,
                        root.path("code"));
                break;
            }
            JsonNode data = root.path("data");
            count = data.path("count").asInt(0);
            JsonNode list = data.path("job_post_list");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            for (JsonNode j : list) {
                JobPosting posting = toJobPosting(portal, j);
                if (!posting.getExternalId().isEmpty()) {
                    byId.putIfAbsent(posting.getExternalId(), posting);
                }
            }
            offset += PAGE_SIZE;
        }
    }

    private JobPosting toJobPosting(Portal portal, JsonNode j) {
        String id = j.path("id").asString("");
        return JobPosting.builder()
                .company(portal.company())
                .externalId(id)
                .title(j.path("title").asString(""))
                .url(String.format(portal.jobUrlTemplate(), id))
                .location(extractLocation(j))
                .description(stripHtml(j.path("description").asString("")))
                .postedDate(null)
                .detectedAt(Instant.now())
                .notified(false)
                .build();
    }

    /**
     * ByteDance rows carry {@code city_list} (array); TikTok rows carry {@code city_info} (single).
     */
    private static String extractLocation(JsonNode j) {
        List<String> cities = new ArrayList<>();
        JsonNode cityList = j.path("city_list");
        if (cityList.isArray() && !cityList.isEmpty()) {
            for (JsonNode c : cityList) {
                String en = c.path("en_name").asString("");
                if (!en.isBlank()) {
                    cities.add(en);
                }
            }
        } else {
            String en = j.path("city_info").path("en_name").asString("");
            if (!en.isBlank()) {
                cities.add(en);
            }
        }
        return String.join("; ", cities);
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
