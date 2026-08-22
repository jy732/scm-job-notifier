package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.OracleCloudProperties;
import com.github.jingyangyu.scmjobnotifier.config.OracleCloudProperties.OracleCloudCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

/**
 * Scraper for companies using Oracle Cloud HCM (formerly Taleo). Fetches job requisitions from the
 * Oracle Recruiting Cloud REST API.
 */
@Slf4j
@Component
public class OracleCloudScraper implements JobScraper {

    private static final int PAGE_SIZE = 25;

    /**
     * Safety cap on pagination. Oracle Recruiting has no recency window, so a huge tenant (e.g.
     * Albertsons ~7k reqs) would otherwise page forever; the per-company poll timeout would cut it
     * off mid-scrape anyway. Bounds the scrape to {@value} × {@link #PAGE_SIZE} newest-listed reqs.
     */
    private static final int MAX_PAGES = 80;

    /** SCM keyword queries for {@code keywordFiltered} tenants (union de-duplicated by req id). */
    private static final List<String> SCM_QUERIES =
            List.of(
                    "supply chain",
                    "procurement",
                    "logistics",
                    "planner",
                    "buyer",
                    "inventory",
                    "distribution",
                    "sourcing");

    private final WebClient webClient;
    private final OracleCloudProperties properties;

    public OracleCloudScraper(
            WebClient.Builder webClientBuilder, OracleCloudProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        log.info(
                "Oracle Cloud scraper initialized with {} company(ies)",
                properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "oraclecloud";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(OracleCloudCompany::getName).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Paginates through the Oracle Recruiting Cloud REST API ({@code
     * /hcmRestApi/resources/latest/recruitingCEJobRequisitions}) in batches of {@value #PAGE_SIZE}.
     * The API URL is constructed from per-company config (subdomain, region, site number). Each
     * company config maps to a specific Oracle Cloud HCM instance. On failure mid-pagination,
     * returns partial results.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<JobPosting> scrape(String company) {
        Optional<OracleCloudCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No Oracle Cloud config found for company: {}", company);
            return Collections.emptyList();
        }

        OracleCloudCompany config = configOpt.get();
        Map<String, JobPosting> byId = new LinkedHashMap<>();

        try {
            if (config.isKeywordFiltered()) {
                // Huge tenant (grocery/retail) — scrape by SCM keyword so the relevant roles aren't
                // buried past MAX_PAGES behind thousands of store jobs.
                for (String query : SCM_QUERIES) {
                    try {
                        paginate(company, config, query, byId);
                    } catch (Exception e) {
                        log.warn(
                                "Oracle Cloud [{}] query '{}' failed: {}",
                                company,
                                query,
                                e.getMessage());
                    }
                }
                log.info(
                        "Oracle Cloud [{}]: scraped {} SCM candidate(s) via keyword",
                        company,
                        byId.size());
            } else {
                paginate(company, config, null, byId);
                log.info("Oracle Cloud [{}]: scraped {} total job(s)", company, byId.size());
            }
            return new ArrayList<>(byId.values());
        } catch (Exception e) {
            log.error("Failed to scrape Oracle Cloud for company: {}", company, e);
            return new ArrayList<>(byId.values());
        }
    }

    /** Paginates one query (or the whole board when {@code keyword} is null) into {@code byId}. */
    @SuppressWarnings("unchecked")
    private void paginate(
            String company,
            OracleCloudCompany config,
            String keyword,
            Map<String, JobPosting> byId) {
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> response = fetchPage(config, offset, keyword);
            if (response == null) {
                break;
            }
            List<Map<String, Object>> items =
                    (List<Map<String, Object>>)
                            response.getOrDefault("items", Collections.emptyList());
            if (items.isEmpty()) {
                break;
            }
            // The first item contains requisitionList and TotalJobsCount
            Map<String, Object> wrapper = items.get(0);
            int totalJobs = ((Number) wrapper.getOrDefault("TotalJobsCount", 0)).intValue();
            List<Map<String, Object>> requisitions =
                    (List<Map<String, Object>>)
                            wrapper.getOrDefault("requisitionList", Collections.emptyList());
            for (Map<String, Object> req : requisitions) {
                JobPosting posting = toJobPosting(company, config, req);
                byId.putIfAbsent(posting.getExternalId(), posting);
            }
            offset += PAGE_SIZE;
            if (offset >= totalJobs || requisitions.isEmpty()) {
                break;
            }
        }
    }

    private Map<String, Object> fetchPage(OracleCloudCompany config, int offset, String keyword) {
        return webClient
                .get()
                // Pass a URI (not a String) so WebClient uses the already-encoded URL verbatim —
                // otherwise it re-encodes the %-escapes (e.g. keyword=%22..%22 -> %2522..%2522),
                // which silently mangles the keyword filter to 0 results.
                .uri(java.net.URI.create(config.apiUrl(PAGE_SIZE, offset, keyword)))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    private JobPosting toJobPosting(
            String company, OracleCloudCompany config, Map<String, Object> req) {
        Object reqId = req.getOrDefault("Id", req.get("RequisitionId"));
        String title = strOrEmpty(req.get("Title"));
        String location = strOrEmpty(req.get("PrimaryLocation"));
        String description = stripHtml(strOrEmpty(req.get("ExternalDescriptionStr")));
        String qualifications = stripHtml(strOrEmpty(req.get("ExternalQualificationsStr")));
        if (!qualifications.isEmpty()) {
            description = description + " " + qualifications;
        }

        Instant postedDate = parseDate(req.get("PostedDate"));

        return JobPosting.builder()
                .company(company)
                .externalId(String.valueOf(reqId))
                .title(title)
                .url(config.jobUrl(reqId))
                .location(location)
                .description(description.trim())
                .postedDate(postedDate)
                .detectedAt(Instant.now())
                .build();
    }

    private static Instant parseDate(Object value) {
        if (value == null) return null;
        try {
            String str = value.toString();
            // Oracle Cloud dates can be "2024-06-15" or ISO instant
            if (str.length() == 10 && str.charAt(4) == '-') {
                return LocalDate.parse(str).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(str);
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
