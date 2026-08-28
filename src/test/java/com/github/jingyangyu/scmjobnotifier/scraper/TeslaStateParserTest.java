package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeslaStateParserTest {

    @Test
    void parsesListingsWithLocationLookup() {
        String json =
                "{\"lookup\":{\"locations\":{\"L1\":\"Palo Alto, CA\"}},"
                        + "\"listings\":[{\"id\":\"100\",\"t\":\"Supply Chain Analyst\",\"l\":\"L1\"}]}";
        List<JobPosting> jobs = TeslaStateParser.parse(json);
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getCompany()).isEqualTo("tesla");
        assertThat(j.getExternalId()).isEqualTo("100");
        assertThat(j.getTitle()).isEqualTo("Supply Chain Analyst");
        assertThat(j.getLocation()).isEqualTo("Palo Alto, CA");
        assertThat(j.getUrl()).endsWith("/job/100");
    }

    @Test
    void skipsListingsMissingIdOrTitle() {
        String json =
                "{\"lookup\":{\"locations\":{}},"
                        + "\"listings\":[{\"id\":\"\",\"t\":\"X\"},{\"id\":\"5\",\"t\":\"\"},"
                        + "{\"id\":\"7\",\"t\":\"Buyer\",\"l\":\"nope\"}]}";
        List<JobPosting> jobs = TeslaStateParser.parse(json);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getExternalId()).isEqualTo("7");
        assertThat(jobs.get(0).getLocation()).isEmpty(); // unknown lookup key -> ""
    }

    @Test
    void emptyForNullBlankOrNoListings() {
        assertThat(TeslaStateParser.parse(null)).isEmpty();
        assertThat(TeslaStateParser.parse("")).isEmpty();
        assertThat(TeslaStateParser.parse("   ")).isEmpty();
        assertThat(TeslaStateParser.parse("{\"lookup\":{}}")).isEmpty();
    }
}
