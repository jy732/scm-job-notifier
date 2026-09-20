package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.config.JazzHrProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scraper for JazzHR boards ({@code {subdomain}.applytojob.com}).
 *
 * <p>JazzHR server-renders the whole board on {@code /apply/} — one list item per role carrying the
 * detail link ({@code /apply/{code}/{slug}}), the title, and a map-marker "City, ST" line. That is
 * everything the filter needs, so this is a single fetch per employer with no paging and no detail
 * round-trip (the boards are small, typically tens of roles). Postings carry no machine-readable
 * date, so {@code postedDate} is left null and the pipeline falls back to first-seen recency.
 *
 * <p>The regex walks item-by-item rather than matching href/location globally, so a role whose card
 * omits the location can't borrow the next role's city — it simply yields a blank location and is
 * dropped by the California filter.
 */
@Slf4j
@Component
public class JazzHrScraper implements JobScraper {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    /** One board list item: the apply link + title, then (optionally) the map-marker location. */
    private static final Pattern ITEM =
            Pattern.compile(
                    "<a\\s+href=\"https://[a-z0-9-]+\\.applytojob\\.com/apply/([A-Za-z0-9]+)/"
                            + "([^\"]*)\"[^>]*>\\s*(.*?)\\s*</a>(.*?)(?=<a\\s+href=\"https://"
                            + "[a-z0-9-]+\\.applytojob\\.com/apply/|$)",
                    Pattern.DOTALL);

    private static final Pattern LOCATION =
            Pattern.compile("fa-map-marker'?\"?></i>\\s*([^<]{2,80})<", Pattern.DOTALL);

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private final WebClient webClient;
    private final JazzHrProperties properties;

    public JazzHrScraper(WebClient.Builder webClientBuilder, JazzHrProperties properties) {
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        log.info(
                "JazzHR scraper initialized with {} company(ies)",
                properties.getCompanies().size());
    }

    @Override
    public String platform() {
        return "jazzhr";
    }

    @Override
    public List<String> companies() {
        return List.copyOf(properties.getCompanies());
    }

    @Override
    public List<JobPosting> scrape(String company) {
        if (!properties.getCompanies().contains(company)) {
            log.warn("No JazzHR config found for company: {}", company);
            return List.of();
        }
        try {
            String html = fetch("https://" + company + ".applytojob.com/apply/");
            if (html == null || html.isBlank()) {
                log.warn("JazzHR [{}]: empty board page", company);
                return List.of();
            }
            List<JobPosting> jobs = new ArrayList<>();
            Matcher m = ITEM.matcher(html);
            while (m.find()) {
                String title = clean(m.group(3));
                if (title.isEmpty()) {
                    continue;
                }
                jobs.add(
                        JobPosting.builder()
                                .company(company)
                                .externalId(m.group(1))
                                .title(title)
                                .url(
                                        "https://"
                                                + company
                                                + ".applytojob.com/apply/"
                                                + m.group(1)
                                                + "/"
                                                + m.group(2))
                                .location(location(m.group(4)))
                                .description("")
                                .postedDate(null)
                                .detectedAt(Instant.now())
                                .notified(false)
                                .build());
            }
            log.info("JazzHR [{}]: scraped {} total job(s)", company, jobs.size());
            return jobs;
        } catch (Exception e) {
            log.error("Failed to scrape JazzHR for company: {}", company, e);
            return List.of();
        }
    }

    /** Reads the map-marker "City, ST" line out of one card's trailing markup. */
    private static String location(String itemTail) {
        Matcher m = LOCATION.matcher(itemTail);
        return m.find() ? clean(m.group(1)) : "";
    }

    /** Strips any nested tags and collapses the whitespace JazzHR's templates leave behind. */
    private static String clean(String raw) {
        return TAG.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").trim();
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
