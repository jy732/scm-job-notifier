package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.AdzunaProperties;
import com.github.jingyangyu.scmjobnotifier.config.IcimsProperties;
import com.github.jingyangyu.scmjobnotifier.config.OracleCloudProperties;
import com.github.jingyangyu.scmjobnotifier.config.SuccessFactorsProperties;
import com.github.jingyangyu.scmjobnotifier.config.WorkdayProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Aggregator source backed by the Adzuna jobs API. Where the other scrapers each monitor one
 * employer's ATS, this queries Adzuna for California SCM roles across <em>every</em> board and
 * surfaces the long-tail employers we don't scrape directly.
 *
 * <p>Design (see technical-design.html §8):
 *
 * <ul>
 *   <li><b>Additive only</b> — employers already covered by a direct scraper are excluded, so this
 *       never duplicates their jobs; it's purely the "long-tail net".
 *   <li><b>Self-throttled</b> — participates in the normal poll but only hits the API every {@link
 *       AdzunaProperties#getThrottleMinutes()} minutes, to respect the ~250 calls/day free tier.
 *   <li><b>Tagged</b> — every posting is marked {@code source="adzuna"} so the email renders it in
 *       a separate section.
 * </ul>
 *
 * <p>Descriptions come inline from the API (a snippet), so {@link #fetchDescriptions} is a no-op.
 */
@Slf4j
@Component
public class AdzunaScraper implements JobScraper {

    private static final String SYNTHETIC_COMPANY = "adzuna";
    private static final int RESULTS_PER_PAGE = 50;

    /** Employer-name fragments that are staffing/recruiting/noise, not real SCM employers. */
    private static final List<String> NOISE =
            List.of(
                    "staffing",
                    "recruit",
                    "talent",
                    "robert half",
                    "insight global",
                    "aerotek",
                    "randstad",
                    "adecco",
                    "manpower",
                    "kforce",
                    "teksystems",
                    "cybercoders",
                    "jobot",
                    "consulting group",
                    "solutions inc",
                    "personnel",
                    "real estate",
                    "realty");

    /** Aliases for cryptic Workday/Oracle slugs → their real display-name fragment. */
    private static final Map<String, String> SLUG_ALIASES =
            Map.ofEntries(
                    Map.entry("flyzipline", "zipline"),
                    Map.entry("jdgroupnam", "shoe palace"),
                    Map.entry("vwr", "avantor"),
                    Map.entry("pbv", "pepsico"),
                    Map.entry("bdgrowers", "blue diamond"),
                    Map.entry("roche", "genentech"),
                    Map.entry("ngc", "northrop"),
                    Map.entry("globalhr", "raytheon"),
                    Map.entry("synnex", "hyve"),
                    Map.entry("eeho", "oracle"),
                    Map.entry("eofd", "albertsons"),
                    Map.entry("edel", "fortinet"),
                    Map.entry("ibqbjb", "honeywell"),
                    Map.entry("nerostechnologies", "neros"),
                    Map.entry("sambanovasystems", "sambanova"),
                    Map.entry("harbingermotors", "harbinger"),
                    Map.entry("orcabiosystems", "orca bio"),
                    Map.entry("ambirobotics", "ambi"),
                    Map.entry("penumbrainc", "penumbra"),
                    Map.entry("thewonderfulcompany", "wonderful"));

    private final WebClient webClient;
    private final AdzunaProperties props;
    private final Set<String> excludeTokens;

    private volatile Instant lastFetch = Instant.EPOCH;
    private volatile boolean disabledLogged = false;

    public AdzunaScraper(
            WebClient.Builder webClientBuilder,
            AdzunaProperties props,
            WorkdayProperties workday,
            OracleCloudProperties oracle,
            IcimsProperties icims,
            SuccessFactorsProperties sf,
            @Value("${job.companies.greenhouse:}") String greenhouse,
            @Value("${job.companies.lever:}") String lever,
            @Value("${job.companies.ashby:}") String ashby,
            @Value("${job.companies.smartrecruiters:}") String smartrecruiters) {
        this.webClient = webClientBuilder.build();
        this.props = props;
        this.excludeTokens =
                buildExcludeTokens(
                        workday, oracle, icims, sf, greenhouse, lever, ashby, smartrecruiters);
        log.info(
                "Adzuna scraper initialized (configured={}, {} exclude tokens, throttle={}m)",
                props.isConfigured(),
                excludeTokens.size(),
                props.getThrottleMinutes());
    }

    private static Set<String> buildExcludeTokens(
            WorkdayProperties workday,
            OracleCloudProperties oracle,
            IcimsProperties icims,
            SuccessFactorsProperties sf,
            String greenhouse,
            String lever,
            String ashby,
            String smartrecruiters) {
        Set<String> tokens = new HashSet<>();
        workday.getCompanies().forEach(c -> addToken(tokens, c.getName()));
        oracle.getCompanies().forEach(c -> addToken(tokens, c.getName()));
        icims.getCompanies().forEach(c -> addToken(tokens, c.getName()));
        sf.getCompanies().forEach(c -> addToken(tokens, c.getName()));
        for (String csv : List.of(greenhouse, lever, ashby, smartrecruiters)) {
            for (String slug : csv.split(",")) {
                addToken(tokens, slug);
            }
        }
        // bespoke single-company scrapers
        for (String s : List.of("amazon", "microsoft", "apple", "tesla")) {
            addToken(tokens, s);
        }
        // display-name aliases for cryptic slugs
        SLUG_ALIASES.values().forEach(v -> addToken(tokens, v));
        return tokens;
    }

    private static void addToken(Set<String> tokens, String raw) {
        String n = normalize(raw);
        if (n.length() >= 3) {
            tokens.add(n);
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    @Override
    public String platform() {
        return "adzuna";
    }

    @Override
    public List<String> companies() {
        return List.of(SYNTHETIC_COMPANY);
    }

    @Override
    public List<JobPosting> scrape(String company) {
        if (!props.isConfigured()) {
            if (!disabledLogged) {
                log.info("Adzuna source disabled (no app-id/app-key) — skipping");
                disabledLogged = true;
            }
            return Collections.emptyList();
        }
        Instant now = Instant.now();
        if (now.isBefore(lastFetch.plus(props.getThrottleMinutes(), ChronoUnit.MINUTES))) {
            return Collections.emptyList(); // throttled — a later poll will fetch
        }
        lastFetch = now;

        Map<String, JobPosting> byId = new LinkedHashMap<>();
        int rawCount = 0;
        int excluded = 0;
        try {
            for (String query : props.getQueries()) {
                for (int page = 1; page <= props.getPages(); page++) {
                    List<Map<String, Object>> results = fetchPage(query, page);
                    if (results.isEmpty()) {
                        break;
                    }
                    for (Map<String, Object> r : results) {
                        rawCount++;
                        JobPosting job = toJobPosting(r);
                        if (job == null) {
                            continue;
                        }
                        if (isExcludedOrNoise(job.getCompany())) {
                            excluded++;
                            continue;
                        }
                        byId.putIfAbsent(job.getExternalId(), job);
                    }
                }
            }
            log.info(
                    "Adzuna: {} raw hits across {} queries → {} excluded (already-scraped/noise) →"
                            + " {} unique long-tail job(s)",
                    rawCount,
                    props.getQueries().size(),
                    excluded,
                    byId.size());
            return new ArrayList<>(byId.values());
        } catch (Exception e) {
            log.error("Adzuna fetch failed: {}", e.getMessage());
            return new ArrayList<>(byId.values());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchPage(String query, int page) {
        String url =
                String.format(
                        "https://api.adzuna.com/v1/api/jobs/%s/search/%d?app_id=%s&app_key=%s"
                                + "&results_per_page=%d&what_phrase=%s&where=California"
                                + "&max_days_old=%d&sort_by=date",
                        props.getCountry(),
                        page,
                        props.getAppId(),
                        props.getAppKey(),
                        RESULTS_PER_PAGE,
                        URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20"),
                        props.getMaxDaysOld());
        Map<String, Object> body =
                webClient
                        .get()
                        // Pass a URI so WebClient uses it verbatim — .uri(String) treats it as a
                        // template and double-encodes our %20, which silently zeroed every
                        // multi-word phrase query (only single-word ones returned results).
                        .uri(URI.create(url))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .block();
        if (body == null) {
            return Collections.emptyList();
        }
        Object results = body.get("results");
        return results instanceof List
                ? (List<Map<String, Object>>) results
                : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private JobPosting toJobPosting(Map<String, Object> r) {
        String id = String.valueOf(r.get("id"));
        String title = str(r.get("title"));
        if (id.equals("null") || id.isBlank() || title.isBlank()) {
            return null;
        }
        String company = "Unknown";
        if (r.get("company") instanceof Map<?, ?> c && c.get("display_name") != null) {
            company = c.get("display_name").toString().trim();
        }
        String location = "";
        if (r.get("location") instanceof Map<?, ?> l && l.get("display_name") != null) {
            location = l.get("display_name").toString().trim();
        }
        return JobPosting.builder()
                .company(company.isBlank() ? "Unknown" : company)
                .externalId("adz-" + id)
                .title(title)
                .url(str(r.get("redirect_url")))
                .location(location)
                .description(stripHtml(str(r.get("description"))))
                .postedDate(parseCreated(r.get("created")))
                .detectedAt(Instant.now())
                .notified(false)
                .source("adzuna")
                .build();
    }

    private boolean isExcludedOrNoise(String company) {
        String lower = company.toLowerCase();
        for (String n : NOISE) {
            if (lower.contains(n)) {
                return true;
            }
        }
        String norm = normalize(company);
        if (norm.length() < 3) {
            return false;
        }
        for (String token : excludeTokens) {
            // token len>=4: substring either way; shorter: exact match (avoid false hits like "hp")
            if (token.length() >= 4
                    ? (norm.contains(token) || token.contains(norm))
                    : norm.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static Instant parseCreated(Object created) {
        if (created == null) {
            return null;
        }
        try {
            return Instant.parse(created.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
