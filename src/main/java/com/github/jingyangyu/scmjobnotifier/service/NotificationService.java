package com.github.jingyangyu.scmjobnotifier.service;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.notification.EmailNotifier;
import com.github.jingyangyu.scmjobnotifier.repository.JobPostingRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scans for unnotified notifiable jobs every 5 minutes and sends a single alert email.
 *
 * <p>Decoupled from the poll cycle: {@code JobPollingService} scrapes, filters, classifies, and
 * persists jobs with a {@code level} (ENTRY_LEVEL/INTERNSHIP/UNSURE/OTHER). This service picks up
 * the unnotified ENTRY_LEVEL/INTERNSHIP/UNSURE jobs and emails them in one message with a Type
 * column (Decision D1). Benefits:
 *
 * <ul>
 *   <li>No inline email sending during the poll — poll failures don't affect notifications.
 *   <li>No separate retry logic needed — the same scan naturally retries on the next run.
 *   <li>Worst-case alert latency is ~5 minutes after a job is persisted.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final EmailNotifier emailNotifier;
    private final JobPostingRepository repository;
    private final PipelineMetrics metrics;

    /**
     * Scans for unnotified notifiable jobs and sends one alert email if any are found. Runs every 5
     * minutes. No-ops silently when nothing to send.
     */
    @Scheduled(cron = "${job.notification.scan.cron:0 */5 * * * *}")
    public void scanAndNotify() {
        List<JobPosting> unnotified = repository.findUnnotifiedNotifiableJobs();
        if (unnotified.isEmpty()) {
            return;
        }

        log.info(
                "=== ALERT SCAN === {} unnotified notifiable job(s) found, sending one email...",
                unnotified.size());
        boolean sent = emailNotifier.sendNewJobAlert(unnotified);
        if (sent) {
            metrics.recordEmailSuccess();
            for (JobPosting job : unnotified) {
                job.setNotified(true);
                repository.save(job);
            }
            log.info("Alert SENT — {} job(s) marked as notified", unnotified.size());
        } else {
            metrics.recordEmailFail();
            log.warn(
                    "Alert FAILED — {} job(s) remain unnotified, will retry in 5 min",
                    unnotified.size());
        }

        metrics.setUnnotifiedCount(repository.findUnnotifiedNotifiableJobs().size());
    }
}
