package com.github.jingyangyu.scmjobnotifier.controller;

import com.github.jingyangyu.scmjobnotifier.service.ClassificationReplayService;
import com.github.jingyangyu.scmjobnotifier.service.ClassificationReplayService.ReplaySummary;
import com.github.jingyangyu.scmjobnotifier.service.classification.ClassificationSource;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Admin endpoint to replay classification for jobs processed during a degraded window and re-queue
 * missed/upgraded alerts. Dry-run by default so the transition breakdown is visible before any
 * re-send. Delegates all logic (selection, re-classify, requeue policy) to {@link
 * ClassificationReplayService}.
 */
@Slf4j
@RestController
@RequestMapping("/api/replay")
public class ClassificationReplayController {

    /** Default target when no selector is supplied: the low-confidence provenance outcomes. */
    private static final Set<String> DEFAULT_SOURCES =
            Set.of(ClassificationSource.FALLBACK_UNSURE, ClassificationSource.AUTO_APPROVED);

    private final ClassificationReplayService replayService;

    public ClassificationReplayController(ClassificationReplayService replayService) {
        this.replayService = replayService;
    }

    /**
     * Re-runs classification and re-queues material upgrades.
     *
     * @param sources comma-separated provenance values to target; defaults to FALLBACK_UNSURE +
     *     AUTO_APPROVED when neither sources nor a window is given
     * @param since ISO-8601 instant, inclusive window lower bound (e.g. {@code
     *     2026-08-28T00:00:00Z})
     * @param until ISO-8601 instant, exclusive window upper bound
     * @param limit max jobs to re-classify (0 = no cap)
     * @param dryRun when true (default), computes transitions without writing or re-queuing
     */
    @PostMapping("/classification")
    public Mono<ReplaySummary> replayClassification(
            @RequestParam(required = false) String sources,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return Mono.fromCallable(
                () -> {
                    Set<String> src = parseSources(sources);
                    Instant sinceTs;
                    Instant untilTs;
                    try {
                        sinceTs = parseInstant(since);
                        untilTs = parseInstant(until);
                    } catch (Exception e) {
                        return ReplaySummary.error(
                                "invalid since/until — use an ISO-8601 instant like"
                                        + " 2026-08-28T00:00:00Z: "
                                        + e.getMessage());
                    }
                    // Default to the low-confidence provenance when nothing was specified at all.
                    if (src == null && sinceTs == null && untilTs == null) {
                        src = DEFAULT_SOURCES;
                    }
                    log.info(
                            "Replay requested: sources={}, since={}, until={}, limit={}, dryRun={}",
                            src,
                            sinceTs,
                            untilTs,
                            limit,
                            dryRun);
                    return replayService.replay(src, sinceTs, untilTs, limit, dryRun);
                });
    }

    private static Set<String> parseSources(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        Set<String> set =
                Arrays.stream(csv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
        return set.isEmpty() ? null : set;
    }

    private static Instant parseInstant(String value) {
        return (value == null || value.isBlank()) ? null : Instant.parse(value);
    }
}
