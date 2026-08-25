package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for CA employers on the Jibe / iCIMS "careers-home" front-end, via its public {@code
 * /api/jobs} search endpoint.
 *
 * <p>Jibe-built career sites (owned by iCIMS) expose {@code {host}/api/jobs?keywords=&location=&
 * page={n}&limit=100&sortBy=posted_date} with no auth, returning JSON {@code jobs[].data} plus a
 * stable {@code totalCount}. Each configured company is a portal (just its {@code host}); because
 * every board here is small (&lt;1200 reqs) we scan the whole board and let the downstream SCM +
 * California pre-filter decide relevance — this avoids the fuzzy keyword-match false-negatives seen
 * on other ATSes. {@code full_location} already concatenates every location ("Palo Alto,
 * California; Irvine, California"), so multi-location CA roles are caught without parsing {@code
 * additional_locations}.
 *
 * <ul>
 *   <li><b>amd</b> — Santa Clara semiconductor SCM (migrated off Kenexa BrassRing to iCIMS)
 *   <li><b>rivian</b> — Palo Alto / Irvine EV-manufacturing SCM
 * </ul>
 */
@Slf4j
@Component
public class JibeScraper implements JobScraper {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 25;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";
    // iCIMS returns "2026-08-20T19:08:00+0000" — basic (colon-less) offset, so ISO_OFFSET can't
    // parse it directly.
    private static final DateTimeFormatter POSTED_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    /** One Jibe/iCIMS career site: {@code company} identifier and its {@code host}. */
    private record Portal(String company, String host) {}

    private static final List<Portal> PORTALS =
            List.of(
                    new Portal("amd", "careers.amd.com"),
                    new Portal("rivian", "careers.rivian.com"));

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public JibeScraper(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        this.objectMapper = objectMapper;
        log.info("Jibe scraper initialized ({} sites)", PORTALS.size());
    }

    @Override
    public String platform() {
        return "jibe";
    }

    @Override
    public List<String> companies() {
        return PORTALS.stream().map(Portal::company).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Portal portal =
                PORTALS.stream().filter(p -> p.company().equals(company)).findFirst().orElse(null);
        if (portal == null) {
            log.warn("Jibe: no portal for company '{}'", company);
            return List.of();
        }
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            int before = byId.size();
            int returned = fetchPage(portal, page, byId);
            if (returned < PAGE_SIZE) {
                break;
            }
            if (byId.size() == before) {
                // no new ids on a full page — pagination exhausted / looping
                break;
            }
        }
        log.info("Jibe [{}]: {} unique posting(s) scanned", company, byId.size());
        return new ArrayList<>(byId.values());
    }

    /** Fetches one page into {@code byId}; returns the number of jobs the API returned. */
    private int fetchPage(Portal portal, int page, Map<String, JobPosting> byId) {
        String uri =
                "https://"
                        + portal.host()
                        + "/api/jobs?keywords=&location=&page="
                        + page
                        + "&limit="
                        + PAGE_SIZE
                        + "&sortBy=posted_date";
        String resp;
        try {
            resp =
                    webClient
                            .get()
                            .uri(java.net.URI.create(uri))
                            .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
        } catch (Exception e) {
            log.error("Jibe [{}] page {} failed", portal.company(), page, e);
            return 0;
        }
        if (resp == null) {
            return 0;
        }
        JsonNode jobs = objectMapper.readTree(resp).path("jobs");
        if (!jobs.isArray() || jobs.isEmpty()) {
            return 0;
        }
        for (JsonNode j : jobs) {
            JobPosting posting = toJobPosting(portal, j.path("data"));
            if (posting != null && !posting.getExternalId().isEmpty()) {
                byId.putIfAbsent(posting.getExternalId(), posting);
            }
        }
        return jobs.size();
    }

    private JobPosting toJobPosting(Portal portal, JsonNode d) {
        String id = d.path("slug").asString(d.path("req_id").asString(""));
        if (id.isEmpty()) {
            return null;
        }
        String url = d.path("apply_url").asString("");
        if (url.isEmpty()) {
            url = "https://" + portal.host() + "/careers-home/jobs/" + id;
        }
        // full_location concatenates every location with "; " — covers multi-location CA roles.
        String location = d.path("full_location").asString(d.path("short_location").asString(""));
        return JobPosting.builder()
                .company(portal.company())
                .externalId(id)
                .title(d.path("title").asString(""))
                .url(url)
                .location(location)
                .description(d.path("description").asString(""))
                .postedDate(parsePosted(d.path("posted_date").asString("")))
                .detectedAt(Instant.now())
                .notified(false)
                .build();
    }

    private static Instant parsePosted(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw, POSTED_FMT).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
