package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaloAltoNetworksScraperTest {

    private static final String CARD =
            "<a class=\"section29__search-results-link\" href=\"/en/job/x123\""
                    + " data-job-id=\"p1\"><h2 class=\"section29__search-results-job-title\">"
                    + "Buyer</h2><span class=\"section29__result-location\">San Jose, CA</span></a>";

    // return the card only on page 1 (/47263/1); empty on later pages so pagination stops
    private static String body(String url) {
        return url.contains("/47263/1") ? CARD : "<html></html>";
    }

    @Test
    void platform() {
        assertThat(
                        new PaloAltoNetworksScraper(WebClientStubs.text(u -> CARD, "text/html"))
                                .platform())
                .isEqualTo("paloaltonetworks");
    }

    @Test
    void scrapeParsesCardsDeduped() {
        PaloAltoNetworksScraper s =
                new PaloAltoNetworksScraper(
                        WebClientStubs.text(PaloAltoNetworksScraperTest::body, "text/html"));
        List<JobPosting> jobs = s.scrape("paloaltonetworks");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("p1");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).isEqualTo("San Jose, CA");
        assertThat(j.getUrl()).isEqualTo("https://jobs.paloaltonetworks.com/en/job/x123");
    }

    @Test
    void emptyWhenNoCards() {
        PaloAltoNetworksScraper s =
                new PaloAltoNetworksScraper(WebClientStubs.text(u -> "<html></html>", "text/html"));
        assertThat(s.scrape("paloaltonetworks")).isEmpty();
    }
}
