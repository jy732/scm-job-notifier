package com.github.jingyangyu.scmjobnotifier.repository;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data JPA repository for {@link JobPosting} entities. */
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    /** Checks if a job with the given natural key already exists. */
    boolean existsByCompanyAndExternalId(String company, String externalId);

    /** Returns all compound keys (company + ':' + externalId) for bulk in-memory dedup. */
    @Query("SELECT jp.company || ':' || jp.externalId FROM JobPosting jp")
    Set<String> findAllCompanyExternalIdKeys();

    /** Batch lookup of existing jobs by their natural keys (company + externalId). */
    @Query("SELECT jp FROM JobPosting jp WHERE jp.company || ':' || jp.externalId IN ?1")
    List<JobPosting> findByCompanyExternalIdKeys(Set<String> keys);

    /**
     * Unnotified notifiable jobs (ENTRY_LEVEL / INTERNSHIP / UNSURE) for the 5-minute alert scan.
     * OTHER is excluded. Ordered so the single alert email lists newest first.
     */
    @Query(
            "SELECT jp FROM JobPosting jp WHERE jp.notified = false"
                    + " AND jp.level IN ('ENTRY_LEVEL', 'INTERNSHIP', 'UNSURE')"
                    + " ORDER BY jp.detectedAt DESC")
    List<JobPosting> findUnnotifiedNotifiableJobs();

    /** Recent notifiable jobs (last 24h) for the daily summary. */
    @Query(
            "SELECT jp FROM JobPosting jp WHERE jp.detectedAt > ?1"
                    + " AND jp.level IN ('ENTRY_LEVEL', 'INTERNSHIP', 'UNSURE')"
                    + " ORDER BY jp.detectedAt DESC")
    List<JobPosting> findRecentNotifiableJobs(Instant since);

    /** Finds jobs that failed Gemini classification but haven't exhausted retries yet. */
    List<JobPosting> findByClassificationFailuresGreaterThanAndClassificationFailuresLessThan(
            int min, int max);

    /** Deletes jobs posted before the cutoff for data retention cleanup. */
    @Modifying
    @Query("DELETE FROM JobPosting jp WHERE jp.postedDate < ?1")
    int deleteByPostedDateBefore(Instant cutoff);
}
