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
}
