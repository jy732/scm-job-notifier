package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.AdpMyJobsProperties;
import com.github.jingyangyu.scmjobnotifier.config.AdpMyJobsProperties.AdpMyJobsCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * Scraper for ADP's newer {@code myjobs.adp.com} career sites (Boot Barn, Raymond West, NRI, RJW).
 *
 * <p>The board is an Angular SPA whose bundle exposes UI routes only, and its {@code
 * /cx/staffing/v2/*} calls redirect to a login — which is why this portal first looked unscrapable.
 * The public listing actually comes from {@code
 * my.adp.com/.../job-requisitions/apply-custom-filters}, authenticated by a {@code myjobstoken}
 * that the <b>public</b> career-site config hands out ({@code
 * /public/staffing/v1/career-site/{domain}}). So the flow is: read the config, take the token, then
 * page the filter API over plain HTTP — no browser needed.
 *
 * <p>These are retail/3PL tenants with boards in the thousands (Boot Barn alone posts ~1,500), so
 * the scrape is narrowed server-side to California. The state filter's field name is discovered per
 * tenant from {@code search-custom-filters} (its category labels vary in case, e.g. "STATE" vs
 * "State"); if no state facet is found the whole board is paged instead and the location filter
 * downstream does the work.
 */
@Slf4j
@Component
public class AdpMyJobsScraper implements JobScraper {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    private static final String API_BASE =
            "https://my.adp.com/myadp_prefix/mycareer/public/staffing/v1/job-requisitions/";

    /** Fields the listing needs; keeping this tight keeps the payload small. */
    private static final String SELECT =
            "reqId,jobTitle,publishedJobTitle,jobDescription,clientRequisitionID,postingDate,"
                    + "requisitionLocations";

    private static final int PAGE_SIZE = 100;

    private static final int MAX_PAGES = 30;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AdpMyJobsProperties properties;

    public AdpMyJobsScraper(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            AdpMyJobsProperties properties) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        log.info(
                "ADP myJobs scraper initialized with {} company(ies)",
                properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "adpmyjobs";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(AdpMyJobsCompany::getName).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Optional<AdpMyJobsCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No ADP myJobs config found for company: {}", company);
            return Collections.emptyList();
        }
        AdpMyJobsCompany config = configOpt.get();
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        try {
            String token = token(config);
            if (token.isEmpty()) {
                log.warn("ADP myJobs [{}]: career-site config carried no myJobsToken", company);
                return Collections.emptyList();
            }
            String filter = californiaFilter(company, token);
            paginate(company, config, token, filter, byId);
        } catch (Exception e) {
            log.error("Failed to scrape ADP myJobs for company: {}", company, e);
        }
        log.info("ADP myJobs [{}]: scraped {} total job(s)", company, byId.size());
        return new ArrayList<>(byId.values());
    }

    /** Reads the public career-site config and returns its API token (empty when absent). */
    private String token(AdpMyJobsCompany config) {
        return parse(fetch(config.careerSiteUrl(), null)).path("myJobsToken").asText("");
    }

    /**
     * Finds the tenant's state facet and builds a California filter clause. Returns an empty string
     * when no state facet exists, which makes the caller page the whole board.
     */
    private String californiaFilter(String company, String token) {
        JsonNode categories =
                parse(fetch(url("search-custom-filters", "", 1, 0), token)).path("filterList");
        for (JsonNode category : categories) {
            if (!"state".equalsIgnoreCase(category.path("categoryLabel").asText(""))) {
                continue;
            }
            for (JsonNode value : category.path("filterList")) {
                if ("California".equalsIgnoreCase(value.path("value").asText(""))) {
                    return category.path("category").asText("") + " eq 'California'";
                }
            }
        }
        log.info("ADP myJobs [{}]: no California state facet — paging the whole board", company);
        return "";
    }

    private void paginate(
            String company,
            AdpMyJobsCompany config,
            String token,
            String filter,
            Map<String, JobPosting> byId) {
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode root =
                    parse(
                            fetch(
                                    url(
                                            "apply-custom-filters",
                                            filter,
                                            PAGE_SIZE,
                                            page * PAGE_SIZE),
                                    token));
            JsonNode requisitions = root.path("jobRequisitions");
            if (!requisitions.isArray() || requisitions.isEmpty()) {
                return;
            }
            for (JsonNode requisition : requisitions) {
                add(company, config, requisition, byId);
            }
            int total = root.path("count").asInt(Integer.MAX_VALUE);
            if ((page + 1) * PAGE_SIZE >= total || requisitions.size() < PAGE_SIZE) {
                return;
            }
        }
        log.warn("ADP myJobs [{}] hit the {}-page cap", company, MAX_PAGES);
    }

    private void add(
            String company,
            AdpMyJobsCompany config,
            JsonNode requisition,
            Map<String, JobPosting> byId) {
        String id = requisition.path("reqId").asText("");
        String title = requisition.path("publishedJobTitle").asText("");
        if (title.isBlank()) {
            title = requisition.path("jobTitle").asText("");
        }
        if (id.isEmpty() || title.isBlank()) {
            return;
        }
        byId.putIfAbsent(
                id,
                JobPosting.builder()
                        .company(company)
                        .externalId(id)
                        .title(title.trim())
                        .url(config.jobUrl(id))
                        .location(locations(requisition.path("requisitionLocations")))
                        .description(requisition.path("jobDescription").asText(""))
                        .postedDate(parseDate(requisition.path("postingDate").asText("")))
                        .detectedAt(Instant.now())
                        .notified(false)
                        .build());
    }

    /** Joins each posting site as "City, ST" so multi-site roles expose every city. */
    private static String locations(JsonNode requisitionLocations) {
        StringJoiner joiner = new StringJoiner("; ");
        for (JsonNode location : requisitionLocations) {
            JsonNode address = location.path("address");
            String city = address.path("cityName").asText("").trim();
            String state =
                    address.path("countrySubdivisionLevel1").path("codeValue").asText("").trim();
            String one = city.isEmpty() ? state : (state.isEmpty() ? city : city + ", " + state);
            if (one.isEmpty()) {
                one = location.path("nameCode").path("longName").asText("").trim();
            }
            if (!one.isEmpty()) {
                joiner.add(one);
            }
        }
        return joiner.toString();
    }

    private static String url(String endpoint, String filter, int top, int skip) {
        return API_BASE
                + endpoint
                + "?$select="
                + SELECT
                + "&$top="
                + top
                + "&$skip="
                + skip
                + "&$filter="
                // URLEncoder emits "+" for spaces, which ADP does NOT read as a space in $filter —
                // it silently drops the whole clause and returns the unfiltered board (1539 rows
                // instead of 289). %20 is required.
                + URLEncoder.encode(filter, StandardCharsets.UTF_8).replace("+", "%20")
                + "&tz=America/Los_Angeles";
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.readTree("{}");
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("ADP myJobs: unparseable response ({})", e.getMessage());
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

    /** One GET; {@code token} is null for the public career-site config, set for the API. */
    private String fetch(String url, String token) {
        // URI.create, not uri(String): WebClient treats a String as a URI *template* and re-encodes
        // it, turning our %20/%27 into %2520/%2527 — which ADP answers by silently dropping the
        // $filter clause and returning the whole unfiltered board.
        WebClient.RequestHeadersSpec<?> request =
                webClient
                        .get()
                        .uri(URI.create(url))
                        .header(HttpHeaders.USER_AGENT, USER_AGENT)
                        .header(HttpHeaders.ACCEPT, "application/json");
        if (token != null) {
            request =
                    request.header("myjobstoken", token)
                            .header("rolecode", "manager")
                            .header(HttpHeaders.REFERER, "https://myjobs.adp.com/");
        }
        return request.retrieve().bodyToMono(String.class).block();
    }
}
