package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for Palo Alto Networks Careers ({@code jobs.paloaltonetworks.com}), which runs on Phenom
 * with <b>server-rendered</b> results (no usable JSON API — the page ships the job cards in HTML).
 *
 * <p>We GET the search-results page per SCM query ({@code /en/search-jobs/{keyword}/47263/{page}},
 * 30 cards/page) and extract each card via regex (Phenom's stable {@code
 * section29__search-results-*} classes). Santa Clara HQ, so many roles are CA (the pre-filter
 * enforces). Union de-duplicated by job id. Descriptions are deferred (title + location suffice for
 * the pre-filters).
 */
@Slf4j
@Component
public class PaloAltoNetworksScraper implements JobScraper {

    private static final String SEARCH_URL =
            "https://jobs.paloaltonetworks.com/en/search-jobs/%s/47263/%d";
    private static final int MAX_PAGES = 6;
    private static final int PAGE_SIZE = 30;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    /**
     * One card: href, job id, title, location (location may contain &lt;br/&gt; for multi-site).
     */
    private static final Pattern CARD =
            Pattern.compile(
                    "<a class=\"section29__search-results-link\" href=\"([^\"]+)\""
                            + " data-job-id=\"([^\"]+)\">\\s*"
                            + "<h2 class=\"section29__search-results-job-title\">([^<]+)</h2>"
                            + ".*?<span class=\"section29__result-location[^\"]*\">(.*?)</span>",
                    Pattern.DOTALL);

    /** SCM free-text queries. Union of results is de-duplicated by job id. */
    private static final List<String> SCM_QUERIES =
            List.of(
                    "supply chain",
                    "procurement",
                    "logistics",
                    "planner",
                    "buyer",
                    "sourcing",
                    "inventory");

    private final WebClient webClient;

    public PaloAltoNetworksScraper(WebClient.Builder webClientBuilder) {
        this.webClient =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        log.info(
                "Palo Alto Networks scraper initialized (Phenom SSR, {} SCM queries)",
                SCM_QUERIES.size());
    }

    @Override
    public String platform() {
        return "paloaltonetworks";
    }

    @Override
    public List<String> companies() {
        return List.of("paloaltonetworks");
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        for (String query : SCM_QUERIES) {
            try {
                fetchQuery(query, byId);
            } catch (Exception e) {
                log.debug("PANW query '{}' failed: {}", query, e.getMessage());
            }
        }
        log.info(
                "Palo Alto Networks: {} unique SCM candidate(s) across {} queries",
                byId.size(),
                SCM_QUERIES.size());
        return new ArrayList<>(byId.values());
    }

    private void fetchQuery(String query, Map<String, JobPosting> byId) {
        String kw = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        for (int page = 1; page <= MAX_PAGES; page++) {
            String html =
                    webClient
                            .get()
                            .uri(java.net.URI.create(String.format(SEARCH_URL, kw, page)))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
            if (html == null) {
                break;
            }
            int found = 0;
            Matcher m = CARD.matcher(html);
            while (m.find()) {
                found++;
                String id = m.group(2).trim();
                if (byId.containsKey(id)) {
                    continue;
                }
                String href = m.group(1).trim();
                String url =
                        href.startsWith("http") ? href : "https://jobs.paloaltonetworks.com" + href;
                byId.put(
                        id,
                        JobPosting.builder()
                                .company("paloaltonetworks")
                                .externalId(id)
                                .title(unescape(m.group(3).trim()))
                                .url(url)
                                .location(cleanLocation(m.group(4)))
                                .description("")
                                .postedDate(null)
                                .detectedAt(Instant.now())
                                .notified(false)
                                .build());
            }
            if (found < PAGE_SIZE) {
                break;
            }
        }
    }

    /** Location span may hold several sites separated by {@code <br/>}; flatten and de-tag. */
    private static String cleanLocation(String raw) {
        String flat = raw.replaceAll("(?i)<br\\s*/?>", "; ").replaceAll("<[^>]+>", " ");
        return unescape(flat).replaceAll("\\s*;\\s*", "; ").replaceAll("\\s+", " ").trim();
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&nbsp;", " ").replace("&#39;", "'").trim();
    }
}
