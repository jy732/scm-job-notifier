package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.UltiProProperties;
import com.github.jingyangyu.scmjobnotifier.config.UltiProProperties.UltiProCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for UKG/UltiPro recruiting boards ({@code recruiting{N}.ultipro.com}).
 *
 * <p>The board's search box posts to a public JSON endpoint, {@code
 * JobBoardView/LoadSearchResults}, with an {@code opportunitySearch} body carrying a free-text
 * {@code QueryString} plus {@code Top}/{@code Skip} paging. We run the SCM query set per board
 * (rather than pulling whole boards) because these tenants are grocery/logistics employers whose
 * boards are dominated by hourly store roles, and de-duplicate by opportunity id across queries —
 * the same shape as the Oracle Cloud keyword mode.
 *
 * <p>Records are rich enough that no detail fetch is needed: each opportunity carries a title, a
 * structured {@code Locations[].Address} (city + state code, which the CA filter wants), a posted
 * date and a brief description. Multi-location postings list every site, so the locations are
 * joined with "; " and the downstream {@code isCaliforniaLocation} check sees a CA token whenever
 * any one site is Californian.
 */
@Slf4j
@Component
public class UltiProScraper implements JobScraper {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    /** Page size for Top/Skip paging. */
    private static final int PAGE_SIZE = 50;

    /** Safety cap on pages per query (a board returning a constant page would otherwise spin). */
    private static final int MAX_PAGES = 20;

    private static final List<String> SCM_QUERIES =
            List.of(
                    "supply chain",
                    "procurement",
                    "purchasing",
                    "buyer",
                    "planner",
                    "sourcing",
                    "logistics",
                    "inventory",
                    "materials");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final UltiProProperties properties;

    public UltiProScraper(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            UltiProProperties properties) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        log.info(
                "UltiPro scraper initialized with {} company(ies), {} SCM queries",
                properties.getCompanies().size(),
                SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "ultipro";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(UltiProCompany::getName).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Optional<UltiProCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No UltiPro config found for company: {}", company);
            return Collections.emptyList();
        }
        UltiProCompany config = configOpt.get();
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (String query : SCM_QUERIES) {
            try {
                paginate(company, config, query, byId);
            } catch (Exception e) {
                log.warn("UltiPro [{}] query '{}' failed: {}", company, query, e.getMessage());
            }
        }
        log.info("UltiPro [{}]: scraped {} SCM candidate(s) via keyword", company, byId.size());
        return new ArrayList<>(byId.values());
    }

    /** Pages one query until the board runs out of opportunities (or the page cap is hit). */
    private void paginate(
            String company, UltiProCompany config, String query, Map<String, JobPosting> byId) {
        for (int page = 0; page < MAX_PAGES; page++) {
            String body = fetch(config, query, page * PAGE_SIZE);
            JsonNode opportunities = parse(body).path("opportunities");
            if (!opportunities.isArray() || opportunities.isEmpty()) {
                return;
            }
            for (JsonNode o : opportunities) {
                String id = o.path("Id").asText("");
                String title = o.path("Title").asText("");
                if (id.isEmpty() || title.isBlank()) {
                    continue;
                }
                byId.putIfAbsent(
                        id,
                        JobPosting.builder()
                                .company(company)
                                .externalId(id)
                                .title(title.trim())
                                .url(config.jobUrl(id))
                                .location(locations(o.path("Locations")))
                                .description(o.path("BriefDescription").asText(""))
                                .postedDate(parseDate(o.path("PostedDate").asText("")))
                                .detectedAt(Instant.now())
                                .notified(false)
                                .build());
            }
            if (opportunities.size() < PAGE_SIZE) {
                return;
            }
        }
        log.warn("UltiPro [{}] query '{}' hit the {}-page cap", company, query, MAX_PAGES);
    }

    /**
     * Joins every listed site into "City, ST" form, separated by "; " so a multi-location posting
     * still exposes its CA site to the location filter.
     */
    private static String locations(JsonNode locations) {
        StringJoiner joiner = new StringJoiner("; ");
        for (JsonNode l : locations) {
            JsonNode address = l.path("Address");
            String city = address.path("City").asText("").trim();
            String state = address.path("State").path("Code").asText("").trim();
            String one = city.isEmpty() ? state : (state.isEmpty() ? city : city + ", " + state);
            if (one.isEmpty()) {
                one = l.path("LocalizedDescription").asText("").trim();
            }
            if (!one.isEmpty()) {
                joiner.add(one);
            }
        }
        return joiner.toString();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.readTree("{}");
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("UltiPro: unparseable response ({})", e.getMessage());
            return objectMapper.readTree("{}");
        }
    }

    private static Instant parseDate(String posted) {
        if (posted == null || posted.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(posted).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private String fetch(UltiProCompany config, String query, int skip) {
        Map<String, Object> search =
                Map.of(
                        "Top", PAGE_SIZE,
                        "Skip", skip,
                        "QueryString", query,
                        "OrderBy", List.of(),
                        "Filters", List.of());
        return webClient
                .post()
                .uri(config.searchUrl())
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("opportunitySearch", search))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
