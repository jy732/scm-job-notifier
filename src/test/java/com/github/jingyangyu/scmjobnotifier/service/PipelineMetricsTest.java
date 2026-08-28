package com.github.jingyangyu.scmjobnotifier.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PipelineMetricsTest {

    @Test
    void recordsAllCountersAndTimer() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        PipelineMetrics m = new PipelineMetrics(reg);

        m.recordGeminiSuccess();
        m.recordGeminiFail();
        m.recordGeminiRetry();
        m.recordScrapeSuccess();
        m.recordScrapeFail();
        m.recordEmailSuccess();
        m.recordEmailFail();
        m.recordJobsScraped(5);
        m.recordJobsClassified(3);
        m.recordJobsAutoApproved(2);
        m.recordAutoApprovedFallback();
        m.recordClassifyStage1(1);
        m.recordClassifyStage2(2);
        m.recordClassifyStage3(3);
        m.setUnnotifiedCount(7);
        Timer.Sample sample = m.startPollTimer();
        m.stopPollTimer(sample);

        assertThat(reg.get("job.gemini.calls").tag("result", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(reg.get("job.gemini.calls").tag("result", "failure").counter().count())
                .isEqualTo(1.0);
        assertThat(reg.get("job.pipeline.scraped").counter().count()).isEqualTo(5.0);
        assertThat(reg.get("job.pipeline.classified").counter().count()).isEqualTo(3.0);
    }
}
