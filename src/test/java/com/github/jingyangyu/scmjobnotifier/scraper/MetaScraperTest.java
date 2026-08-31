package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.ProxyProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class MetaScraperTest {

    private static final String LSD_HTML = "<script>[\"LSD\",[],{\"token\":\"tok123\"}]</script>";
    private static final String JOBS =
            "{\"data\":{\"job_search_with_featured_jobs\":{\"all_jobs\":[{\"id\":\"mj1\","
                    + "\"title\":\"Supply Chain Analyst\",\"locations\":[\"Menlo Park, CA\"],"
                    + "\"description\":\"<p>d</p>\"}]}}}";

    private static ProxyProperties noProxy() {
        return new ProxyProperties();
    }

    private MetaScraper scraper(Function<String, String> byUrl) {
        return new MetaScraper(WebClientStubs.json(byUrl), new ObjectMapper(), noProxy());
    }

    private static String routed(String url, String graphqlBody) {
        if (url.contains("/graphql")) return graphqlBody;
        if (url.contains("/jobs")) return LSD_HTML;
        return ""; // cookie bootstrap
    }

    @Test
    void platformAndCompanies() {
        MetaScraper s = scraper(u -> routed(u, JOBS));
        assertThat(s.platform()).isEqualTo("meta");
        assertThat(s.companies()).containsExactly("meta");
    }

    @Test
    void scrapeParsesJobsAcrossQueriesDeduped() {
        MetaScraper s = scraper(u -> routed(u, JOBS));
        List<JobPosting> jobs = s.scrape("meta");
        assertThat(jobs).hasSize(1); // same id from every SCM query -> deduped
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("mj1");
        assertThat(j.getTitle()).isEqualTo("Supply Chain Analyst");
        assertThat(j.getLocation()).isEqualTo("Menlo Park, CA");
        assertThat(j.getUrl()).contains("mj1");
    }

    @Test
    void missingLsdTokenReturnsEmpty() {
        MetaScraper s = scraper(u -> u.contains("/jobs") ? "<html>no token</html>" : "");
        assertThat(s.scrape("meta")).isEmpty();
    }

    @Test
    void nonJsonGraphqlResponseIsSkipped() {
        MetaScraper s = scraper(u -> routed(u, "not-json-blocked"));
        assertThat(s.scrape("meta")).isEmpty();
    }

    @Test
    void graphqlErrorsResponseIsSkipped() {
        MetaScraper s = scraper(u -> routed(u, "{\"errors\":[{\"message\":\"blocked\"}]}"));
        assertThat(s.scrape("meta")).isEmpty();
    }

    @Test
    void missingDataShapesYieldNoJobs() {
        assertThat(scraper(u -> routed(u, "{\"data\":{}}")).scrape("meta")).isEmpty();
        assertThat(
                        scraper(u -> routed(u, "{\"data\":{\"job_search_with_featured_jobs\":{}}}"))
                                .scrape("meta"))
                .isEmpty();
        // data is not a Map -> ClassCastException -> caught -> empty
        assertThat(scraper(u -> routed(u, "{\"data\":\"nope\"}")).scrape("meta")).isEmpty();
    }

    @Test
    void malformedJsonInQueryIsCaughtPerQuery() {
        // starts with '{' so it passes the guard, but is invalid JSON -> readValue throws -> caught
        MetaScraper s = scraper(u -> routed(u, "{ this is not valid json"));
        assertThat(s.scrape("meta")).isEmpty();
    }

    @Test
    void responseWithoutDataKeyYieldsNoJobs() {
        MetaScraper s = scraper(u -> routed(u, "{\"extensions\":{}}"));
        assertThat(s.scrape("meta")).isEmpty(); // data == null branch
    }

    @Test
    void jobWithoutLocationsHasBlankLocation() {
        String body =
                "{\"data\":{\"job_search_with_featured_jobs\":{\"all_jobs\":[{\"id\":\"mj2\","
                        + "\"title\":\"Buyer\",\"description\":\"d\"}]}}}";
        JobPosting j = scraper(u -> routed(u, body)).scrape("meta").get(0);
        assertThat(j.getLocation()).isEmpty();
    }

    @Test
    void transportFailureReturnsEmpty() {
        MetaScraper s = new MetaScraper(WebClientStubs.erroring(), new ObjectMapper(), noProxy());
        assertThat(s.scrape("meta")).isEmpty(); // exchange throws -> "" body -> no LSD -> empty
    }

    @Test
    void mergesResponseCookiesAndSendsCookieHeader() {
        // bootstrap sets a cookie -> the jar is non-empty -> later requests send a Cookie header
        ExchangeFunction ex =
                req -> {
                    String u = req.url().toString();
                    ClientResponse.Builder b =
                            ClientResponse.create(HttpStatus.OK)
                                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                    if (u.contains("/graphql")) return Mono.just(b.body(JOBS).build());
                    if (u.contains("/jobs")) return Mono.just(b.body(LSD_HTML).build());
                    return Mono.just(b.cookie("datr", "cookieval").body("").build());
                };
        MetaScraper s =
                new MetaScraper(
                        WebClient.builder().exchangeFunction(ex), new ObjectMapper(), noProxy());
        assertThat(s.scrape("meta")).hasSize(1);
    }

    @Test
    void constructorRoutesThroughProxyWithAndWithoutAuth() {
        ProxyProperties withAuth = new ProxyProperties();
        withAuth.setEnabled(true);
        withAuth.setHost("proxy.example.com");
        withAuth.setPort(8080);
        withAuth.setUsername("u");
        withAuth.setPassword("p");
        assertThat(new MetaScraper(WebClient.builder(), new ObjectMapper(), withAuth).platform())
                .isEqualTo("meta");

        ProxyProperties noAuth = new ProxyProperties();
        noAuth.setEnabled(true);
        noAuth.setHost("proxy.example.com");
        noAuth.setPort(8080);
        assertThat(new MetaScraper(WebClient.builder(), new ObjectMapper(), noAuth).platform())
                .isEqualTo("meta");
    }

    @Test
    void extractLsdHandlesNull() throws Exception {
        Method m = MetaScraper.class.getDeclaredMethod("extractLsd", String.class);
        m.setAccessible(true);
        assertThat(m.invoke(null, (Object) null)).isNull();
    }
}
