package com.github.jingyangyu.scmjobnotifier.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JobPostingTest {

    @Test
    void builderAndGetters() {
        Instant now = Instant.now();
        JobPosting j =
                JobPosting.builder()
                        .company("acme")
                        .externalId("42")
                        .title("Buyer")
                        .url("https://x/42")
                        .location("San Jose, CA")
                        .description("desc")
                        .postedDate(now)
                        .detectedAt(now)
                        .notified(true)
                        .level("ENTRY_LEVEL")
                        .source("adzuna")
                        .build();
        assertThat(j.getCompany()).isEqualTo("acme");
        assertThat(j.getExternalId()).isEqualTo("42");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getUrl()).isEqualTo("https://x/42");
        assertThat(j.getLocation()).isEqualTo("San Jose, CA");
        assertThat(j.getDescription()).isEqualTo("desc");
        assertThat(j.getPostedDate()).isEqualTo(now);
        assertThat(j.getDetectedAt()).isEqualTo(now);
        assertThat(j.isNotified()).isTrue();
        assertThat(j.getLevel()).isEqualTo("ENTRY_LEVEL");
        assertThat(j.getSource()).isEqualTo("adzuna");
    }

    @Test
    void notifiedDefaultsFalse() {
        JobPosting j =
                JobPosting.builder()
                        .company("a")
                        .externalId("1")
                        .title("t")
                        .detectedAt(Instant.now())
                        .build();
        assertThat(j.isNotified()).isFalse();
    }

    @Test
    void settersAndNoArgsCtor() {
        JobPosting j = new JobPosting();
        j.setId(7L);
        j.setCompany("b");
        j.setTitle("Planner");
        j.setNotified(true);
        j.setClassificationFailures(2);
        assertThat(j.getId()).isEqualTo(7L);
        assertThat(j.getCompany()).isEqualTo("b");
        assertThat(j.getTitle()).isEqualTo("Planner");
        assertThat(j.isNotified()).isTrue();
        assertThat(j.getClassificationFailures()).isEqualTo(2);
    }
}
