package com.github.jingyangyu.scmjobnotifier.notification;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends HTML email notifications via Spring Mail (Gmail SMTP).
 *
 * <p>Per Decision D1 there is a <b>single recipient list</b> and a <b>single email</b>:
 * entry-level, internship, and unsure postings are rendered as rows in one table with a Type
 * column. Callers use the returned boolean to decide whether to mark jobs notified — a failed send
 * leaves them {@code notified=false} so the daily-summary safety net retries.
 *
 * <p>Retries up to 3 times with exponential backoff (2s → 4s → 8s) on SMTP failures.
 */
@Slf4j
@Component
public class EmailNotifier {

    private final JavaMailSender mailSender;
    private final String[] toAddresses;
    private final String fromAddress;
    private final RetryTemplate retryTemplate;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public EmailNotifier(
            JavaMailSender mailSender,
            @Value("${job.notification.to:}") String toAddress,
            @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.toAddresses = parseAddresses(toAddress);
        this.fromAddress = fromAddress;
        this.retryTemplate =
                RetryTemplate.builder()
                        .maxAttempts(3)
                        .exponentialBackoff(2000, 2, 8000)
                        .retryOn(MessagingException.class)
                        .retryOn(MailException.class)
                        .build();

        if (toAddresses.length == 0 || fromAddress.isBlank()) {
            log.error(
                    "██ EMAIL NOT CONFIGURED ██ "
                            + "toAddress={}, fromAddress={} — alerts will NOT be sent until fixed",
                    toAddresses.length == 0 ? "<MISSING>" : toAddress,
                    fromAddress.isBlank() ? "<MISSING>" : fromAddress);
        } else {
            log.info("Email configured: from={}, to={}", fromAddress, Arrays.toString(toAddresses));
        }
    }

