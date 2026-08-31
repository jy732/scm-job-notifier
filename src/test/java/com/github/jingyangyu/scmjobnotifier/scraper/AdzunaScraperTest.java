package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.AdzunaProperties;
import com.github.jingyangyu.scmjobnotifier.config.BrassRingProperties;
import com.github.jingyangyu.scmjobnotifier.config.IcimsProperties;
import com.github.jingyangyu.scmjobnotifier.config.OracleCloudProperties;
import com.github.jingyangyu.scmjobnotifier.config.PaylocityProperties;
import com.github.jingyangyu.scmjobnotifier.config.SuccessFactorsProperties;
import com.github.jingyangyu.scmjobnotifier.config.WorkdayProperties;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AdzunaScraperTest {

    private static final String RESULTS =
            "{\"results\":[{\"id\":123,\"title\":\"Supply Chain Analyst\","
                    + "\"company\":{\"display_name\":\"Acme\"},"
                    + "\"location\":{\"display_name\":\"San Jose, CA\"},"
                    + "\"redirect_url\":\"https://x/123\",\"created\":\"2026-08-01T00:00:00Z\"}]}";

    private AdzunaScraper scraper(AdzunaProperties props, Function<String, String> stub) {
        WebClient.Builder b = WebClientStubs.json(stub);
        return new AdzunaScraper(
                b,
                props,
                new WorkdayProperties(),
                new OracleCloudProperties(),
                new IcimsProperties(),
                new SuccessFactorsProperties(),
                new PaylocityProperties(),
                new BrassRingProperties(),
                "",
                "",
                "",
                "",
                "");
    }

    private static AdzunaProperties configured() {
        AdzunaProperties p = new AdzunaProperties();
        p.setEnabled(true);
        p.setAppId("id");
        p.setAppKey("key");
        p.setThrottleMinutes(0);
        p.setPages(1);
        return p;
    }

    @Test
    void platformAndCompanies() {
        AdzunaScraper s = scraper(configured(), u -> RESULTS);
        assertThat(s.platform()).isEqualTo("adzuna");
        assertThat(s.companies()).isNotEmpty();
    }

    @Test
    void scrapeParsesResults() {
        AdzunaScraper s = scraper(configured(), u -> u.contains("/search/1") ? RESULTS : "{}");
        List<JobPosting> jobs = s.scrape("adzuna");
        assertThat(jobs).isNotEmpty();
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("adz-123");
        assertThat(j.getTitle()).isEqualTo("Supply Chain Analyst");
        assertThat(j.getLocation()).isEqualTo("San Jose, CA");
        assertThat(j.getSource()).isEqualTo("adzuna");
    }

    @Test
    void notConfiguredReturnsEmpty() {
        AdzunaScraper s = scraper(new AdzunaProperties(), u -> RESULTS);
        assertThat(s.scrape("adzuna")).isEmpty();
    }

    @Test
    void secondCallIsThrottled() {
        AdzunaProperties p = configured();
        p.setThrottleMinutes(60);
        AdzunaScraper s = scraper(p, u -> u.contains("/search/1") ? RESULTS : "{}");
        s.scrape("adzuna"); // first call fetches, sets lastFetch=now
        assertThat(s.scrape("adzuna")).isEmpty(); // within throttle window
    }

    private AdzunaScraper scraperWithGreenhouse(String gh, Function<String, String> stub) {
        return new AdzunaScraper(
                WebClientStubs.json(stub),
                configured(),
                new WorkdayProperties(),
                new OracleCloudProperties(),
                new IcimsProperties(),
                new SuccessFactorsProperties(),
                new PaylocityProperties(),
                new BrassRingProperties(),
                gh,
                "",
                "",
                "",
                "");
    }

    @Test
    void nullBodyBreaksPagination() {
        AdzunaScraper s = scraper(configured(), u -> "null");
        assertThat(s.scrape("adzuna")).isEmpty();
    }

    @Test
    void resultsNotAListReturnsEmpty() {
        AdzunaScraper s =
                scraper(configured(), u -> u.contains("/search/1") ? "{\"results\":5}" : "{}");
        assertThat(s.scrape("adzuna")).isEmpty();
    }

    @Test
    void jobWithoutIdIsSkipped() {
        String body =
                "{\"results\":[{\"title\":\"Buyer\","
                        + "\"company\":{\"display_name\":\"Acme\"},"
                        + "\"location\":{\"display_name\":\"San Jose, CA\"}}]}";
        AdzunaScraper s = scraper(configured(), u -> u.contains("/search/1") ? body : "{}");
        assertThat(s.scrape("adzuna")).isEmpty(); // no id -> toJobPosting null -> skipped
    }

    @Test
    void shortCompanyNameIsNotExcluded() {
        String body =
                "{\"results\":[{\"id\":789,\"title\":\"Supply Chain Analyst\","
                        + "\"company\":{\"display_name\":\"AB\"},"
                        + "\"location\":{\"display_name\":\"San Jose, CA\"},"
                        + "\"redirect_url\":\"https://x/789\"}]}";
        AdzunaScraper s = scraper(configured(), u -> u.contains("/search/1") ? body : "{}");
        assertThat(s.scrape("adzuna")).extracting(JobPosting::getExternalId).contains("adz-789");
    }

    @Test
    void companyMatchingExcludeTokenIsDropped() {
        String body =
                "{\"results\":[{\"id\":321,\"title\":\"Supply Chain Analyst\","
                        + "\"company\":{\"display_name\":\"Acme Corporation\"},"
                        + "\"location\":{\"display_name\":\"San Jose, CA\"},"
                        + "\"redirect_url\":\"https://x/321\"}]}";
        // greenhouse company "acme" becomes an exclude token; the Adzuna hit for Acme is dropped
        AdzunaScraper s = scraperWithGreenhouse("acme", u -> u.contains("/search/1") ? body : "{}");
        assertThat(s.scrape("adzuna")).isEmpty();
    }

    @Test
    void badCreatedDateYieldsNullPostedDate() {
        String body =
                "{\"results\":[{\"id\":654,\"title\":\"Supply Chain Analyst\","
                        + "\"company\":{\"display_name\":\"Acme\"},"
                        + "\"location\":{\"display_name\":\"San Jose, CA\"},"
                        + "\"redirect_url\":\"https://x/654\",\"created\":\"garbage\"}]}";
        AdzunaScraper s = scraper(configured(), u -> u.contains("/search/1") ? body : "{}");
        assertThat(s.scrape("adzuna").get(0).getPostedDate()).isNull();
    }

    @Test
    void excludesNoiseCompanies() {
        String twoResults =
                "{\"results\":[{\"id\":123,\"title\":\"Supply Chain Analyst\","
                        + "\"company\":{\"display_name\":\"Acme\"},"
                        + "\"location\":{\"display_name\":\"San Jose, CA\"},"
                        + "\"redirect_url\":\"https://x/123\"},"
                        + "{\"id\":456,\"title\":\"Buyer\","
                        + "\"company\":{\"display_name\":\"Aerotek Staffing\"},"
                        + "\"location\":{\"display_name\":\"San Jose, CA\"},"
                        + "\"redirect_url\":\"https://x/456\"}]}";
        AdzunaScraper s = scraper(configured(), u -> u.contains("/search/1") ? twoResults : "{}");
        List<JobPosting> jobs = s.scrape("adzuna");
        assertThat(jobs)
                .extracting(JobPosting::getExternalId)
                .contains("adz-123")
                .doesNotContain("adz-456");
    }
}
