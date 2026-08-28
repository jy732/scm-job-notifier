package com.github.jingyangyu.scmjobnotifier.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.notification.EmailNotifier;
import com.github.jingyangyu.scmjobnotifier.scraper.JobScraper;
import com.github.jingyangyu.scmjobnotifier.service.JobPollingService;
import com.github.jingyangyu.scmjobnotifier.service.classification.JobTitleFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScrapeTestControllerTest {

    private final JobScraper fake =
            new JobScraper() {
                @Override
                public String platform() {
                    return "greenhouse";
                }

                @Override
                public List<String> companies() {
                    return List.of("acme");
                }

                @Override
                public List<JobPosting> scrape(String company) {
                    return List.of(
                            JobPosting.builder()
                                    .company(company)
                                    .externalId("1")
                                    .title("Supply Chain Analyst")
                                    .location("San Jose, CA")
                                    .detectedAt(Instant.now())
                                    .build());
                }
            };

    private final EmailNotifier email = mock(EmailNotifier.class);
    private final JobPollingService poll = mock(JobPollingService.class);
    private final ScrapeTestController controller =
            new ScrapeTestController(List.of(fake), poll, email, new JobTitleFilter(90));

    @Test
    void testEmailSendsSample() {
        when(email.sendTestAlert(any(), anyString())).thenReturn(true);
        Map<String, Object> r = controller.testEmail().block();
        assertThat(r).containsEntry("sent", true).containsKey("to");
        verify(email).sendTestAlert(any(), anyString());
    }

    @Test
    void announcementUsesBodyRecipient() {
        when(email.sendAnnouncement(anyString(), anyString(), anyString())).thenReturn(true);
        Map<String, Object> r =
                controller
                        .sendAnnouncement(Map.of("subject", "s", "html", "<p>h</p>", "to", "x@y.z"))
                        .block();
        assertThat(r).containsEntry("sent", true).containsEntry("to", "x@y.z");
    }

    @Test
    void announcementDefaultsToTestAddressWhenNoRecipient() {
        when(email.sendAnnouncement(anyString(), anyString(), anyString())).thenReturn(true);
        Map<String, Object> r = controller.sendAnnouncement(Map.of("subject", "s")).block();
        assertThat(r).containsEntry("to", "jy63@illinois.edu");
    }

    @Test
    void scrapeSingleReturnsCount() {
        Map<String, Object> r = controller.scrapeSingle("greenhouse", "acme").block();
        assertThat(r).containsEntry("platform", "greenhouse").containsEntry("count", 1);
    }

    @Test
    void scrapeSingleUnknownPlatform() {
        Map<String, Object> r = controller.scrapeSingle("nope", "x").block();
        assertThat(r).containsKey("error").containsKey("available");
    }

    @Test
    void scrapeAllSummarizes() {
        Map<String, Object> r = controller.scrapeAll().block();
        assertThat(r).isNotNull();
    }

    @Test
    void locationAuditRuns() {
        Map<String, Object> r = controller.locationAudit().block();
        assertThat(r).isNotNull();
    }

    @Test
    void filterAuditScopedReportsDisposition() {
        Map<String, Object> r = controller.filterAudit("greenhouse", "acme").block();
        assertThat(r).containsEntry("companiesAudited", 1).containsEntry("totalJobs", 1);
        assertThat(r.get("byDisposition").toString()).contains("PASSED");
    }

    @Test
    void filterAuditUnmatchedCompany() {
        Map<String, Object> r = controller.filterAudit(null, "ghost").block();
        assertThat(r).containsKey("unmatchedCompanies").containsKey("warning");
    }

    @Test
    void triggerPollInvokesPollingService() {
        Map<String, Object> r = controller.triggerPoll().block();
        assertThat(r).containsKey("status");
        verify(poll).poll();
    }

    private final JobScraper throwing =
            new JobScraper() {
                @Override
                public String platform() {
                    return "boom";
                }

                @Override
                public List<String> companies() {
                    return List.of("c");
                }

                @Override
                public List<JobPosting> scrape(String company) {
                    throw new RuntimeException("scrape failed");
                }
            };

    @Test
    void auditsSurviveScraperException() {
        ScrapeTestController c =
                new ScrapeTestController(List.of(throwing), poll, email, new JobTitleFilter(90));
        assertThat(c.scrapeAll().block()).isNotNull();
        assertThat(c.locationAudit().block()).isNotNull();
        assertThat(c.filterAudit(null, null).block()).isNotNull();
    }
}
