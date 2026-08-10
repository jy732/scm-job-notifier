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
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for companies using the Greenhouse Boards API.
 *
 * <p>Two-phase (see {@link JobScraper}): {@link #scrape} fetches metadata only (the board list
 * without {@code content=true}), and {@link #fetchDescriptions} fetches the full description per
 * job — but only for the unseen jobs that survived pre-filtering and dedup. This matters at scale:
 * a board's {@code content=true} list can be ~10x larger (SpaceX: 24 MB vs 2.2 MB), so we avoid
 * downloading descriptions for the ~99% of jobs that are dropped as non-CA / non-SCM.
 */
@Slf4j
@Component
public class GreenhouseScraper implements JobScraper {

    // Metadata only (no descriptions). content=true is deferred to fetchDescriptions().
    private static final String LIST_URL =
            "https://boards-api.greenhouse.io/v1/boards/{company}/jobs";
    // Per-job endpoint — includes the full HTML "content" (description).
    private static final String JOB_URL =
            "https://boards-api.greenhouse.io/v1/boards/{company}/jobs/{id}";

    private final WebClient webClient;
    private final List<String> companies;

    public GreenhouseScraper(
            WebClient.Builder webClientBuilder,
            @Value("${job.companies.greenhouse:}") String companiesCsv) {
        this.webClient = webClientBuilder.build();
        this.companies = CsvUtil.parse(companiesCsv);
        log.info("Greenhouse scraper initialized with {} company(ies)", companies.size());
    }

    @Override
    public String platform() {
        return "greenhouse";
    }

    @Override
    public List<String> companies() {
        return companies;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches the board's job list in a single request (no pagination — Greenhouse returns the
     * full list). Metadata only; descriptions are deferred to {@link #fetchDescriptions}. On
     * failure, returns an empty list.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<JobPosting> scrape(String company) {
        try {
            Map<String, Object> response =
                    webClient
                            .get()
                            .uri(LIST_URL, company)
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block();

            if (response == null || !response.containsKey("jobs")) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> jobs = (List<Map<String, Object>>) response.get("jobs");
            return jobs.stream().map(job -> toJobPosting(company, job)).toList();
        } catch (Exception e) {
            log.error("Failed to scrape Greenhouse for company: {}", company, e);
            return Collections.emptyList();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches the full description for each (already pre-filtered + deduped) job via the per-job
     * endpoint. A missing description is tolerated — the title filter and Gemini can still
     * classify.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void fetchDescriptions(List<JobPosting> jobs) {
        for (JobPosting job : jobs) {
            try {
                Map<String, Object> detail =
                        webClient
                                .get()
                                .uri(JOB_URL, job.getCompany(), job.getExternalId())
                                .retrieve()
                                .bodyToMono(
                                        new ParameterizedTypeReference<Map<String, Object>>() {})
                                .block();
                if (detail != null && detail.get("content") != null) {
                    job.setDescription(stripHtml(detail.get("content").toString()));
                }
            } catch (Exception e) {
                log.debug(
                        "Failed to fetch Greenhouse description for [{}] {}: {}",
                        job.getCompany(),
                        job.getExternalId(),
                        e.getMessage());
            }
        }
    }

    private JobPosting toJobPosting(String company, Map<String, Object> job) {
        String locationName = "";
        Object locationObj = job.get("location");
        if (locationObj instanceof Map<?, ?> locMap && locMap.get("name") != null) {
            locationName = locMap.get("name").toString();
        }

        Instant postedDate = parseInstant(job.get("updated_at"));

        return JobPosting.builder()
                .company(company)
                .externalId(String.valueOf(job.get("id")))
                .title(strOrEmpty(job.get("title")))
                .url(strOrEmpty(job.get("absolute_url")))
                .location(locationName)
                .description("") // deferred to fetchDescriptions()
                .postedDate(postedDate)
                .detectedAt(Instant.now())
                .build();
    }

    private static Instant parseInstant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String strOrEmpty(Object value) {
        return value != null ? value.toString() : "";
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
