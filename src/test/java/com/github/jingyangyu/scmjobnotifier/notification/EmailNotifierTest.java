package com.github.jingyangyu.scmjobnotifier.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

class EmailNotifierTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final EmailNotifier notifier = notifier();

    private EmailNotifier notifier() {
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        return new EmailNotifier(sender, "to@x.com", "from@x.com");
    }

    private static JobPosting job() {
        return JobPosting.builder()
                .company("acme")
                .externalId("1")
                .title("Supply Chain Analyst")
                .url("https://x/1")
                .location("San Jose, CA")
                .level("ENTRY_LEVEL")
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void sendTestAlert() {
        assertThat(notifier.sendTestAlert(List.of(job()), "t@x.com")).isTrue();
        verify(sender, atLeastOnce()).send(any(MimeMessage.class));
    }

    @Test
    void sendAnnouncement() {
        assertThat(notifier.sendAnnouncement("Subject", "<p>hi</p>", "t@x.com")).isTrue();
    }

    @Test
    void sendNewJobAlert() {
        assertThat(notifier.sendNewJobAlert(List.of(job()))).isTrue();
    }

    @Test
    void sendDailySummary() {
        assertThat(notifier.sendDailySummary(List.of(job()))).isTrue();
    }

    @Test
    void notConfiguredReturnsFalse() {
        EmailNotifier n = new EmailNotifier(sender, "", "from@x.com");
        assertThat(n.sendNewJobAlert(List.of(job()))).isFalse();
    }

    @Test
    void emptyNewJobsIsNoOpSuccess() {
        // nothing to send is treated as success (no email dispatched)
        assertThat(notifier.sendNewJobAlert(List.of())).isTrue();
    }

    private static JobPosting varied(String level, String source, String loc) {
        return JobPosting.builder()
                .company("acme")
                .externalId(level + source)
                .title("Supply Chain " + level)
                .url("https://x/1")
                .location(loc)
                .level(level)
                .source(source)
                .postedDate(Instant.now())
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void sendFailureAfterRetriesReturnsFalse() {
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(sender)
                .send(any(MimeMessage.class));
        // retries (2s,4s backoff) then gives up -> false
        assertThat(notifier.sendNewJobAlert(List.of(job()))).isFalse();
    }

    @Test
    void richAlertExercisesDirectAndAdzunaSections() {
        // direct (source null) + Adzuna-sourced + internship — hits the grouped-section branches
        List<JobPosting> jobs =
                List.of(
                        varied("ENTRY_LEVEL", null, "San Jose, CA"),
                        varied("INTERNSHIP", null, "Irvine, CA"),
                        varied("UNSURE", "adzuna", "Los Angeles, CA"));
        assertThat(notifier.sendNewJobAlert(jobs)).isTrue();
        assertThat(notifier.sendDailySummary(jobs)).isTrue();
    }

    @Test
    void dailySummaryNotConfiguredReturnsFalse() {
        EmailNotifier n = new EmailNotifier(sender, "", "from@x.com");
        assertThat(n.sendDailySummary(List.of(job()))).isFalse();
    }

    @Test
    void dailySummaryEmptyJobsIsSuccess() {
        assertThat(notifier.sendDailySummary(List.of())).isTrue();
    }

    @Test
    void testAlertBlankAddressReturnsFalse() {
        assertThat(notifier.sendTestAlert(List.of(job()), "")).isFalse();
        assertThat(notifier.sendTestAlert(List.of(job()), null)).isFalse();
    }

    @Test
    void announcementBlankAddressReturnsFalse() {
        assertThat(notifier.sendAnnouncement("s", "<p>h</p>", "")).isFalse();
        assertThat(notifier.sendAnnouncement("s", "<p>h</p>", null)).isFalse();
    }

    @Test
    void sendFailuresAcrossAllEntryPointsReturnFalse() {
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(sender)
                .send(any(MimeMessage.class));
        assertThat(notifier.sendDailySummary(List.of(job()))).isFalse();
        assertThat(notifier.sendTestAlert(List.of(job()), "t@x.com")).isFalse();
        assertThat(notifier.sendAnnouncement("s", "<p>h</p>", "t@x.com")).isFalse();
    }

    @Test
    void labelAndLocationHelpersCoverAllBranches() {
        JobPosting nullLevel =
                JobPosting.builder()
                        .company("c")
                        .externalId("nl")
                        .title("T")
                        .url("https://x")
                        .location("San Jose, CA")
                        .level(null)
                        .detectedAt(Instant.now())
                        .build();
        JobPosting nullLoc =
                JobPosting.builder()
                        .company("c")
                        .externalId("nloc")
                        .title("T")
                        .url("https://x")
                        .location(null)
                        .level("ENTRY_LEVEL")
                        .detectedAt(Instant.now())
                        .build();
        JobPosting remote =
                JobPosting.builder()
                        .company("c")
                        .externalId("rem")
                        .title("T")
                        .url("https://x")
                        .location("Remote, CA")
                        .level("ENTRY_LEVEL")
                        .detectedAt(Instant.now())
                        .build();
        JobPosting otherMetro =
                JobPosting.builder()
                        .company("c")
                        .externalId("sd")
                        .title("T")
                        .url("https://x")
                        .location("San Diego, CA")
                        .level("ENTRY_LEVEL")
                        .detectedAt(Instant.now())
                        .build();
        JobPosting nullUrl =
                JobPosting.builder()
                        .company("c")
                        .externalId("nu")
                        .title("T")
                        .url(null)
                        .location("San Jose, CA")
                        .level("ENTRY_LEVEL")
                        .detectedAt(Instant.now())
                        .build();
        assertThat(
                        notifier.sendNewJobAlert(
                                List.of(nullLevel, nullLoc, remote, otherMetro, nullUrl)))
                .isTrue();
    }

    @Test
    void buildBodyRendersIntroWhenProvided() throws Exception {
        java.lang.reflect.Method m =
                EmailNotifier.class.getDeclaredMethod(
                        "buildBody", List.class, String.class, String.class);
        m.setAccessible(true);
        String body = (String) m.invoke(notifier, List.of(job()), "<h1>H</h1>", "<p>intro</p>");
        assertThat(body).contains("<p>intro</p>");
    }
}