    private static String[] parseAddresses(String addresses) {
        if (addresses == null || addresses.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(addresses.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);
    }

    /**
     * Sends a single alert email for newly detected notifiable jobs (entry-level, internship, and
     * unsure), with a Type column marking each row.
     *
     * @return true if the email was sent successfully, false otherwise
     */
    public boolean sendNewJobAlert(List<JobPosting> newJobs) {
        if (toAddresses.length == 0) {
            log.error("EMAIL NOT CONFIGURED — {} job(s) will NOT be sent", newJobs.size());
            return false;
        }
        if (newJobs.isEmpty()) {
            return true;
        }

        String subject =
                String.format("[SCM Job Alert] %d new CA SCM posting(s) detected", newJobs.size());
        log.info(
                "Preparing job alert email: to={}, subject={}",
                Arrays.toString(toAddresses),
                subject);
        String body = buildBody(newJobs);
        try {
            sendHtmlEmail(subject, body, toAddresses);
            log.info("Job alert email sent successfully to {}", Arrays.toString(toAddresses));
            return true;
        } catch (Exception e) {
            log.error(
                    "Failed to send job alert email to {} after retries: {}",
                    Arrays.toString(toAddresses),
                    e.getMessage(),
                    e);
            return false;
        }
    }

    /**
     * Sends a daily summary email of recent notifiable postings.
     *
     * @return true if the email was sent successfully, false otherwise
     */
    public boolean sendDailySummary(List<JobPosting> recentJobs) {
        if (toAddresses.length == 0) {
            log.error(
                    "Notification email not configured (toAddress blank) — skipping daily summary");
            return false;
        }
        log.info(
                "Preparing daily summary email: to={}, jobs={}",
                Arrays.toString(toAddresses),
                recentJobs.size());

        String subject;
        String body;
        if (recentJobs.isEmpty()) {
            subject = "[SCM Summary] No new CA SCM postings in the last 24 hours";
            body =
                    mascotHeader()
                            + "<p>No new California SCM entry-level/internship postings in the last"
                            + " 24 hours.</p>";
        } else {
            subject =
                    String.format(
                            "[SCM Summary] %d new CA SCM posting(s) in the last 24 hours",
                            recentJobs.size());
            body = buildBody(recentJobs);
        }
        try {
            sendHtmlEmail(subject, body, toAddresses);
            return true;
        } catch (Exception e) {
            log.error("Failed to send daily summary after retries: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Sends a sample alert to an <b>explicit</b> address (test only) — deliberately ignores the
     * configured {@code toAddresses} so tests never hit the real recipient. Same body/template as a
     * real alert (mascot + sections).
     *
     * @return true if the email was sent successfully, false otherwise
     */
    public boolean sendTestAlert(List<JobPosting> jobs, String toAddress) {
        if (toAddress == null || toAddress.isBlank()) {
            return false;
        }
        String subject = String.format("[SCM Job Alert · TEST] %d sample posting(s)", jobs.size());
        try {
            sendHtmlEmail(subject, buildBody(jobs), new String[] {toAddress});
            log.info("TEST alert email sent to {}", toAddress);
            return true;
        } catch (Exception e) {
            log.error("Failed to send TEST alert to {}: {}", toAddress, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Builds the email body. Directly-scraped postings render in the main section; Adzuna-sourced
     * long-tail postings (source="adzuna") render in a separate, clearly-labeled section below.
     */
    private String buildBody(List<JobPosting> jobs) {
        List<JobPosting> direct =
                jobs.stream().filter(j -> !"adzuna".equals(j.getSource())).toList();
        List<JobPosting> adzuna =
                jobs.stream().filter(j -> "adzuna".equals(j.getSource())).toList();
        StringBuilder sb = new StringBuilder();
        sb.append(mascotHeader());
        if (!direct.isEmpty()) {
            sb.append(buildSection("New SCM Postings (California)", null, direct));
        }
        if (!adzuna.isEmpty()) {
            sb.append(
                    buildSection(
                            "Additional postings via Adzuna (third-party API · pending migration)",
                            "Sourced from the Adzuna aggregator API for employers not yet on our"
                                    + " direct ATS scrapers — these are pending migration to direct"
                                    + " monitoring, so details may be less precise; verify on the"
                                    + " linked page.",
                            adzuna));
        }
        return sb.toString();
    }

    /**
     * Centered Hello Kitty mascot image at the top of every email. Referenced as a CID inline
     * attachment ({@code cid:hellokitty}, added in {@link #sendHtmlEmail}) so it renders without
     * external hosting. {@code image-rendering:pixelated} keeps the pixel art crisp.
     */
    private static String mascotHeader() {
        return "<div style='text-align:center;margin:4px 0 16px;'>"
                + "<img src='cid:hellokitty' alt='Hello Kitty hard at work' width='170'"
                + " style='image-rendering:pixelated;'/></div>";
    }

    /** Renders one titled section: a table with one row per job and a Type column. */
    private String buildSection(String heading, String note, List<JobPosting> jobs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>").append(escape(heading)).append("</h2>");
        if (note != null) {
            sb.append("<p style='color:#555;font-size:0.9em;'>")
                    .append(escape(note))
                    .append("</p>");
        }
        sb.append(
                "<table border='1' cellpadding='8' cellspacing='0'"
                        + " style='border-collapse:collapse;'>");
        sb.append(
                "<tr><th>Type</th><th>Company</th><th>Title</th><th>Location</th>"
                        + "<th>Area</th><th>Link</th></tr>");
        for (JobPosting job : jobs) {
            sb.append("<tr>");
            sb.append("<td>").append(typeLabel(job.getLevel())).append("</td>");
            sb.append("<td>").append(escape(job.getCompany())).append("</td>");
            sb.append("<td>").append(escape(job.getTitle())).append("</td>");
            sb.append("<td>").append(escape(formatLocation(job.getLocation()))).append("</td>");
            sb.append("<td>").append(regionLabel(job.getLocation())).append("</td>");
            sb.append("<td><a href='").append(escape(job.getUrl())).append("'>Apply</a></td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    /** Maps the stored track string to a human-readable Type label. */
    private static String typeLabel(String level) {
        if (level == null) {
            return "Unsure";
        }
        return switch (level) {
            case "ENTRY_LEVEL" -> "Entry-Level";
            case "INTERNSHIP" -> "Internship";
            default -> "Unsure";
        };
    }

    // ── Metro buckets for the Area column ──
    private static final List<String> BAY_AREA_CITIES =
            List.of(
                    "bay area",
                    "san francisco",
                    "south san francisco",
                    "san jose",
                    "oakland",
                    "fremont",
                    "sunnyvale",
                    "santa clara",
                    "mountain view",
                    "palo alto",
                    "cupertino",
                    "milpitas",
                    "pleasanton",
                    "san mateo",
                    "redwood city",
                    "menlo park",
                    "san bruno",
                    "san carlos",
                    "foster city",
                    "campbell",
                    "berkeley",
                    "emeryville",
                    "hercules",
                    "san ramon",
                    "dublin",
                    "livermore",
                    "hayward",
                    "newark",
                    "santa rosa",
                    "napa",
                    "concord",
                    "walnut creek",
                    "alameda",
                    "san leandro",
                    "burlingame",
                    "san rafael");

    private static final List<String> LA_AREA_CITIES =
            List.of(
                    "los angeles",
                    "long beach",
                    "irvine",
                    "anaheim",
                    "santa ana",
                    "el segundo",
                    "torrance",
                    "city of industry",
                    "costa mesa",
                    "newport beach",
                    "manhattan beach",
                    "redondo beach",
                    "hawthorne",
                    "culver city",
                    "pasadena",
                    "burbank",
                    "glendale",
                    "thousand oaks",
                    "santa monica",
                    "ontario",
                    "san bernardino",
                    "riverside",
                    "fullerton",
                    "santa clarita",
                    "van nuys",
                    "carson",
                    "cerritos",
                    "inglewood",
                    "woodland hills",
                    "westlake village",
                    "calabasas",
                    "simi valley",
                    "oxnard");

    /**
     * Buckets a California location into one of three metro areas for the Area column: "SF Bay
     * Area", "Greater LA", or "Other" (San Diego, Sacramento, Central Valley, remote, etc.).
     */
    private static String regionLabel(String location) {
        if (location == null || location.isBlank()) {
            return "Other";
        }
        String loc = location.toLowerCase();
        if (loc.contains("remote")) {
            return "Other";
        }
        if (BAY_AREA_CITIES.stream().anyMatch(loc::contains)) {
            return "SF Bay Area";
        }
        if (LA_AREA_CITIES.stream().anyMatch(loc::contains)) {
            return "Greater LA";
        }
        return "Other";
    }

    /**
     * Sends an HTML email with retry support for transient failures. Retries up to 3 times with
     * exponential backoff (2s, 4s, 8s) on {@link MessagingException} and {@link MailException}.
     */
    private void sendHtmlEmail(String subject, String htmlBody, String[] recipients)
            throws Exception {
        retryTemplate.execute(
                context -> {
                    if (context.getRetryCount() > 0) {
                        log.warn(
                                "Email retry attempt {} for: {}", context.getRetryCount(), subject);
                    }
                    log.info("Connecting to SMTP server to send: {}", subject);
                    MimeMessage message = mailSender.createMimeMessage();
                    // MIXED_RELATED so we can inline the mascot image alongside the HTML.
                    MimeMessageHelper helper =
                            new MimeMessageHelper(
                                    message,
                                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                                    "UTF-8");
                    helper.setFrom(fromAddress);
                    helper.setTo(recipients);
                    helper.setSubject(subject);
                    helper.setText(htmlBody, true);
                    // Inline Hello Kitty mascot (cid:hellokitty in the body). Must be added AFTER
                    // setText. Best-effort — a missing image just leaves the alt text.
                    ClassPathResource kitty = new ClassPathResource("hellokitty.png");
                    if (kitty.exists()) {
                        helper.addInline("hellokitty", kitty);
                    }
                    mailSender.send(message);
                    log.info("Email SENT successfully: {}", subject);
                    return null;
                });
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Formats a job location for display. Normalizes "Remote" variants to a "Remote (CA)" label;
     * all other locations are shown as-is since the location filter already ensures they are in
     * California.
     */
    private static String formatLocation(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        if (location.toLowerCase().contains("remote")) {
            return "Remote (CA)";
        }
        return location;
    }
}
