package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for Amazon Jobs ({@code amazon.jobs/en/search.json}), adapted for SCM.
 *
 * <p>Amazon has no valid supply-chain <em>category</em> slug and its location params only rank
 * (don't strictly filter), so we query each SCM term via {@code base_query} narrowed by a set of CA
 * cities ({@code normalized_location[]}). That cuts a nationwide "supply chain" search from ~4360
 * hits to ~126 CA-region hits; our {@code isCaliforniaLocation} pre-filter then strictly enforces
 * California. Results across queries are de-duplicated by external id. Descriptions come inline in
 * the search response (single-phase).
 */
@Slf4j
@Component
public class AmazonScraper implements JobScraper {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 5; // cap per query (500 results) to bound volume

    /** SCM free-text queries (title + description match). Union of results is de-duplicated. */
    private static final List<String> SCM_QUERIES =
            List.of("supply chain", "procurement", "logistics", "sourcing", "inventory");

    /** Major CA cities used to narrow the search server-side (loose — the CA filter enforces). */
    private static final List<String> CA_LOCATIONS =
            List.of(
                    "San Francisco, California, USA",
                    "San Jose, California, USA",
                    "Sunnyvale, California, USA",
                    "Santa Clara, California, USA",
                    "Cupertino, California, USA",
                    "Palo Alto, California, USA",
                    "Fremont, California, USA",
                    "Oakland, California, USA",
                    "San Bruno, California, USA",
                    "Los Angeles, California, USA",
                    "El Segundo, California, USA",
                    "Culver City, California, USA",
                    "Irvine, California, USA",
                    "San Diego, California, USA",
                    "Sacramento, California, USA");

    private static final DateTimeFormatter POSTED_DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final WebClient webClient;

    public AmazonScraper(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
        log.info(
                "Amazon scraper initialized ({} SCM queries x {} CA cities)",
                SCM_QUERIES.size(),
                CA_LOCATIONS.size());
    }

    @Override
    public String platform() {
        return "amazon";
    }

    @Override
    public List<String> companies() {
        return List.of("amazon");
    }

    /**
     * Runs each SCM {@code base_query} (narrowed to CA cities), paginates, and unions the results
     * de-duplicated by external id. On a per-query failure, keeps whatever the other queries found.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (String query : SCM_QUERIES) {
            try {
                fetchQuery(query, byId);
            } catch (Exception e) {
                log.error("Amazon SCM query '{}' failed", query, e);
            }
        }
        log.info(
                "Amazon: {} unique CA-region SCM candidate(s) across {} queries",
                byId.size(),
                SCM_QUERIES.size());
        return new ArrayList<>(byId.values());
    }

    @SuppressWarnings("unchecked")
    private void fetchQuery(String query, Map<String, JobPosting> byId) {
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> response =
                    webClient
                            .get()
                            .uri(buildUri(query, offset))
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block();
            if (response == null) {
                break;
            }
            int totalHits = ((Number) response.getOrDefault("hits", 0)).intValue();
            List<Map<String, Object>> jobs =
                    (List<Map<String, Object>>)
                            response.getOrDefault("jobs", Collections.emptyList());
            if (jobs.isEmpty()) {
                break;
            }
            for (Map<String, Object> job : jobs) {
                JobPosting posting = toJobPosting(job);
                if (!posting.getExternalId().isEmpty()) {
                    byId.putIfAbsent(posting.getExternalId(), posting);
                }
            }
            offset += jobs.size();
            if (offset >= totalHits) {
                break;
            }
        }
    }

    /** Builds a fully-encoded search URI: base_query + repeated normalized_location[] + paging. */
    private static URI buildUri(String query, int offset) {
        StringBuilder sb =
                new StringBuilder("https://www.amazon.jobs/en/search.json?base_query=")
                        .append(URLEncoder.encode(query, StandardCharsets.UTF_8))
                        .append("&offset=")
                        .append(offset)
                        .append("&result_limit=")
                        .append(PAGE_SIZE);
        for (String loc : CA_LOCATIONS) {
            sb.append("&normalized_location%5B%5D=")
                    .append(URLEncoder.encode(loc, StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }

    private JobPosting toJobPosting(Map<String, Object> job) {
        String idIcims = strOrEmpty(job.get("id_icims"));
        String jobPath = strOrEmpty(job.get("job_path"));
        String description = strOrEmpty(job.get("description"));

        return JobPosting.builder()
                .company("amazon")
                .externalId(idIcims.isEmpty() ? strOrEmpty(job.get("id")) : idIcims)
                .title(strOrEmpty(job.get("title")))
                .url(jobPath.isEmpty() ? "" : "https://www.amazon.jobs" + jobPath)
                .location(strOrEmpty(job.get("normalized_location")))
                .description(stripHtml(description))
                .postedDate(parsePostedDate(strOrEmpty(job.get("posted_date"))))
                .detectedAt(Instant.now())
                .build();
    }

    private static Instant parsePostedDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            String normalized = dateStr.replaceAll("\\s+", " ").trim();
            return LocalDate.parse(normalized, POSTED_DATE_FMT)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String strOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }
}
