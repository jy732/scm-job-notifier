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
}
