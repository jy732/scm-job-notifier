package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.RadancyProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for CA employers on Radancy (TalentBrew), via its public {@code /search-jobs/results}
 * endpoint.
 *
 * <p>The endpoint returns JSON that <em>wraps a fragment of HTML</em> ({@code {"results": "<section
 * …>"}}) rather than structured rows, so the rows are parsed out of that fragment: each {@code
 * <li>} carries the job id, href, title, location and posting date. {@code data-total-pages} on the
 * wrapper section bounds pagination.
 *
 * <p>We scan the whole board (empty {@code Keywords}/{@code Location}) and let the downstream
 * California + SCM pre-filters select — see {@link RadancyProperties.RadancyCompany#searchUrl} for
 * why the server-side filters are not usable. Listing rows carry no description, so {@link
 * #fetchDescriptions} pulls each surviving job's {@code ats-description} block post-dedup.
 *
 * <ul>
 *   <li><b>boeing</b> — Huntington Beach / El Segundo / Long Beach aerospace SCM
 * </ul>
 */
@Slf4j
@Component
public class RadancyScraper implements JobScraper {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 30;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    private static final Pattern TOTAL_PAGES = Pattern.compile("data-total-pages=\"(\\d+)\"");

    /** Row anchor: the href and the job id always sit on the same {@code <a>}. */
    private static final Pattern ROW_LINK =
            Pattern.compile(
                    "<a\\b[^>]*class=\"search-results__job-link\"[^>]*href=\"([^\"]+)\""
                            + "[^>]*data-job-id=\"([^\"]+)\"");

    private static final Pattern ROW_TITLE =
            Pattern.compile(
                    "class=\"search-results__job-title\"[^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern ROW_LOCATION =
            Pattern.compile(
                    "class=\"search-results__job-info location\"[^>]*>(.*?)</span>",
                    Pattern.DOTALL);
    private static final Pattern ROW_DATE =
            Pattern.compile(
                    "class=\"search-results__job-info date\"[^>]*>(.*?)</span>", Pattern.DOTALL);
    // The description body holds only inline/paragraph markup, no nested <div>, so the first
    // closing tag IS the container's. Matching a "</div></div>" pair instead runs past it and
    // swallows the page's marketing footer (40k chars of "Boeing in Germany" on a real req).
    private static final Pattern DESCRIPTION =
            Pattern.compile("id=\"ats-description\"[^>]*>(.*?)</div>", Pattern.DOTALL);

    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(?:x([0-9a-fA-F]+)|(\\d+));");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final RadancyProperties properties;

    public RadancyScraper(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            RadancyProperties properties) {
        this.webClient =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        log.info("Radancy scraper initialized ({} sites)", properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "radancy";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream()
                .map(RadancyProperties.RadancyCompany::getName)
                .toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        RadancyProperties.RadancyCompany config = properties.findByName(company).orElse(null);
        if (config == null) {
            log.warn("Radancy: no portal for company '{}'", company);
            return List.of();
        }
        Map<String, JobPosting> byId = new LinkedHashMap<>();
        try {
            int totalPages = MAX_PAGES;
            for (int page = 1; page <= Math.min(totalPages, MAX_PAGES); page++) {
                String html = fetchResults(config, page);
                if (html.isBlank()) {
                    break;
                }
                if (page == 1) {
                    totalPages = totalPages(html);
                }
                int before = byId.size();
                parseRows(config, company, html, byId);
                if (byId.size() == before) {
                    // a page that adds no new ids means pagination is exhausted or looping
                    break;
                }
            }
            log.info("Radancy [{}]: scraped {} job(s)", company, byId.size());
        } catch (Exception e) {
            log.error("Radancy [{}] scrape failed", company, e);
        }
        return new ArrayList<>(byId.values());
    }

    /** Fetches one results page and unwraps the HTML fragment from the JSON envelope. */
    private String fetchResults(RadancyProperties.RadancyCompany config, int page) {
        String body =
                webClient
                        .get()
                        .uri(java.net.URI.create(config.searchUrl(page, PAGE_SIZE)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header(
                                HttpHeaders.ACCEPT,
                                "application/json, text/javascript, */*; q=0.01")
                        .header(HttpHeaders.REFERER, config.searchPageUrl())
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
        if (body == null || body.isBlank()) {
            return "";
        }
        JsonNode root = objectMapper.readTree(body);
        return root.path("results").asString("");
    }

    /** Reads {@code data-total-pages}; falls back to {@link #MAX_PAGES} when absent. */
    private static int totalPages(String html) {
        Matcher m = TOTAL_PAGES.matcher(html);
        return m.find() ? Integer.parseInt(m.group(1)) : MAX_PAGES;
    }

    /**
     * Splits the fragment on row anchors and reads each row's fields from the text that follows it
     * — the title/location/date spans are siblings of the anchor, not children, so they can only be
     * matched relative to it.
     */
    private void parseRows(
            RadancyProperties.RadancyCompany config,
            String company,
            String html,
            Map<String, JobPosting> byId) {
        List<Anchor> anchors = new ArrayList<>();
        Matcher links = ROW_LINK.matcher(html);
        while (links.find()) {
            anchors.add(new Anchor(links.group(2), links.group(1), links.end()));
        }
        for (int i = 0; i < anchors.size(); i++) {
            Anchor anchor = anchors.get(i);
            int to = i + 1 < anchors.size() ? anchors.get(i + 1).start() : html.length();
            String row = html.substring(anchor.start(), to);
            if (anchor.id().isBlank() || byId.containsKey(anchor.id())) {
                continue;
            }
            byId.put(
                    anchor.id(),
                    JobPosting.builder()
                            .company(company)
                            .externalId(anchor.id())
                            .title(field(ROW_TITLE, row))
                            .url(config.jobUrl(anchor.href()))
                            .location(field(ROW_LOCATION, row))
                            .description("")
                            .postedDate(parseDate(field(ROW_DATE, row)))
                            .detectedAt(Instant.now())
                            .notified(false)
                            .build());
        }
    }

    /**
     * Pulls each job's description from its detail page. Called post-dedup, so only unseen jobs pay
     * for the extra request.
     */
    @Override
    public void fetchDescriptions(List<JobPosting> jobs) {
        for (JobPosting job : jobs) {
            try {
                String html =
                        webClient
                                .get()
                                .uri(java.net.URI.create(job.getUrl()))
                                .header(HttpHeaders.ACCEPT, "text/html")
                                .retrieve()
                                .bodyToMono(String.class)
                                .block();
                if (html == null) {
                    continue;
                }
                Matcher m = DESCRIPTION.matcher(html);
                if (m.find()) {
                    job.setDescription(strip(m.group(1)));
                }
            } catch (Exception e) {
                log.warn("Radancy: description fetch failed for {}", job.getUrl(), e);
            }
        }
    }

    /** One row's {@code <a>}: its job id, href, and where its sibling spans start in the HTML. */
    private record Anchor(String id, String href, int start) {}

    private static String field(Pattern pattern, String row) {
        Matcher m = pattern.matcher(row);
        return m.find() ? strip(m.group(1)) : "";
    }

    private static String strip(String s) {
        return unescape(TAGS.matcher(s).replaceAll(" ")).replaceAll("\\s+", " ").trim();
    }

    /**
     * Decodes the named entities the markup actually uses, then any numeric reference. Boeing's
     * descriptions carry hex references ({@code &#x27;}, {@code &#x2011;}) as well as decimal ones,
     * so the named-only replace chain the other HTML scrapers use is not enough here.
     */
    private static String unescape(String s) {
        String named =
                s.replace("&amp;", "&")
                        .replace("&nbsp;", " ")
                        .replace("&quot;", "\"")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">");
        Matcher m = NUMERIC_ENTITY.matcher(named);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int code =
                    m.group(1) != null
                            ? Integer.parseInt(m.group(1), 16)
                            : Integer.parseInt(m.group(2));
            m.appendReplacement(out, Matcher.quoteReplacement(Character.toString(code)));
        }
        return m.appendTail(out).toString();
    }

    private static Instant parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DATE_FMT).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
