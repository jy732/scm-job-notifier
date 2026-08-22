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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for Lam Research Careers (Fremont/Livermore semiconductor-equipment maker), which runs on
 * Eightfold AI.
 *
 * <p>Lam's career site exposes Eightfold's public "pcsx" search API — {@code
 * careers.lamresearch.com/api/pcsx/search?domain=lamresearch.com&query=&location=&start=} — which
 * returns JSON {@code data.positions[]} (no auth needed). We run the SCM free-text queries,
 * paginate by {@code start}, and union de-duplicated by id. Locations come back like {@code
 * "US-CA-Livermore (1028)"}, so the downstream California pre-filter matches; Lam's SCM hub is
 * Fremont/Livermore CA. Descriptions come inline are not provided by the list endpoint (title +
 * location suffice for the pre-filters).
 */
@Slf4j
@Component
public class LamResearchScraper implements JobScraper {

    private static final String API =
            "https://careers.lamresearch.com/api/pcsx/search"
                    + "?domain=lamresearch.com&location=&start=%d&query=%s";
    private static final String JOB_URL = "https://careers.lamresearch.com/careers?pid=%s";
    private static final int MAX_PAGES = 15;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    /** SCM free-text queries. Union of results is de-duplicated by id. */
    private static final List<String> SCM_QUERIES =
            List.of(
                    "supply chain",
                    "procurement",
                    "logistics",
                    "planner",
                    "sourcing",
                    "buyer",
                    "materials");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public LamResearchScraper(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                        .build();
        this.objectMapper = objectMapper;
        log.info(
                "Lam Research scraper initialized (Eightfold pcsx, {} SCM queries)",
                SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "lamresearch";
    }

    @Override
    public List<String> companies() {
        return List.of("lamresearch");
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (String query : SCM_QUERIES) {
            try {
                fetchQuery(query, byId);
            } catch (Exception e) {
                log.error("Lam Research SCM query '{}' failed", query, e);
            }
        }
        log.info(
                "Lam Research: {} unique SCM candidate(s) across {} queries",
                byId.size(),
                SCM_QUERIES.size());
        return new ArrayList<>(byId.values());
    }

    private void fetchQuery(String query, Map<String, JobPosting> byId) {
        int start = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            String uri =
                    String.format(API, start, URLEncoder.encode(query, StandardCharsets.UTF_8));
            String resp =
                    webClient
                            .get()
                            .uri(uri)
                            .header(HttpHeaders.REFERER, "https://careers.lamresearch.com/careers")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
            if (resp == null) {
                break;
            }
            JsonNode positions = objectMapper.readTree(resp).path("data").path("positions");
            if (!positions.isArray() || positions.isEmpty()) {
                break;
            }
            for (JsonNode p : positions) {
                JobPosting posting = toJobPosting(p);
                if (!posting.getExternalId().isEmpty()) {
                    byId.putIfAbsent(posting.getExternalId(), posting);
                }
            }
            start += positions.size();
        }
    }

    private JobPosting toJobPosting(JsonNode p) {
        String id = p.path("id").asString("");
        List<String> locs = new ArrayList<>();
        for (JsonNode l : p.path("locations")) {
            // "US-CA-Livermore (1028)" -> drop the trailing "(code)"
            String loc = l.asString("").replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
            if (!loc.isBlank()) {
                locs.add(loc);
            }
        }
        return JobPosting.builder()
                .company("lamresearch")
                .externalId(id)
                .title(p.path("name").asString(""))
                .url(String.format(JOB_URL, id))
                .location(String.join("; ", locs))
                .description("")
                .postedDate(null)
                .detectedAt(Instant.now())
                .notified(false)
                .build();
    }
}
