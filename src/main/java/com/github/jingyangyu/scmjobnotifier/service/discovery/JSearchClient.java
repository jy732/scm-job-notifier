package com.github.jingyangyu.scmjobnotifier.service.discovery;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Thin client for the JSearch (Google-for-Jobs aggregator) RapidAPI {@code /search-v2} endpoint.
 * Used for <em>discovery</em> only — surfacing employers we don't yet scrape directly — never for
 * live alerting. Returns lightweight {@link DiscoveredJob}s (employer/title/location/publisher).
 */
@Slf4j
@Component
public class JSearchClient {

    private static final String HOST = "jsearch.p.rapidapi.com";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public JSearchClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${jsearch.api.key:}") String apiKey) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        if (apiKey == null || apiKey.isBlank()) {
            log.info("JSearch API key not configured — discovery disabled");
        }
    }

    /** True if a RapidAPI key is present (JSearch shares the app's RAPIDAPI_KEY). */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Runs one JSearch query (US, all dates, first page) and returns the parsed jobs. Best-effort:
     * any transport/parse failure yields an empty list so one bad query doesn't abort a sweep.
     */
    public List<DiscoveredJob> search(String query) {
        String url =
                "https://"
                        + HOST
                        + "/search-v2?query="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20")
                        + "&num_pages=1&country=us&date_posted=all";
        try {
            String body =
                    webClient
                            .get()
                            .uri(URI.create(url))
                            .header("x-rapidapi-host", HOST)
                            .header("x-rapidapi-key", apiKey)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
            if (body == null) {
                return List.of();
            }
            JsonNode jobs = objectMapper.readTree(body).path("data").path("jobs");
            if (!jobs.isArray()) {
                return List.of();
            }
            List<DiscoveredJob> out = new ArrayList<>();
            for (JsonNode j : jobs) {
                out.add(
                        new DiscoveredJob(
                                j.path("employer_name").asString(""),
                                j.path("job_title").asString(""),
                                j.path("job_city").asString(""),
                                j.path("job_state").asString(""),
                                j.path("job_publisher").asString("")));
            }
            return out;
        } catch (Exception e) {
            log.warn("JSearch query '{}' failed: {}", query, e.getMessage());
            return List.of();
        }
    }

    /** A single aggregated job from JSearch (metadata only — no description/apply URL kept). */
    public record DiscoveredJob(
            String employer, String title, String city, String state, String publisher) {}
}
