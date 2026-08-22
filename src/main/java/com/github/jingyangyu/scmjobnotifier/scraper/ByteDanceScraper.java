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
 * Scraper for ByteDance Careers via their public job-search API.
 *
 * <p>ByteDance runs its own recruiting platform (not a supported ATS); its careers site fetches
 * from {@code jobs.bytedance.com/api/v1/search/job/posts} — an open JSON endpoint (no auth, no bot
 * wall) that takes a keyword + pagination and returns coded job rows. We run each SCM free-text
 * query, paginate via {@code offset} until the reported {@code count} is exhausted, and union the
 * results de-duplicated by id. The board is global, so location comes from each row's {@code
 * city_list} (English city names joined) and the downstream {@code isCaliforniaLocation} pre-filter
 * enforces CA (ByteDance's US supply-chain hub is San Jose). Descriptions come inline
 * (single-phase).
 *
 * <p>TikTok (now {@code lifeattiktok.com}) uses the same platform but a differently-gated search
 * endpoint — not covered here yet.
 */
@Slf4j
@Component
public class ByteDanceScraper implements JobScraper {

    private static final String API = "https://jobs.bytedance.com/api/v1/search/job/posts";
    private static final String JOB_URL = "https://jobs.bytedance.com/en/position/%s/detail";
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
        log.info("ByteDance scraper initialized ({} SCM queries)", SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "bytedance";
    }

    @Override
    public List<String> companies() {
        return List.of("bytedance");
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (String query : SCM_QUERIES) {
            try {
                fetchQuery(query, byId);
            } catch (Exception e) {
                log.error("ByteDance SCM query '{}' failed", query, e);
            }
        }
        log.info(
                "ByteDance: {} unique SCM candidate(s) across {} queries",
                byId.size(),
                SCM_QUERIES.size());
        return new ArrayList<>(byId.values());
    }

    private void fetchQuery(String query, Map<String, JobPosting> byId) {
        int offset = 0;
        int count = Integer.MAX_VALUE;
        for (int page = 0; page < MAX_PAGES && offset < count; page++) {
            String uri =
                    API
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
                log.warn("ByteDance: non-zero code for query '{}': {}", query, root.path("code"));
                break;
            }
            JsonNode data = root.path("data");
            count = data.path("count").asInt(0);
            JsonNode list = data.path("job_post_list");
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            for (JsonNode j : list) {
                JobPosting posting = toJobPosting(j);
                if (!posting.getExternalId().isEmpty()) {
                    byId.putIfAbsent(posting.getExternalId(), posting);
                }
            }
            offset += PAGE_SIZE;
        }
    }

    private JobPosting toJobPosting(JsonNode j) {
        String id = j.path("id").asString("");
        List<String> cities = new ArrayList<>();
        for (JsonNode c : j.path("city_list")) {
            String en = c.path("en_name").asString("");
            if (!en.isBlank()) {
                cities.add(en);
            }
        }
        return JobPosting.builder()
                .company("bytedance")
                .externalId(id)
                .title(j.path("title").asString(""))
                .url(String.format(JOB_URL, id))
                .location(String.join("; ", cities))
                .description(stripHtml(j.path("description").asString("")))
                .postedDate(null)
                .detectedAt(Instant.now())
                .notified(false)
                .build();
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
