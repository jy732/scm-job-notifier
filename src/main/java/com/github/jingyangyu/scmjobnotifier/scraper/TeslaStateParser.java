package com.github.jingyangyu.scmjobnotifier.scraper;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses Tesla's careers "state" payload ({@code /cua-api/apps/careers/state}) into {@link
 * JobPosting}s. The payload is a single global board: a {@code listings} array of coded rows plus a
 * {@code lookup} dictionary that resolves the codes.
 *
 * <p>Listing shape (verified against a live response): {@code {id, t=title, l=locationId, y=typeId,
 * dp=deptId, f=subDept}}. Locations resolve via {@code lookup.locations} (id → "City, State", e.g.
 * {@code 401022 → "Palo Alto, California"}) — which is exactly what the downstream California
 * filter reads — and types via {@code lookup.types} ({@code 1=fulltime, 3=intern, …}).
 *
 * <p>Pure and dependency-light on purpose so it can be unit-tested against a saved fixture without
 * a browser or network. Access (minting Akamai-valid cookies) is handled separately by the scraper.
 */
public final class TeslaStateParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JOB_URL = "https://www.tesla.com/careers/search/job/";

    private TeslaStateParser() {}

    /**
     * Parses the raw {@code state} JSON into job postings (all sites; the pipeline filters CA/SCM).
     */
    public static List<JobPosting> parse(String stateJson) {
        List<JobPosting> jobs = new ArrayList<>();
        if (stateJson == null || stateJson.isBlank()) {
            return jobs;
        }
        JsonNode root = MAPPER.readTree(stateJson);
        JsonNode lookup = root.path("lookup");
        JsonNode locations = lookup.path("locations");
        JsonNode types = lookup.path("types");
        JsonNode listings = root.path("listings");
        Instant now = Instant.now();
        for (JsonNode j : listings) {
            String id = j.path("id").asString("");
            String title = j.path("t").asString("");
            if (id.isEmpty() || title.isEmpty()) {
                continue;
            }
            // l/y come back as either strings or numbers depending on the row — normalize to text
            // before dictionary lookup so both resolve.
            String location = locations.path(j.path("l").asString("")).asString("");
            jobs.add(
                    JobPosting.builder()
                            .company("tesla")
                            .externalId(id)
                            .title(title)
                            .url(JOB_URL + id)
                            .location(location)
                            .description("")
                            .postedDate(null)
                            .detectedAt(now)
                            .notified(false)
                            .build());
        }
        return jobs;
    }
}
