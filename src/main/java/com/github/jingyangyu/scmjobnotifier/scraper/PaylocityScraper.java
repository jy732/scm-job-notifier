package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.PaylocityProperties;
import com.github.jingyangyu.scmjobnotifier.config.PaylocityProperties.PaylocityCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for Paylocity recruiting job boards.
 *
 * <p>Paylocity exposes no public JSON API (its {@code /recruiting/api/...} endpoints 302-redirect),
 * but the board page ({@code /recruiting/jobs/All/{companyId}/{slug}}) server-renders the full job
 * list as a {@code "Jobs":[...]} JSON array. This scraper fetches that page over plain HTTP,
 * extracts the array, splits it into per-job objects (both steps string-aware so brackets/braces
 * inside titles or descriptions don't confuse the boundaries), and pulls each field by regex — the
 * same dependency-free approach as the iCIMS/SuccessFactors scrapers, but over clean JSON so no
 * per-tenant markup guessing is needed. Records carry a machine-readable title, structured {@code
 * JobLocation.City/State}, a posting date, and a description snippet — so there is no separate
 * description fetch.
 */
@Slf4j
@Component
public class PaylocityScraper implements JobScraper {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    private static final Pattern JOB_ID = Pattern.compile("\"JobId\"\\s*:\\s*(\\d+)");
    private static final Pattern JOB_TITLE =
            Pattern.compile("\"JobTitle\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern PUBLISHED = Pattern.compile("\"PublishedDate\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern CITY = Pattern.compile("\"City\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern STATE = Pattern.compile("\"State\"\\s*:\\s*\"([^\"]*)\"");

    private final WebClient webClient;
    private final PaylocityProperties properties;

    public PaylocityScraper(WebClient.Builder webClientBuilder, PaylocityProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        log.info(
                "Paylocity scraper initialized with {} company(ies)",
                properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "paylocity";
    }

    @Override
    public List<String> companies() {
        return properties.getCompanies().stream().map(PaylocityCompany::getName).toList();
    }

    @Override
    public List<JobPosting> scrape(String company) {
        Optional<PaylocityCompany> configOpt = properties.findByName(company);
        if (configOpt.isEmpty()) {
            log.warn("No Paylocity config found for company: {}", company);
            return Collections.emptyList();
        }
        PaylocityCompany config = configOpt.get();
        try {
            String html = fetch(config.jobsUrl());
            String jobsArray = extractJobsArray(html);
            if (jobsArray == null) {
                log.warn("Paylocity [{}]: no Jobs array found on board page", company);
                return Collections.emptyList();
            }
            List<JobPosting> jobs = new ArrayList<>();
            for (String record : splitObjects(jobsArray)) {
                String title = group(JOB_TITLE, record);
                String id = group(JOB_ID, record);
                if (title == null || title.isBlank() || id == null) {
                    continue;
                }
                jobs.add(
                        JobPosting.builder()
                                .company(company)
                                .externalId(id)
                                .title(unescape(title).trim())
                                .url(config.jobUrl(Long.parseLong(id)))
                                .location(location(record))
                                .description("")
                                .postedDate(parseDate(group(PUBLISHED, record)))
                                .detectedAt(Instant.now())
                                .notified(false)
                                .build());
            }
            log.info("Paylocity [{}]: scraped {} total job(s)", company, jobs.size());
            return jobs;
        } catch (Exception e) {
            log.error("Failed to scrape Paylocity for company: {}", company, e);
            return Collections.emptyList();
        }
    }

    /** Combines the record's {@code JobLocation.City/State} into the "City, ST" form the filter wants. */
    private static String location(String record) {
        String city = group(CITY, record);
        String state = group(STATE, record);
        city = city == null ? "" : city.trim();
        state = state == null ? "" : state.trim();
        if (city.isEmpty()) {
            return state;
        }
        return state.isEmpty() ? city : city + ", " + state;
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static Instant parseDate(String published) {
        if (published == null || published.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(published).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the {@code "Jobs":[...]} array from the board HTML, balancing brackets while ignoring
     * any inside JSON string literals (so a "]" in a title/description doesn't end the array early).
     */
    private static String extractJobsArray(String html) {
        if (html == null) {
            return null;
        }
        int key = html.indexOf("\"Jobs\":");
        if (key < 0) {
            return null;
        }
        int start = html.indexOf('[', key);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int k = start; k < html.length(); k++) {
            char c = html.charAt(k);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return html.substring(start, k + 1);
                }
            }
        }
        return null;
    }

    /** Splits a JSON array string into its top-level {@code {...}} object substrings (string-aware). */
    private static List<String> splitObjects(String array) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int k = 0; k < array.length(); k++) {
            char c = array.charAt(k);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = k;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(array.substring(start, k + 1));
                    start = -1;
                }
            }
        }
        return out;
    }

    /** Unescapes the common JSON string escapes that appear in Paylocity titles. */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'n' -> b.append(' ');
                    case 't' -> b.append(' ');
                    case 'r' -> b.append(' ');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private String fetch(String url) {
        return webClient
                .get()
                .uri(url)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
