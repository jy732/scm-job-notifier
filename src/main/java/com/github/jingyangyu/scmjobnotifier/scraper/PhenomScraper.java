package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.PhenomProperties;
import com.github.jingyangyu.scmjobnotifier.config.PhenomProperties.PhenomCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for Phenom People career sites (PPG, Lineage, Shamrock Foods, Din Tai Fung …).
 *
 * <p>Phenom's JSON widget endpoint ({@code POST /widgets}) answers 200 but returns zero rows for
 * these tenants, so this scraper uses the server-rendered search page instead: {@code
 * /us/en/search-results?keywords=…&from=N} embeds a {@code phApp.ddo} blob whose {@code
 * eagerLoadRefineSearch.data.jobs} array carries the full record — title, city/state, posted date
 * and the canonical apply URL. Pages are 10 rows, so {@code from} advances by the page size until a
 * short page arrives.
 *
 * <p>Runs the SCM query set per site and de-duplicates by {@code jobSeqNo} across queries, like the
 * Oracle Cloud and UltiPro keyword modes. Multi-location postings carry {@code
 * multi_location_array}, which is joined so a CA site survives the location filter even when the
 * primary city is elsewhere.
 */
@Slf4j
@Component
public class PhenomScraper implements JobScraper {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    /** Phenom's basic-form offset stamp, e.g. {@code 2026-08-10T00:00:00.000+0000}. */
    private static final DateTimeFormatter BASIC_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z");

    /** Phenom's search page returns 10 results per request. */
    private static final int PAGE_SIZE = 10;

    private static final int MAX_PAGES = 20;

    private static final List<String> SCM_QUERIES =
            List.of(
                    "supply chain",
                    "procurement",
                    "buyer",
                    "planner",
                    "logistics",
                    "inventory",
                    "sourcing",
                    "materials");

    /** The inline {@code phApp.ddo = {...};} assignment that carries the rendered result page. */
    private static final Pattern DDO =
            Pattern.compile(
                    "phApp\\.ddo\\s*=\\s*(\\{.*?\\});\\s*(?:phApp|</script>)", Pattern.DOTALL);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final PhenomProperties properties;

    public PhenomScraper(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            PhenomProperties properties) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        log.info(
                "Phenom scraper initialized with {} site(s), {} SCM queries",
                properties.getCompanies().size(),
                SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "phenom";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(PhenomCompany::getName).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Optional<PhenomCompany> configOpt =
                properties.getCompanies().stream()
                        .filter(c -> company.equals(c.getName()))
                        .findFirst();
        if (configOpt.isEmpty()) {
            log.warn("No Phenom config found for company: {}", company);
            return Collections.emptyList();
        }
        PhenomCompany config = configOpt.get();
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (String query : SCM_QUERIES) {
            try {
                paginate(company, config, query, byId);
            } catch (Exception e) {
                log.warn("Phenom [{}] query '{}' failed: {}", company, query, e.getMessage());
            }
        }
        log.info("Phenom [{}]: scraped {} SCM candidate(s) via keyword", company, byId.size());
        return new ArrayList<>(byId.values());
    }

    private void paginate(
            String company, PhenomCompany config, String query, Map<String, JobPosting> byId) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        for (int page = 0; page < MAX_PAGES; page++) {
            String html = fetch(config.searchUrl(encoded, page * PAGE_SIZE));
            JsonNode jobs = jobs(html);
            if (jobs.isEmpty()) {
                return;
            }
            for (JsonNode j : jobs) {
                String id = j.path("jobSeqNo").asText("");
                String title = j.path("title").asText("");
                if (id.isEmpty() || title.isBlank()) {
                    continue;
                }
                byId.putIfAbsent(
                        id,
                        JobPosting.builder()
                                .company(company)
                                .externalId(id)
                                .title(title.trim())
                                .url(jobUrl(config, j))
                                .location(locations(j))
                                .description(j.path("descriptionTeaser").asText(""))
                                .postedDate(parseDate(j.path("postedDate").asText("")))
                                .detectedAt(Instant.now())
                                .notified(false)
                                .build());
            }
            if (jobs.size() < PAGE_SIZE) {
                return;
            }
        }
        log.warn("Phenom [{}] query '{}' hit the {}-page cap", company, query, MAX_PAGES);
    }

    /** Extracts {@code eagerLoadRefineSearch.data.jobs} from the page's phApp.ddo blob. */
    private JsonNode jobs(String html) {
        if (html == null || html.isBlank()) {
            return objectMapper.readTree("[]");
        }
        Matcher m = DDO.matcher(html);
        if (!m.find()) {
            return objectMapper.readTree("[]");
        }
        try {
            JsonNode ddo = objectMapper.readTree(m.group(1));
            JsonNode jobs = ddo.path("eagerLoadRefineSearch").path("data").path("jobs");
            return jobs.isArray() ? jobs : objectMapper.readTree("[]");
        } catch (Exception e) {
            log.warn("Phenom: unparseable phApp.ddo ({})", e.getMessage());
            return objectMapper.readTree("[]");
        }
    }

    /**
     * Joins the posting's location list ("City, State, Country" entries), falling back to the
     * single-site fields, so a multi-location role advertises every city to the CA filter.
     */
    private static String locations(JsonNode job) {
        JsonNode multi = job.path("multi_location_array");
        if (multi.isArray() && !multi.isEmpty()) {
            StringJoiner joiner = new StringJoiner("; ");
            for (JsonNode one : multi) {
                String city = one.path("city").asText("").trim();
                String state = one.path("state").asText("").trim();
                String text = one.isTextual() ? one.asText("").trim() : join(city, state);
                if (!text.isEmpty()) {
                    joiner.add(text);
                }
            }
            if (joiner.length() > 0) {
                return joiner.toString();
            }
        }
        String cityState = job.path("cityState").asText("").trim();
        if (!cityState.isEmpty()) {
            return cityState;
        }
        return join(job.path("city").asText("").trim(), job.path("state").asText("").trim());
    }

    private static String join(String city, String state) {
        if (city.isEmpty()) {
            return state;
        }
        return state.isEmpty() ? city : city + ", " + state;
    }

    /**
     * Builds the site's own detail-page URL. Callers have already skipped records without a {@code
     * jobSeqNo}, so no apply-link fallback is needed.
     */
    private static String jobUrl(PhenomCompany config, JsonNode job) {
        String slug =
                job.path("title")
                        .asText("job")
                        .trim()
                        .replaceAll("[^A-Za-z0-9]+", "-")
                        .replaceAll("^-|-$", "");
        return String.format(
                "https://%s/%s/job/%s/%s",
                config.getHost(), config.getLocalePath(), job.path("jobSeqNo").asText(""), slug);
    }

    /**
     * Phenom stamps dates as {@code 2026-08-10T00:00:00.000+0000} — a basic-form offset that {@link
     * OffsetDateTime#parse(CharSequence)} rejects, so fall back to an explicit pattern before
     * giving up.
     */
    private static Instant parseDate(String posted) {
        if (posted == null || posted.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(posted).toInstant();
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(posted, BASIC_OFFSET).toInstant();
            } catch (Exception e) {
                return null;
            }
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
