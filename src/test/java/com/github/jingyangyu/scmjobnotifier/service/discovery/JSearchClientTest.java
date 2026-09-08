package com.github.jingyangyu.scmjobnotifier.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.service.discovery.JSearchClient.DiscoveredJob;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JSearchClientTest {

    private static final String OK =
            "{\"data\":{\"jobs\":[{\"employer_name\":\"Acme\",\"job_title\":\"Supply Chain Analyst\","
                    + "\"job_city\":\"San Jose\",\"job_state\":\"California\","
                    + "\"job_publisher\":\"LinkedIn\"}]}}";

    private JSearchClient client(String body, String key) {
        return new JSearchClient(WebClientStubs.json(u -> body), new ObjectMapper(), key);
    }

    @Test
    void configuredWhenKeyPresent() {
        assertThat(client(OK, "k").isConfigured()).isTrue();
        assertThat(client(OK, "").isConfigured()).isFalse();
        assertThat(client(OK, null).isConfigured()).isFalse();
    }

    @Test
    void searchParsesJobs() {
        List<DiscoveredJob> jobs =
                client(OK, "k").search("supply chain analyst in California, USA");
        assertThat(jobs).hasSize(1);
        DiscoveredJob j = jobs.get(0);
        assertThat(j.employer()).isEqualTo("Acme");
        assertThat(j.title()).isEqualTo("Supply Chain Analyst");
        assertThat(j.city()).isEqualTo("San Jose");
        assertThat(j.state()).isEqualTo("California");
        assertThat(j.publisher()).isEqualTo("LinkedIn");
    }

    @Test
    void nonArrayDataReturnsEmpty() {
        assertThat(client("{\"data\":{}}", "k").search("q")).isEmpty();
        assertThat(client("{}", "k").search("q")).isEmpty();
    }

    @Test
    void nullBodyReturnsEmpty() {
        JSearchClient c = new JSearchClient(WebClientStubs.noBody(), new ObjectMapper(), "k");
        assertThat(c.search("q")).isEmpty();
    }

    @Test
    void transportErrorReturnsEmpty() {
        JSearchClient c = new JSearchClient(WebClientStubs.erroring(), new ObjectMapper(), "k");
        assertThat(c.search("q")).isEmpty();
    }
}
