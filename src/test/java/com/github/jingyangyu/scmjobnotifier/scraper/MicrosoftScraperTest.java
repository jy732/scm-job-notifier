package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class MicrosoftScraperTest {

    private static final String POS =
            "{\"data\":{\"positions\":[{\"displayJobId\":\"m1\",\"name\":\"Buyer\","
                    + "\"positionUrl\":\"https://x/m1\","
                    + "\"standardizedLocations\":[\"San Jose, CA\"]}]}}";

    // positions on first page (start=0) only, so pagination terminates
    private static String body(String url) {
        return url.contains("start=0") ? POS : "{\"data\":{\"positions\":[]}}";
    }

    @Test
    void platform() {
        assertThat(new MicrosoftScraper(WebClientStubs.json(u -> POS)).platform())
                .isEqualTo("microsoft");
    }

    @Test
    void scrapeParsesPositionsDeduped() {
        MicrosoftScraper s = new MicrosoftScraper(WebClientStubs.json(MicrosoftScraperTest::body));
        List<JobPosting> jobs = s.scrape("microsoft");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("m1");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).isEqualTo("San Jose, CA");
        assertThat(j.getUrl()).isEqualTo("https://x/m1");
    }

    @Test
    void emptyWhenNoPositions() {
        MicrosoftScraper s =
                new MicrosoftScraper(WebClientStubs.json(u -> "{\"data\":{\"positions\":[]}}"));
        assertThat(s.scrape("microsoft")).isEmpty();
    }
}
