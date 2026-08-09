package com.github.jingyangyu.scmjobnotifier.service;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.notification.EmailNotifier;
import com.github.jingyangyu.scmjobnotifier.repository.JobPostingRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends a daily summary email at 8 AM ({@code job.summary.cron}).
 *
 * <p>Includes two sources of jobs:
 *
 * <ol>
 *   <li>Notifiable jobs detected in the last 24 hours (the normal daily digest).
 *   <li>Any older notifiable jobs where {@code notified=false} — jobs whose instant alert failed
 *       (e.g. SMTP error) and haven't been included in a successful summary yet. Safety net so no
 *       approved job is ever silently lost.
 * </ol>
 *
 * <p>Jobs are only marked {@code notified=true} after the summary email is successfully sent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySummaryService {

    private final JobPostingRepository repository;
    private final EmailNotifier emailNotifier;

    /** Sends the daily summary email on the configured cron schedule. */
    @Scheduled(cron = "${job.summary.cron}")
    public void sendDailySummary() {
        log.info("=== DAILY SUMMARY START ===");
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        List<JobPosting> recentJobs = repository.findRecentNotifiableJobs(since);
        List<JobPosting> unnotifiedJobs = repository.findUnnotifiedNotifiableJobs();

        // Merge both queries and dedup by id — a job may appear in both lists.
        Set<Long> seen = new LinkedHashSet<>();
        List<JobPosting> allJobs = new ArrayList<>();
        for (JobPosting job : recentJobs) {
            if (seen.add(job.getId())) {
                allJobs.add(job);
            }
        }
        for (JobPosting job : unnotifiedJobs) {
            if (seen.add(job.getId())) {
                allJobs.add(job);
            }
        }

        log.info(
                "Daily summary: {} recent job(s), {} unnotified job(s), {} total after dedup",
                recentJobs.size(),
                unnotifiedJobs.size(),
                allJobs.size());

        if (allJobs.isEmpty()) {
            log.info("No jobs to include in daily summary — skipping email");
            return;
        }

        boolean sent = emailNotifier.sendDailySummary(allJobs);

        if (sent) {
            for (JobPosting job : unnotifiedJobs) {
                if (!job.isNotified()) {
                    job.setNotified(true);
                    repository.save(job);
                }
            }
            log.info(
                    "Daily summary sent — {} unnotified job(s) marked as notified",
                    unnotifiedJobs.size());
        } else {
            log.warn(
                    "Daily summary email failed — {} job(s) remain unnotified for next attempt",
                    unnotifiedJobs.size());
        }
    }
}
