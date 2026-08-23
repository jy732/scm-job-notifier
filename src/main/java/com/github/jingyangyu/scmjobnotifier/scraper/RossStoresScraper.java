package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for Ross Stores Careers ({@code jobs.rossstores.com}).
 *
 * <p>Ross's search API returns JSON but its keyword search is fuzzy (any common SCM word OR-matches
 * most of the ~7k-job board), so instead of keyword-filtering we <b>scan the whole board
 * newest-first and bound by recency</b> — the same approach the Workday scrapers use — and let the
 * downstream California + SCM filters do the selecting. The endpoint needs a session cookie
 * (bootstrapped from the search page) and returns a <em>double-encoded</em> JSON string; fields are
 * HTML-span-wrapped.
 *
 * <ul>
 *   <li>Bootstrap: GET {@code /search/searchjobs} → session cookies
 *   <li>Scan: GET {@code
 *       /Search/SearchResults?keyword=&jtStartIndex=&jtPageSize=100&jtSorting=PostedDate DESC}
 * </ul>
 */
@Slf4j
@Component
public class RossStoresScraper implements JobScraper {

    private static final String BOOTSTRAP_URL = "https://jobs.rossstores.com/search/searchjobs";
    private static final String SEARCH_URL =
            "https://jobs.rossstores.com/Search/SearchResults"
                    + "?keyword=&jtStartIndex=%d&jtPageSize=%d&jtSorting=PostedDate%%20DESC";
    private static final String JOB_URL = "https://jobs.rossstores.com/job/%s";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final int MAX_DAYS_POSTED = 30;
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public RossStoresScraper(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        this.objectMapper = objectMapper;
        log.info("Ross Stores scraper initialized (whole-board recency scan)");
    }

    @Override
    public String platform() {
        return "rossstores";
    }

    @Override
    public List<String> companies() {
        return List.of("rossstores");
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        try {
            String cookie = bootstrapCookie();
            for (int page = 0; page < MAX_PAGES; page++) {
                String body = fetch(String.format(SEARCH_URL, page * PAGE_SIZE, PAGE_SIZE), cookie);
                if (body == null || body.isBlank()) {
                    break;
                }
                JsonNode records = decode(body).path("Records");
                if (!records.isArray() || records.isEmpty()) {
                    break;
                }
                boolean reachedOld = false;
                for (JsonNode r : records) {
                    if (daysAgo(strip(r.path("PostedDate").asString(""))) > MAX_DAYS_POSTED) {
                        reachedOld = true; // sorted newest-first, so the rest are older too
                        continue;
                    }
                    JobPosting posting = toJobPosting(r);
                    if (!posting.getExternalId().isEmpty()) {
                        byId.putIfAbsent(posting.getExternalId(), posting);
                    }
                }
                if (reachedOld || records.size() < PAGE_SIZE) {
                    break;
                }
            }
            log.info("Ross Stores: scraped {} recent job(s)", byId.size());
        } catch (Exception e) {
            log.error("Failed to scrape Ross Stores careers", e);
        }
        return new ArrayList<>(byId.values());
    }

    /** GETs the search page to obtain the session cookies the JSON endpoint requires. */
    private String bootstrapCookie() {
        return webClient
                .get()
                .uri(java.net.URI.create(BOOTSTRAP_URL))
                .exchangeToMono(
                        resp -> {
                            StringBuilder sb = new StringBuilder();
                            resp.cookies()
                                    .forEach(
                                            (name, list) -> {
                                                if (!list.isEmpty()) {
                                                    if (sb.length() > 0) {
                                                        sb.append("; ");
                                                    }
                                                    sb.append(name)
                                                            .append("=")
                                                            .append(list.get(0).getValue());
                                                }
                                            });
                            return resp.releaseBody().thenReturn(sb.toString());
                        })
                .block();
    }

    private String fetch(String url, String cookie) {
        return webClient
                .get()
                .uri(java.net.URI.create(url))
                .header("X-Requested-With", "XMLHttpRequest")
                .header(HttpHeaders.ACCEPT, "application/json, text/javascript, */*; q=0.01")
                .header(HttpHeaders.REFERER, BOOTSTRAP_URL)
                .header(HttpHeaders.COOKIE, cookie == null ? "" : cookie)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /** The endpoint returns a JSON <em>string</em> containing the JSON (double-encoded). */
    private JsonNode decode(String body) {
        JsonNode outer = objectMapper.readTree(body);
        return outer.isString() ? objectMapper.readTree(outer.asString()) : outer;
    }

    private JobPosting toJobPosting(JsonNode r) {
        String ref = strip(r.path("ReferenceNumber").asString(""));
        return JobPosting.builder()
                .company("rossstores")
                .externalId(strip(r.path("ID").asString("")))
                .title(strip(r.path("Title").asString("")))
                .url(String.format(JOB_URL, ref))
                .location(strip(r.path("CityStateData").asString("")))
                .description("")
                .postedDate(parseDate(strip(r.path("PostedDate").asString(""))))
                .detectedAt(Instant.now())
                .notified(false)
                .build();
    }

    private static String strip(String s) {
        return s == null ? "" : TAGS.matcher(s).replaceAll("").replace("&nbsp;", " ").trim();
    }

    private static int daysAgo(String dateStr) {
        Instant posted = parseDate(dateStr);
        return posted == null ? 0 : (int) ChronoUnit.DAYS.between(posted, Instant.now());
    }

    private static Instant parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim(), DATE_FMT)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
