package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.WorkdayProperties;
import com.github.jingyangyu.scmjobnotifier.config.WorkdayProperties.WorkdayCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

/**
 * Scraper for companies that use Workday as their ATS. Posts search requests to the Workday CXS
 * career site API ({@code {subdomain}.wd{N}.myworkdayjobs.com/wday/cxs/{subdomain}/{site}/jobs})
 * and paginates through all results.
 */
@Slf4j
@Component
public class WorkdayScraper implements JobScraper {

    private static final int PAGE_SIZE = 20;

    /** Safety cap (2000 jobs) so a misbehaving tenant can't loop forever. */
    private static final int MAX_PAGES = 100;

    /**
     * Retry on HTTP 429 with exponential backoff + jitter. Necessary because ~40 Workday companies
     * poll in parallel (8-thread pool) and Workday's CXS API rate-limits under that load, which
     * would otherwise cut pagination short (partial ~40 jobs per company). Jitter de-synchronizes
     * the retries so the pool doesn't stampede.
     */
    private static final Retry RATE_LIMIT_RETRY =
            Retry.backoff(4, Duration.ofSeconds(2))
                    .maxBackoff(Duration.ofSeconds(20))
                    .jitter(0.5)
                    .filter(WorkdayScraper::isRateLimited);

    private final WebClient webClient;
    private final WorkdayProperties properties;

    private static boolean isRateLimited(Throwable t) {
        return t instanceof WebClientResponseException e && e.getStatusCode().value() == 429;
    }

    public WorkdayScraper(WebClient.Builder webClientBuilder, WorkdayProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        log.info(
                "Workday scraper initialized with {} company(ies)",
                properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "workday";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(WorkdayCompany::getName).toList();
    }

    /**
     * Scrapes all job postings for a Workday company via paginated API calls.
     *
     * <p>Workday's CXS API returns jobs in pages of {@value #PAGE_SIZE}. We iterate until {@code
     * offset >= total}. On failure mid-pagination, we return partial results rather than nothing —
     * better to process some jobs than lose an entire company's listings.
     *
     * <p>Note: Workday does not provide a posted date in the search results, so {@code postedDate}
     * is always null for Workday jobs. This means the freshness filter (Tier 0) will accept all
     * Workday jobs.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        Optional<WorkdayCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No Workday config found for company: {}", company);
            return Collections.emptyList();
        }

        WorkdayCompany config = configOpt.get();
        List<JobPosting> allJobs = new ArrayList<>();
        int offset = 0;
        // Workday's CXS reports the real "total" only on the FIRST page (0 on later pages), so we
        // capture it once and never overwrite with 0 — otherwise the loop stops after page 2 (40
        // jobs). We also stop when a page returns no postings, with a MAX_PAGES safety cap.
        int total = 0;

        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                Map<String, Object> response = fetchPage(config, offset);
                if (response == null) {
                    break;
                }

                int pageTotal = ((Number) response.getOrDefault("total", 0)).intValue();
                if (pageTotal > 0) {
                    total = pageTotal;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> postings =
                        (List<Map<String, Object>>)
                                response.getOrDefault("jobPostings", Collections.emptyList());
                if (postings.isEmpty()) {
                    break;
                }

                for (Map<String, Object> posting : postings) {
                    allJobs.add(toJobPosting(company, config, posting));
                }

                offset += PAGE_SIZE;
                if (offset >= total) {
                    break;
                }
            }

            log.info("Workday [{}]: scraped {} total job(s)", company, allJobs.size());
            return allJobs;
        } catch (Exception e) {
            log.error("Failed to scrape Workday for company: {}", company, e);
            return allJobs; // Return partial results — better than losing everything
        }
    }

    private Map<String, Object> fetchPage(WorkdayCompany config, int offset) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appliedFacets", Collections.emptyMap());
        body.put("limit", PAGE_SIZE);
        body.put("offset", offset);
        body.put("searchText", "");

        return webClient
                .post()
                .uri(config.apiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .retryWhen(RATE_LIMIT_RETRY)
                .block();
    }

    private JobPosting toJobPosting(
            String company, WorkdayCompany config, Map<String, Object> posting) {
        String title = (String) posting.getOrDefault("title", "");
        String externalPath = (String) posting.getOrDefault("externalPath", "");
        String location = (String) posting.getOrDefault("locationsText", "");
        // Metadata only — the description is deferred to fetchDescriptions() (post-dedup) so we
        // don't hammer Workday with one detail request per job (which triggers HTTP 429). None of
        // the pre-filters (freshness/exclude/California/SCM) need the description.
        return JobPosting.builder()
                .company(company)
                .externalId(externalPath)
                .title(title)
                .url(config.jobUrl(externalPath))
                .location(location)
                .description("")
                .postedDate(null)
                .detectedAt(Instant.now())
                .notified(false)
                .build();
    }

    /**
     * Fetches full descriptions for the given (already pre-filtered + deduped) jobs. Called by the
     * poll after dedup, so only the small surviving set pays for detail requests — a handful per
     * company instead of thousands. Each job's {@code externalId} is its Workday {@code
     * externalPath}; its company config is looked up by name.
     */
    @Override
    public void fetchDescriptions(List<JobPosting> jobs) {
        for (JobPosting job : jobs) {
            Optional<WorkdayCompany> configOpt = properties.findByName(job.getCompany());
            if (configOpt.isEmpty()) {
                continue;
            }
            job.setDescription(fetchJobDescription(configOpt.get(), job.getExternalId()));
        }
    }

    /**
     * Fetches the full job description from Workday's detail endpoint. Returns empty string on any
     * failure — a missing description is acceptable since the title filter and Gemini can still
     * classify based on title alone.
     */
    @SuppressWarnings("unchecked")
    private String fetchJobDescription(WorkdayCompany config, String externalPath) {
        try {
            // Detail URL: {baseUrl}/wday/cxs/{subdomain}/{site}{externalPath}
            String detailUrl =
                    String.format(
                            "%s/wday/cxs/%s/%s%s",
                            config.baseUrl(),
                            config.getSubdomain(),
                            config.getSite(),
                            externalPath);
            Map<String, Object> detail =
                    webClient
                            .get()
                            .uri(detailUrl)
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .retryWhen(RATE_LIMIT_RETRY)
                            .block();
            if (detail == null) {
                return "";
            }
            Map<String, Object> jobInfo = (Map<String, Object>) detail.get("jobPostingInfo");
            if (jobInfo == null) {
                return "";
            }
            Object desc = jobInfo.get("jobDescription");
            if (desc instanceof String descStr) {
                return stripHtml(descStr);
            }
            return "";
        } catch (Exception e) {
            log.debug(
                    "Failed to fetch Workday job detail for {}: {}", externalPath, e.getMessage());
            return "";
        }
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
