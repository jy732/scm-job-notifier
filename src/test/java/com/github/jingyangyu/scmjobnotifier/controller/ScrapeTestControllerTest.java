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

    private static JobPosting jp(String title, String loc, java.time.Instant posted) {
        return JobPosting.builder()
                .company("acme")
                .externalId(title)
                .title(title)
                .location(loc)
                .postedDate(posted)
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void auditsCoverAllDispositionsAndCsvWrite() {
        JobScraper varied =
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
                                jp("Supply Chain Analyst", "San Jose, CA", null), // PASSED
                                jp("Buyer", "Austin, TX", null), // DROPPED_NON_CA
                                jp("Software Developer", "San Jose, CA", null), // NON_SCM/technical
                                jp("Senior Manager", "San Jose, CA", null), // seniority
                                jp(
                                        "Buyer",
                                        "San Jose, CA",
                                        Instant.now().minusSeconds(400L * 24 * 3600))); // stale
                    }
                };
        ScrapeTestController c =
                new ScrapeTestController(List.of(varied), poll, email, new JobTitleFilter(90));
        Map<String, Object> fa = c.filterAudit(null, null).block();
        assertThat(fa).containsKey("byDisposition");
        assertThat(c.locationAudit().block()).isNotNull();
        assertThat(c.scrapeAll().block()).isNotNull();
        assertThat(c.scrapeSingle("greenhouse", "acme").block()).containsKey("sample");
    }

    @Test
    void auditsSurviveScraperException() {
        ScrapeTestController c =
                new ScrapeTestController(List.of(throwing), poll, email, new JobTitleFilter(90));
        assertThat(c.scrapeAll().block()).isNotNull();
        assertThat(c.locationAudit().block()).isNotNull();
        assertThat(c.filterAudit(null, null).block()).isNotNull();
    }

    private static JobScraper scraperOf(String platform, String company, List<JobPosting> jobs) {
        return new JobScraper() {
            @Override
            public String platform() {
                return platform;
            }

            @Override
            public List<String> companies() {
                return List.of(company);
            }

            @Override
            public List<JobPosting> scrape(String c) {
                return jobs;
            }
        };
    }

    private static JobPosting bare(String title, String loc, String company) {
        return JobPosting.builder()
                .company(company)
                .externalId(title + loc)
                .title(title)
                .location(loc)
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void locationAuditFlagsBlankAndZeroCaSignatures() {
        List<JobPosting> mostlyBlank = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            mostlyBlank.add(bare("Buyer " + i, "", null)); // blank loc + null company
        }
        List<JobPosting> zeroCa = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            zeroCa.add(bare("Buyer " + i, "Austin, TX", "acme")); // non-blank, 0 CA
        }
        ScrapeTestController c =
                new ScrapeTestController(
                        List.of(
                                scraperOf("gha", "ca", mostlyBlank),
                                scraperOf("ghb", "cb", zeroCa)),
                        poll,
                        email,
                        new JobTitleFilter(90));
        Map<String, Object> r = c.locationAudit().block();
        assertThat(r).containsKey("parseLikelyBroken").containsKey("zeroCaWorthChecking");
        assertThat(r.get("parseLikelyBroken").toString()).contains("blank locations");
        assertThat(r.get("zeroCaWorthChecking").toString()).contains("0 CA");
    }

    @Test
    void filterAuditDropsNonScmAndPlatformFilterSkips() {
        JobScraper s =
                scraperOf(
                        "greenhouse",
                        "acme",
                        List.of(
                                bare("Registered Nurse", "San Jose, CA", null), // NON_SCM + null co
                                jp("Supply Chain Analyst", "San Jose, CA", null))); // PASSED
        ScrapeTestController c =
                new ScrapeTestController(List.of(s), poll, email, new JobTitleFilter(90));
        Map<String, Object> all = c.filterAudit(null, null).block();
        assertThat(all.get("byDisposition").toString()).contains("DROPPED_NON_SCM");
        // platform filter that matches nothing -> the scraper is skipped
        Map<String, Object> none = c.filterAudit("nomatch", null).block();
        assertThat(none).containsEntry("totalJobs", 0);
    }

    @Test
    void locationAuditReturnsErrorWhenCsvUnwritable() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("location-audit.csv");
        java.nio.file.Files.deleteIfExists(dir);
        java.nio.file.Files.createDirectory(dir); // a dir can't be opened as a file
        try {
            Map<String, Object> r = controller.locationAudit().block();
            assertThat(r).containsKey("error");
        } finally {
            java.nio.file.Files.delete(dir);
        }
    }

    @Test
    void filterAuditReturnsErrorWhenCsvUnwritable() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("filter-audit.csv");
        java.nio.file.Files.deleteIfExists(dir);
        java.nio.file.Files.createDirectory(dir);
        try {
            Map<String, Object> r = controller.filterAudit(null, null).block();
            assertThat(r).containsKey("error");
        } finally {
            java.nio.file.Files.delete(dir);
        }
    }
}
