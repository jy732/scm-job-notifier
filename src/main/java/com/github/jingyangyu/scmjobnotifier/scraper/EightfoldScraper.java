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
 * Scraper for CA employers on Eightfold AI, via its public "pcsx" search API.
 *
 * <p>Eightfold-hosted career sites expose {@code {host}/api/pcsx/search?domain={domain}&query=&
 * location=&start=} (no auth), returning JSON {@code data.positions[]}. Each configured company is
 * a portal (host + domain); we run the SCM free-text queries per company, paginate by {@code
 * start}, and union de-duplicated by id. Locations come back like {@code "US-CA-Fremont (1003)"} or
 * {@code "San Diego, California, United States of America"}, so the downstream California
 * pre-filter matches.
 *
 * <ul>
 *   <li><b>lamresearch</b> — Fremont/Livermore semiconductor-equipment SCM
 *   <li><b>qualcomm</b> — San Diego semiconductor SCM (Capacity Planning, Sourcing, Supply Chain)
 * </ul>
 */
@Slf4j
@Component
public class EightfoldScraper implements JobScraper {

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

    /**
     * One Eightfold career site: {@code company} identifier, {@code host}, and {@code domain}
     * param.
     */
    private record Portal(String company, String host, String domain) {}

    private static final List<Portal> PORTALS =
            List.of(
                    new Portal("lamresearch", "careers.lamresearch.com", "lamresearch.com"),
                    new Portal("qualcomm", "careers.qualcomm.com", "qualcomm.com"));

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public EightfoldScraper(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                        .build();
        this.objectMapper = objectMapper;
        log.info(
                "Eightfold scraper initialized ({} sites, {} SCM queries)",
                PORTALS.size(),
                SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "eightfold";
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
            log.warn("Eightfold: no portal for company '{}'", company);
            return List.of();
        }
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (String query : SCM_QUERIES) {
            try {
                fetchQuery(portal, query, byId);
            } catch (Exception e) {
                log.error("Eightfold [{}] SCM query '{}' failed", company, query, e);
            }
        }
        log.info(
                "Eightfold [{}]: {} unique SCM candidate(s) across {} queries",
                company,
                byId.size(),
                SCM_QUERIES.size());
        return new ArrayList<>(byId.values());
    }

    private void fetchQuery(Portal portal, String query, Map<String, JobPosting> byId) {
        int start = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            String uri =
                    "https://"
                            + portal.host()
                            + "/api/pcsx/search?domain="
                            + portal.domain()
                            + "&location=&start="
                            + start
                            + "&query="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8);
            String resp =
                    webClient
                            .get()
                            .uri(java.net.URI.create(uri))
                            .header(HttpHeaders.REFERER, "https://" + portal.host() + "/careers")
                            .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
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
                JobPosting posting = toJobPosting(portal, p);
                if (!posting.getExternalId().isEmpty()) {
                    byId.putIfAbsent(posting.getExternalId(), posting);
                }
            }
            start += positions.size();
        }
    }

    private JobPosting toJobPosting(Portal portal, JsonNode p) {
        String id = p.path("id").asString("");
        List<String> locs = new ArrayList<>();
        for (JsonNode l : p.path("locations")) {
            String loc = l.asString("").replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
            if (!loc.isBlank()) {
                locs.add(loc);
            }
        }
        return JobPosting.builder()
                .company(portal.company())
                .externalId(id)
                .title(p.path("name").asString(""))
                .url("https://" + portal.host() + "/careers?pid=" + id)
                .location(String.join("; ", locs))
                .description("")
                .postedDate(null)
                .detectedAt(Instant.now())
                .notified(false)
                .build();
    }
}
