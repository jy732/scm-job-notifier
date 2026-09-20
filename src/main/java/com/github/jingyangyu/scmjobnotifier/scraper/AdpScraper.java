package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.AdpProperties;
import com.github.jingyangyu.scmjobnotifier.config.AdpProperties.AdpCompany;
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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for ADP WorkforceNow career centers.
 *
 * <p>ADP's newer {@code myjobs.adp.com} SPA hides its listing behind an authenticated route, but
 * the WorkforceNow career center — which is what most of our ADP employers use — backs its page
 * with a <b>public</b> REST feed: {@code
 * /careercenter/public/events/staffing/v1/job-requisitions?cid={clientGuid}}. It answers plain HTTP
 * with no token, so no browser is needed. Paging is OData-style {@code $top}/{@code $skip}, and
 * {@code meta.totalNumber} says when to stop; the server caps a page at 20 rows regardless of
 * {@code $top}.
 *
 * <p>Each requisition carries a title, a {@code requisitionLocations} array with structured
 * city/state, and a post date, so the whole board is pulled in one pass and filtered downstream —
 * no keyword queries and no detail round-trip.
 */
@Slf4j
@Component
public class AdpScraper implements JobScraper {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    /** The feed caps a page at 20 rows whatever {@code $top} asks for. */
    private static final int PAGE_SIZE = 20;

    /** Safety cap: 50 pages = 1000 requisitions, far beyond any board we target. */
    private static final int MAX_PAGES = 50;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AdpProperties properties;

    public AdpScraper(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            AdpProperties properties) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        log.info("ADP scraper initialized with {} company(ies)", properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "adp";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(AdpCompany::getName).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Optional<AdpCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No ADP config found for company: {}", company);
            return Collections.emptyList();
        }
        AdpCompany config = configOpt.get();
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                JsonNode root = parse(fetch(config.feedUrl(PAGE_SIZE, page * PAGE_SIZE)));
                JsonNode requisitions = root.path("jobRequisitions");
                if (!requisitions.isArray() || requisitions.isEmpty()) {
                    break;
                }
                for (JsonNode requisition : requisitions) {
                    add(company, config, requisition, byId);
                }
                int total = root.path("meta").path("totalNumber").asInt(Integer.MAX_VALUE);
                if ((page + 1) * PAGE_SIZE >= total || requisitions.size() < PAGE_SIZE) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Failed to scrape ADP for company: {}", company, e);
        }
        log.info("ADP [{}]: scraped {} total job(s)", company, byId.size());
        return new ArrayList<>(byId.values());
    }

    private void add(
            String company, AdpCompany config, JsonNode requisition, Map<String, JobPosting> byId) {
        String id = requisition.path("itemID").asText("");
        String title = requisition.path("requisitionTitle").asText("");
        if (id.isEmpty() || title.isBlank()) {
            return;
        }
        byId.putIfAbsent(
                id,
                JobPosting.builder()
                        .company(company)
                        .externalId(id)
                        .title(title.trim())
                        .url(config.jobUrl(requisition.path("clientRequisitionID").asText(id)))
                        .location(locations(requisition.path("requisitionLocations")))
                        .description("")
                        .postedDate(parseDate(requisition.path("postDate").asText("")))
                        .detectedAt(Instant.now())
                        .notified(false)
                        .build());
    }

    /** Joins each site as "City, ST" so a multi-site role exposes every city to the CA filter. */
    private static String locations(JsonNode requisitionLocations) {
        StringJoiner joiner = new StringJoiner("; ");
        for (JsonNode location : requisitionLocations) {
            JsonNode address = location.path("address");
            String city = address.path("cityName").asText("").trim();
            String state =
                    address.path("countrySubdivisionLevel1").path("codeValue").asText("").trim();
            String one = city.isEmpty() ? state : (state.isEmpty() ? city : city + ", " + state);
            if (one.isEmpty()) {
                one = location.path("nameCode").path("shortName").asText("").trim();
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
            log.warn("ADP: unparseable response ({})", e.getMessage());
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

    private String fetch(String url) {
        return webClient
                .get()
                .uri(url)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
