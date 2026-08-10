package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for Microsoft Careers via the PCSX search API, adapted for SCM.
 *
 * <p>Calls {@code apply.careers.microsoft.com/api/pcsx/search} directly (no browser). Ports
 * swe-job-notifier's scraper but queries several supply-chain terms with {@code
 * location=California} instead of "software engineer" / United States, and de-duplicates the union
 * by job id.
 *
 * <p>The search API returns metadata only (no description), and {@code standardizedLocations} is a
 * list of "City, ST, US" strings — joined with "; " so {@code isCaliforniaLocation} can match a CA
 * token. Because there's no description, SCM postings are classified by title (Stage 1) and Gemini
 * (Stage 3); the local description signals (Stage 2) simply don't fire for Microsoft.
 */
@Slf4j
@Component
public class MicrosoftScraper implements JobScraper {

    private static final String JOB_URL_PREFIX = "https://apply.careers.microsoft.com";
    private static final int MAX_RESULTS = 100; // per query

    /** SCM free-text queries. Union of results is de-duplicated by job id. */
    private static final List<String> SCM_QUERIES =
            List.of("supply chain", "procurement", "logistics", "sourcing");

    private final WebClient webClient;

    public MicrosoftScraper(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
        log.info("Microsoft scraper initialized (PCSX API, {} SCM queries)", SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "microsoft";
    }

    @Override
    public List<String> companies() {
        return List.of("microsoft");
    }

    /**
     * Runs each SCM query (California-narrowed), paginates by result offset, and unions results
     * de-duplicated by job id. On a per-query failure, keeps what the other queries found.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (String query : SCM_QUERIES) {
            try {
                fetchQuery(query, byId);
            } catch (Exception e) {
                log.error("Microsoft SCM query '{}' failed", query, e);
            }
        }
        log.info(
                "Microsoft: {} unique CA-region SCM candidate(s) across {} queries",
                byId.size(),
                SCM_QUERIES.size());
        return new ArrayList<>(byId.values());
    }

    private void fetchQuery(String query, Map<String, JobPosting> byId) {
        int start = 0;
        while (start < MAX_RESULTS) {
            Map<String, Object> response =
                    webClient
                            .get()
                            .uri(buildUri(query, start))
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block();

            List<Map<String, Object>> positions = extractPositions(response);
            if (positions.isEmpty()) {
                break;
            }
            for (Map<String, Object> pos : positions) {
                JobPosting posting = toJobPosting(pos);
                if (!posting.getExternalId().isEmpty()) {
                    byId.putIfAbsent(posting.getExternalId(), posting);
                }
            }
            start += positions.size();
        }
    }

    private static URI buildUri(String query, int start) {
        String url =
                "https://apply.careers.microsoft.com/api/pcsx/search?domain=microsoft.com"
                        + "&query="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)
                        + "&location=California"
                        + "&start="
                        + start;
        return URI.create(url);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractPositions(Map<String, Object> response) {
        if (response == null) return Collections.emptyList();
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return Collections.emptyList();
        List<Map<String, Object>> positions = (List<Map<String, Object>>) data.get("positions");
        return positions != null ? positions : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private JobPosting toJobPosting(Map<String, Object> pos) {
        String id = String.valueOf(pos.getOrDefault("displayJobId", pos.getOrDefault("id", "")));
        String title = String.valueOf(pos.getOrDefault("name", ""));
        String posUrl = String.valueOf(pos.getOrDefault("positionUrl", ""));
        String url = posUrl.startsWith("http") ? posUrl : JOB_URL_PREFIX + posUrl;

        List<String> locations =
                (List<String>) pos.getOrDefault("standardizedLocations", Collections.emptyList());
        String location = String.join("; ", locations);

        Instant postedDate = null;
        Object postedTs = pos.get("postedTs");
        if (postedTs instanceof Number num) {
            postedDate = Instant.ofEpochSecond(num.longValue());
        }

        return JobPosting.builder()
                .company("microsoft")
                .externalId(id)
                .title(title)
                .url(url)
                .location(location)
                .description("") // PCSX search returns no description
                .postedDate(postedDate)
                .detectedAt(Instant.now())
                .build();
    }
}
