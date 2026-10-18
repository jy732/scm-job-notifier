package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.AvatureProperties;
import com.github.jingyangyu.scmjobnotifier.config.AvatureProperties.AvatureCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scraper for Avature career portals (e.g. {@code avature.sandisk.com}).
 *
 * <p>The job list is served by a JSON endpoint, {@code https://{host}/{portalId}/_portalList},
 * which needs a session cookie plus a set of widget parameters. Three of those parameters — {@code
 * qtvc}, {@code links} and the session cookie — are each independently mandatory: omit any one and
 * Avature answers {@code 403 "This list is private. Please contact your Avature representative."}
 * That message is misleading; nothing is actually private and there is no bot defence or request
 * signing.
 *
 * <p>So this is a two-step scrape, and nothing tenant-specific is hardcoded:
 *
 * <ol>
 *   <li>GET the search page, keeping the {@code ScustomPortal-{portalId}} cookie. The page embeds a
 *       single {@code <list data-props='{...}'>} element whose JSON carries <em>every</em>
 *       parameter the endpoint wants, so the request is rebuilt verbatim from the tenant's own
 *       config.
 *   <li>GET {@code _portalList} per page of {@value #PAGE_SIZE}, which is the server's cap —
 *       requesting more silently returns 50.
 * </ol>
 *
 * <p>Results carry the <b>full job description</b>, so {@link #fetchDescriptions} stays a no-op and
 * a whole board costs one request per 50 jobs with no per-job detail fetches.
 *
 * <p>Result field keys are per-tenant and opaque ({@code schemaField_3_198_3} is Sandisk's City),
 * but every response ships a {@code columns[]} array mapping each code to a human label, so
 * location and description are resolved <b>by label</b> rather than by hardcoded code — which is
 * what lets one adapter serve any {@code _portalList} tenant. Only {@code name}, {@code postedDate}
 * and {@code req} are stable Avature codes.
 */
@Slf4j
@Component
public class AvatureScraper implements JobScraper {

    /** Avature caps the page size at 50 regardless of what is requested. */
    private static final int PAGE_SIZE = 50;

    /** 2,000-job ceiling, so a misconfigured tenant cannot paginate forever. */
    private static final int MAX_PAGES = 40;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    /**
     * The list widget's config blob. {@code data-props} is single-quoted, so JSON quotes survive.
     */
    private static final Pattern LIST_PROPS = Pattern.compile("<list\\b[^>]*data-props='([^']*)'");

    /** Portal id, taken from the session cookie name ({@code ScustomPortal-13}). */
    private static final Pattern PORTAL_COOKIE = Pattern.compile("ScustomPortal-(\\d+)");

    /** Fallback portal id source: asset paths on the page ({@code /portal/13/}). */
    private static final Pattern PORTAL_PATH = Pattern.compile("/portal/(\\d+)/");

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AvatureProperties properties;

    public AvatureScraper(WebClient.Builder webClientBuilder, AvatureProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        log.info(
                "Avature scraper initialized with {} company(ies)",
                properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "avature";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(AvatureCompany::getName).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Bootstraps the portal config, then paginates the list endpoint de-duplicating by job id.
     * On failure mid-scrape, returns the pages already collected rather than nothing.
     */
    @Override
    public List<JobPosting> scrape(String company) {
        Optional<AvatureCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No Avature config found for company: {}", company);
            return Collections.emptyList();
        }

        AvatureCompany config = configOpt.get();
        Map<String, JobPosting> byId = new LinkedHashMap<>();

        try {
            Portal portal = bootstrap(config);
            if (portal == null) {
                log.warn(
                        "Avature [{}]: no JobList widget on {} — portal may be server-rendered"
                                + " (unsupported flavour) or the page layout changed",
                        company,
                        config.searchUrl());
                return Collections.emptyList();
            }

            for (int page = 0; page < MAX_PAGES; page++) {
                String body = fetchList(config, portal, page * PAGE_SIZE);
                if (body == null || body.isBlank()) {
                    break;
                }
                JsonNode root = objectMapper.readTree(body);
                JsonNode results = root.path("results");
                if (!results.isArray() || results.isEmpty()) {
                    break;
                }
                Map<String, String> codeByLabel = labelIndex(root.path("columns"));
                for (JsonNode result : results) {
                    JobPosting job = toJobPosting(company, config, result, codeByLabel);
                    if (job != null) {
                        byId.putIfAbsent(job.getExternalId(), job);
                    }
                }
                if (results.size() < PAGE_SIZE) {
                    break;
                }
            }
            log.info("Avature [{}]: scraped {} job(s)", company, byId.size());
        } catch (Exception e) {
            log.error("Failed to scrape Avature for company: {}", company, e);
        }
        return new ArrayList<>(byId.values());
    }

    /** Session cookie, portal id and list-widget config, all read from the search page. */
    private record Portal(String cookie, String portalId, JsonNode props) {}

    /**
     * GETs the search page and pulls out everything the list endpoint needs. Returns {@code null}
     * when the page carries no {@code JobList} widget — i.e. a server-rendered Avature portal,
     * which this adapter does not support.
     */
    private Portal bootstrap(AvatureCompany config) {
        String[] jarAndBody =
                webClient
                        .get()
                        .uri(URI.create(config.searchUrl()))
                        .header(HttpHeaders.USER_AGENT, USER_AGENT)
                        .exchangeToMono(
                                resp -> {
                                    String jar =
                                            resp.cookies().values().stream()
                                                    .filter(values -> !values.isEmpty())
                                                    .map(values -> values.get(0))
                                                    .map(c -> c.getName() + "=" + c.getValue())
                                                    .collect(Collectors.joining("; "));
                                    return resp.bodyToMono(String.class)
                                            .defaultIfEmpty("")
                                            .map(body -> new String[] {jar, body});
                                })
                        .defaultIfEmpty(new String[] {"", ""})
                        .block();

        String cookie = jarAndBody[0];
        String html = jarAndBody[1];

        JsonNode props = findJobListProps(html);
        if (props == null) {
            return null;
        }
        String portalId = portalId(cookie, html);
        if (portalId == null) {
            return null;
        }
        return new Portal(cookie, portalId, props);
    }

    /**
     * Returns the {@code data-props} JSON of the page's {@code JobList} widget, or {@code null} if
     * there is none. A portal may host several list widgets, so match on {@code listType}.
     */
    private JsonNode findJobListProps(String html) {
        Matcher m = LIST_PROPS.matcher(html);
        while (m.find()) {
            JsonNode props = objectMapper.readTree(unescapeHtml(m.group(1)));
            if ("JobList".equals(props.path("listType").asString(""))) {
                return props;
            }
        }
        return null;
    }

    /** Portal id from the session cookie name, falling back to the page's asset paths. */
    private static String portalId(String cookie, String html) {
        Matcher fromCookie = PORTAL_COOKIE.matcher(cookie);
        if (fromCookie.find()) {
            return fromCookie.group(1);
        }
        Matcher fromPath = PORTAL_PATH.matcher(html);
        return fromPath.find() ? fromPath.group(1) : null;
    }

    /**
     * Requests one page of the list, rebuilding the query from the widget's own config so no tenant
     * constant is baked in. {@code filters} is an empty search (the whole board — the California
     * and SCM pre-filters do the selecting downstream, as with the Workday and Ross scrapers).
     */
    private String fetchList(AvatureCompany config, Portal portal, int offset) {
        JsonNode p = portal.props();
        StringBuilder q = new StringBuilder();
        param(q, "uuid", p.path("uuid").asString(""));
        param(q, "hasToIncludePaginationOptions", bool(p, "hasToIncludePaginationOptions"));
        param(q, "allowListSorting", bool(p, "allowListSorting"));
        param(q, "fetchJobIdInPeopleLists", "true");
        param(q, "listType", p.path("listType").asString(""));
        param(q, "offset", String.valueOf(offset));
        param(q, "filters", "{\"search\":\"\"}");
        param(q, "sort", "");
        param(q, "sortDirection", "DESC");
        param(q, "recordsPerPage", String.valueOf(PAGE_SIZE));
        param(q, "token", "");
        param(q, "allowFilteringFromUrlParams", bool(p, "allowFilteringFromUrlParams"));
        param(q, "layout", p.path("layout").asString(""));
        param(q, "dynamicValueConfigs", json(p.path("dynamicValueConfigs"), "[]"));
        param(q, "shouldAddBase64FileFields", bool(p, "shouldAddBase64FileFields"));
        param(q, "searchMode", p.path("searchMode").asString(""));
        param(q, "qtvc", p.path("qtvc").asString(""));
        param(q, "pageUrlParams", "{}");
        param(q, "links", json(p.path("links"), "{}"));
        param(q, "formId", p.path("formId").asString(""));
        param(q, "searchIndexId", String.valueOf(p.path("searchIndexId").asInt(1)));

        String url = config.listUrl(portal.portalId()) + "?" + q;
        return webClient
                .get()
                .uri(URI.create(url))
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header(HttpHeaders.ACCEPT, "application/json, text/javascript, */*; q=0.01")
                .header(HttpHeaders.REFERER, config.searchUrl())
                .header(HttpHeaders.COOKIE, portal.cookie())
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private static void param(StringBuilder q, String name, String value) {
        if (q.length() > 0) {
            q.append('&');
        }
        q.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String bool(JsonNode props, String field) {
        return String.valueOf(props.path(field).asBoolean(false));
    }

    private String json(JsonNode node, String fallback) {
        return node.isMissingNode() || node.isNull()
                ? fallback
                : objectMapper.writeValueAsString(node);
    }

    /**
     * Maps each column's human label to its (per-tenant, opaque) field code, lowercased for
     * case-insensitive lookup.
     */
    private static Map<String, String> labelIndex(JsonNode columns) {
        Map<String, String> byLabel = new LinkedHashMap<>();
        if (columns.isArray()) {
            for (JsonNode column : columns) {
                String label = column.path("label").asString("");
                String code = column.path("code").asString("");
                if (!label.isEmpty() && !code.isEmpty()) {
                    byLabel.putIfAbsent(label.toLowerCase(java.util.Locale.ROOT), code);
                }
            }
        }
        return byLabel;
    }

    /** Builds a posting, or {@code null} when the row has no usable id or title. */
    private static JobPosting toJobPosting(
            String company,
            AvatureCompany config,
            JsonNode result,
            Map<String, String> codeByLabel) {
        String jobId = result.path("id").asString("");
        JsonNode fields = result.path("fields");
        String title = fields.path("name").path("stringValue").asString("");
        if (jobId.isEmpty() || title.isEmpty()) {
            return null;
        }
        return JobPosting.builder()
                .company(company)
                .externalId(jobId)
                .title(title.trim())
                .url(config.jobUrl(jobId))
                .location(location(fields, codeByLabel))
                .description(byLabel(fields, codeByLabel, "job description"))
                .postedDate(parseDate(fields.path("postedDate").path("stringValue").asString("")))
                .detectedAt(Instant.now())
                .notified(false)
                .build();
    }

    /**
     * Assembles "City, State, Country" from whichever of those labelled columns the tenant exposes.
     * The California pre-filter matches on the state name, which Avature spells out in full.
     */
    private static String location(JsonNode fields, Map<String, String> codeByLabel) {
        List<String> parts = new ArrayList<>();
        for (String label : List.of("city", "state", "country")) {
            String value = byLabel(fields, codeByLabel, label);
            if (!value.isBlank()) {
                parts.add(value.trim());
            }
        }
        return String.join(", ", parts);
    }

    private static String byLabel(JsonNode fields, Map<String, String> codeByLabel, String label) {
        String code = codeByLabel.get(label);
        return code == null ? "" : fields.path(code).path("stringValue").asString("");
    }

    /** Avature serves ISO dates ({@code 2026-10-16}); anything else is treated as absent. */
    private static Instant parseDate(String raw) {
        try {
            return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code data-props} is HTML-escaped inside its single-quoted attribute. */
    private static String unescapeHtml(String value) {
        return value.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
