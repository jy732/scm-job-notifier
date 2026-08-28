package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.util.CsvUtil;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for companies on BambooHR's hosted careers, via the public {@code
 * https://{company}.bamboohr.com/careers/list} JSON endpoint (no auth).
 *
 * <p>The endpoint returns {@code {meta, result:[...]}} where each result has {@code jobOpeningName}
 * and a nested {@code location:{city,state}}; there's no keyword/location facet, so we pull the
 * whole (small) board and let the downstream SCM + California pre-filter decide relevance. No
 * posted-date is exposed in the list payload, so freshness falls back to first-seen. Companies come
 * from the {@code job.companies.bamboohr} CSV (BambooHR subdomains).
 */
@Slf4j
@Component
public class BambooHrScraper implements JobScraper {

    private static final String LIST_URL = "https://{company}.bamboohr.com/careers/list";
    private static final String JOB_URL = "https://%s.bamboohr.com/careers/%s";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    private final WebClient webClient;
    private final List<String> companies;

    public BambooHrScraper(
            WebClient.Builder webClientBuilder,
            @Value("${job.companies.bamboohr:}") String companiesCsv) {
        this.webClient = webClientBuilder.defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT).build();
        this.companies = CsvUtil.parse(companiesCsv);
        log.info("BambooHR scraper initialized with {} company(ies)", companies.size());
    }

    @Override
    public String platform() {
        return "bamboohr";
    }

    @Override
    public List<String> companies() {
        return companies;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches the whole board from {@code /careers/list} in one request (BambooHR returns the
     * full list; no pagination). On failure, returns an empty list.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<JobPosting> scrape(String company) {
        try {
            Map<String, Object> resp =
                    webClient
                            .get()
                            .uri(LIST_URL, company)
                            .header(HttpHeaders.ACCEPT, "application/json")
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block();

            if (resp == null || !(resp.get("result") instanceof List<?> result)) {
                return Collections.emptyList();
            }

            return result.stream()
                    .filter(Map.class::isInstance)
                    .map(o -> toJobPosting(company, (Map<String, Object>) o))
                    .toList();
        } catch (Exception e) {
            log.error("Failed to scrape BambooHR for company: {}", company, e);
            return Collections.emptyList();
        }
    }

    private JobPosting toJobPosting(String company, Map<String, Object> job) {
        String id = strOrEmpty(job.get("id"));
        String location = "";
        if (job.get("location") instanceof Map<?, ?> loc) {
            String city = strOrEmpty(loc.get("city"));
            String state = strOrEmpty(loc.get("state"));
            String sep = city.isEmpty() || state.isEmpty() ? "" : ", ";
            location = (city + sep + state).trim();
        }
        return JobPosting.builder()
                .company(company)
                .externalId(id)
                .title(strOrEmpty(job.get("jobOpeningName")))
                .url(String.format(JOB_URL, company, id))
                .location(location)
                .description("")
                .postedDate(null)
                .detectedAt(Instant.now())
                .build();
    }

    private static String strOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }
}
