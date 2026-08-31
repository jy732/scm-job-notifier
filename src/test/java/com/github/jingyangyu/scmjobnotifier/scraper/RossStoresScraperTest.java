package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class RossStoresScraperTest {

    private static String records() {
        String recent =
                LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("M/d/yyyy"));
        return "{\"Records\":[{\"ID\":\"r1\",\"Title\":\"Buyer\",\"ReferenceNumber\":\"REF1\","
                + "\"PostedDate\":\""
                + recent
                + "\",\"CityStateData\":\"San Jose, CA\"}]}";
    }

    // bootstrap URL returns empty (cookies read from headers); search URL returns the JSON
    private static String body(String url) {
        return url.contains("SearchResults") ? records() : "";
    }

    private RossStoresScraper scraper() {
        return new RossStoresScraper(
                WebClientStubs.text(RossStoresScraperTest::body, "application/json"),
                new ObjectMapper());
    }

    @Test
    void platform() {
        assertThat(scraper().platform()).isEqualTo("rossstores");
    }

    @Test
    void scrapeParsesRecentRecords() {
        List<JobPosting> jobs = scraper().scrape("rossstores");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getCompany()).isEqualTo("rossstores");
        assertThat(j.getExternalId()).isEqualTo("r1");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).isEqualTo("San Jose, CA");
        assertThat(j.getUrl()).contains("REF1");
    }

    @Test
    void emptyOnError() {
        RossStoresScraper s = new RossStoresScraper(WebClientStubs.erroring(), new ObjectMapper());
        assertThat(s.scrape("rossstores")).isEmpty();
    }

    @Test
    void companiesReturnsRossStores() {
        assertThat(scraper().companies()).containsExactly("rossstores");
    }

    @Test
    void blankSearchBodyBreaks() {
        RossStoresScraper s =
                new RossStoresScraper(
                        WebClientStubs.text(u -> "", "application/json"), new ObjectMapper());
        assertThat(s.scrape("rossstores")).isEmpty();
    }

    @Test
    void emptyRecordsArrayBreaks() {
        RossStoresScraper s =
                new RossStoresScraper(
                        WebClientStubs.text(
                                u -> u.contains("SearchResults") ? "{\"Records\":[]}" : "",
                                "application/json"),
                        new ObjectMapper());
        assertThat(s.scrape("rossstores")).isEmpty();
    }

    @Test
    void bootstrapCollectsSessionCookies() {
        ExchangeFunction ex =
                req -> {
                    String u = req.url().toString();
                    if (u.contains("SearchResults")) {
                        return Mono.just(
                                ClientResponse.create(HttpStatus.OK)
                                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .body(records())
                                        .build());
                    }
                    return Mono.just(
                            ClientResponse.create(HttpStatus.OK)
                                    .cookie("JSESSIONID", "abc123")
                                    .cookie("XSRF", "tok")
                                    .body("")
                                    .build());
                };
        RossStoresScraper s =
                new RossStoresScraper(WebClient.builder().exchangeFunction(ex), new ObjectMapper());
        assertThat(s.scrape("rossstores")).hasSize(1);
    }

    @Test
    void missingAndUnparseableDatesTreatedAsRecent() {
        String recs =
                "{\"Records\":["
                        + "{\"ID\":\"a\",\"Title\":\"Buyer\",\"ReferenceNumber\":\"RA\","
                        + "\"PostedDate\":\"\",\"CityStateData\":\"San Jose, CA\"},"
                        + "{\"ID\":\"b\",\"Title\":\"Planner\",\"ReferenceNumber\":\"RB\","
                        + "\"PostedDate\":\"not-a-date\",\"CityStateData\":\"Irvine, CA\"}]}";
        RossStoresScraper s =
                new RossStoresScraper(
                        WebClientStubs.text(
                                u -> u.contains("SearchResults") ? recs : "", "application/json"),
                        new ObjectMapper());
        List<JobPosting> jobs = s.scrape("rossstores");
        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).getPostedDate()).isNull(); // blank date -> null, kept
        assertThat(jobs.get(1).getPostedDate()).isNull(); // bad date -> null, kept
    }

    @Test
    void filtersOutStaleRecords() {
        String stale =
                "{\"Records\":[{\"ID\":\"r9\",\"Title\":\"Buyer\",\"ReferenceNumber\":\"REF9\","
                        + "\"PostedDate\":\"1/1/2000\",\"CityStateData\":\"San Jose, CA\"}]}";
        RossStoresScraper s =
                new RossStoresScraper(
                        WebClientStubs.text(
                                u -> u.contains("SearchResults") ? stale : "", "application/json"),
                        new ObjectMapper());
        assertThat(s.scrape("rossstores")).isEmpty(); // posted years ago -> dropped
    }
}
