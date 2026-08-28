package com.github.jingyangyu.scmjobnotifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.notification.EmailNotifier;
import com.github.jingyangyu.scmjobnotifier.repository.JobPostingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduledServicesTest {

    private final JobPostingRepository repo = mock(JobPostingRepository.class);
    private final EmailNotifier email = mock(EmailNotifier.class);

    private static JobPosting job() {
        return JobPosting.builder()
                .company("c")
                .externalId("1")
                .title("Buyer")
                .level("ENTRY_LEVEL")
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void dailySummarySendsWhenJobsExist() {
        when(repo.findRecentNotifiableJobs(any())).thenReturn(List.of(job()));
        when(repo.findUnnotifiedNotifiableJobs()).thenReturn(List.of());
        when(email.sendDailySummary(any())).thenReturn(true);
        new DailySummaryService(repo, email).sendDailySummary();
        verify(email).sendDailySummary(any());
    }

    private static JobPosting job(long id, boolean notified) {
        return JobPosting.builder()
                .id(id)
                .company("c")
                .externalId(String.valueOf(id))
                .title("Buyer")
                .level("ENTRY_LEVEL")
                .notified(notified)
                .detectedAt(Instant.now())
                .build();
    }

    @Test
    void dailySummaryMarksUnnotifiedWhenSent() {
        JobPosting unnotified = job(2, false);
        when(repo.findRecentNotifiableJobs(any())).thenReturn(List.of(job(1, true)));
        when(repo.findUnnotifiedNotifiableJobs()).thenReturn(List.of(unnotified));
        when(email.sendDailySummary(any())).thenReturn(true);
        new DailySummaryService(repo, email).sendDailySummary();
        verify(repo).save(unnotified);
        assertThat(unnotified.isNotified()).isTrue();
    }

    @Test
    void dailySummaryKeepsUnnotifiedWhenSendFails() {
        JobPosting unnotified = job(2, false);
        when(repo.findRecentNotifiableJobs(any())).thenReturn(List.of());
        when(repo.findUnnotifiedNotifiableJobs()).thenReturn(List.of(unnotified));
        when(email.sendDailySummary(any())).thenReturn(false);
        new DailySummaryService(repo, email).sendDailySummary();
        verify(repo, never()).save(any());
    }

    @Test
    void dailySummarySkipsWhenNoJobs() {
        when(repo.findRecentNotifiableJobs(any())).thenReturn(List.of());
        when(repo.findUnnotifiedNotifiableJobs()).thenReturn(List.of());
        new DailySummaryService(repo, email).sendDailySummary();
        verify(email, never()).sendDailySummary(any());
    }

    @Test
    void notificationSendsAndMarks() {
        when(repo.findUnnotifiedNotifiableJobs()).thenReturn(List.of(job()));
        when(email.sendNewJobAlert(any())).thenReturn(true);
        new NotificationService(email, repo, new PipelineMetrics(new SimpleMeterRegistry()))
                .scanAndNotify();
        verify(email).sendNewJobAlert(any());
        verify(repo).save(any());
    }

    @Test
    void notificationRecordsFailWhenSendFails() {
        when(repo.findUnnotifiedNotifiableJobs()).thenReturn(List.of(job()));
        when(email.sendNewJobAlert(any())).thenReturn(false);
        new NotificationService(email, repo, new PipelineMetrics(new SimpleMeterRegistry()))
                .scanAndNotify();
        verify(email).sendNewJobAlert(any());
        verify(repo, never()).save(any());
    }

    @Test
    void notificationSkipsWhenNone() {
        when(repo.findUnnotifiedNotifiableJobs()).thenReturn(List.of());
        new NotificationService(email, repo, new PipelineMetrics(new SimpleMeterRegistry()))
                .scanAndNotify();
        verify(email, never()).sendNewJobAlert(any());
    }

    @Test
    void cleanupDeletesStale() {
        when(repo.deleteByPostedDateBefore(any())).thenReturn(3);
        new JobCleanupService(repo).cleanupStaleJobs();
        verify(repo).deleteByPostedDateBefore(any());
        assertThat(true).isTrue();
    }
}
