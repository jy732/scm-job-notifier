package com.github.jingyangyu.scmjobnotifier.service.classification;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Low-level client for the Gemini generativeLanguage API.
 *
 * <p>Handles prompt construction, HTTP communication, and response parsing. Does NOT handle
 * batching, rate limiting, or retry — that orchestration lives in {@link JobClassifier}.
 *
 * <p>Uses a single 4-way SCM track classification (ENTRY_LEVEL/INTERNSHIP/UNSURE/OTHER) per batch.
 * The system prompt instructs Gemini to respond in a strict {@code "1:ENTRY_LEVEL\n2:INTERNSHIP"}
 * format. Signal extraction is delegated to {@link SignalExtractor}.
 */
@Slf4j
@Component
public class GeminiClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;

    /** Overridable so tests can point the client at a loopback server. */
    private final String baseUrl;

    /**
     * Gemini needs a longer ceiling than the shared 30s client. A batch of 50 jobs now carries the
     * Responsibilities/Qualifications text rather than a few keyword snippets (~13k prompt tokens
     * vs ~600), and generation regularly runs past 30s — which surfaced as {@code
     * ReadTimeoutException}, three failed retries, and the batch dumped into the
     * AUTO_APPROVED-UNSURE fallback, i.e. exactly the unclassified output the richer prompt exists
     * to prevent. Raised here rather than on the shared builder so scrapers keep failing fast.
     */
    private static final int GEMINI_TIMEOUT_S = 120;

    public GeminiClient(
            WebClient.Builder webClientBuilder,
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.model:gemini-2.5-flash}") String model,
            @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com}")
                    String baseUrl) {
        this.webClient =
                webClientBuilder
                        .clone()
                        .clientConnector(
                                new ReactorClientHttpConnector(
                                        HttpClient.create()
                                                .responseTimeout(
                                                        Duration.ofSeconds(GEMINI_TIMEOUT_S))
                                                .doOnConnected(
                                                        conn ->
                                                                conn.addHandlerLast(
                                                                        new ReadTimeoutHandler(
                                                                                GEMINI_TIMEOUT_S,
                                                                                TimeUnit
                                                                                        .SECONDS)))))
                        .build();
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;

        if (apiKey == null || apiKey.isBlank()) {
            log.error(
                    "██ GEMINI API KEY NOT CONFIGURED ██ "
                            + "— all ambiguous jobs will fall back to UNSURE (still emailed)");
        } else {
            log.info("Gemini configured: model={}", model);
        }
    }

    private static final String LEVEL_SYSTEM_PROMPT =
            "You classify supply-chain-management (SCM) job postings by career stage. "
                    + "Use the title and the job details provided. Categories: "
                    + "INTERNSHIP = internship / co-op / summer program for a currently-enrolled "
                    + "student. "
                    + "ENTRY_LEVEL = full-time early-career role: new grad, associate, coordinator, "
                    + "rotational/leadership development program, or 0-3 years experience. "
                    + "UNSURE = clearly early-career SCM but you cannot confidently decide between "
                    + "internship and full-time entry-level. "
                    + "OTHER = anything else: senior/manager/lead, 4+ years experience, hourly/manual "
                    + "warehouse or production labor (material handler, warehouse associate, order "
                    + "selector/picker, forklift, stocker, clerk — we target only professional/"
                    + "analytical SCM roles like analyst, planner, buyer, coordinator, specialist), "
                    + "OR any role that is NOT supply chain / logistics / procurement / sourcing / "
                    + "planning / operations (e.g. software, finance, sales, marketing, HR). "
                    + "Response format, one line per job: 1:ENTRY_LEVEL\\n2:INTERNSHIP\\n3:OTHER\\n"
                    + "4:UNSURE";

    /** Returns true if the Gemini API key is configured and non-blank. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Builds the numbered user prompt: title + the job's Responsibilities/Qualifications (or the
     * description head when the posting has no headings), via {@link
     * SignalExtractor#describeForPrompt}. Only a posting with no description at all now arrives
     * here empty — previously 84.5% of jobs reaching this stage carried nothing but a title.
     */
    String buildPrompt(List<JobPosting> batch) {
        StringBuilder sb = new StringBuilder("Classify these job postings:\n\n");
        int withEvidence = 0;
        for (int i = 0; i < batch.size(); i++) {
            JobPosting job = batch.get(i);
            String evidence = SignalExtractor.describeForPrompt(job);
            if (!"(none)".equals(evidence)) {
                withEvidence++;
            }
            log.debug("Prompt evidence [{}] {}: {}", job.getCompany(), job.getTitle(), evidence);
            sb.append(String.format("%d. Title: %s\n", i + 1, job.getTitle()));
            sb.append(String.format("   Details: %s\n\n", evidence));
        }
        log.info(
                "Prompt evidence: {}/{} job(s) had a description, {} had none",
                withEvidence,
                batch.size(),
                batch.size() - withEvidence);
        return sb.toString();
    }

    /**
     * Sends a batch of jobs to Gemini for 4-way SCM track classification.
     *
     * @return map of job to track string (ENTRY_LEVEL/INTERNSHIP/UNSURE/OTHER), or {@code null} if
     *     the API call failed (signals caller to retry).
     */
    public Map<JobPosting, String> classifyLevel(List<JobPosting> batch) {
        Map<String, Object> response = callApi(buildPrompt(batch), LEVEL_SYSTEM_PROMPT);
        return parseLevelResponse(response, batch);
    }

    @SuppressWarnings("unchecked")
    Map<JobPosting, String> parseLevelResponse(
            Map<String, Object> response, List<JobPosting> batch) {
        if (response == null) {
            return null;
        }
        try {
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            String text = parts.get(0).get("text").toString().trim();

            log.debug("Gemini level raw response: {}", text);

            Set<String> validLevels = Set.of("ENTRY_LEVEL", "INTERNSHIP", "UNSURE", "OTHER");
            Map<JobPosting, String> result = new HashMap<>();
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split(":");
                if (tokens.length == 2) {
                    int index = Integer.parseInt(tokens[0].trim()) - 1;
                    String level = tokens[1].trim().toUpperCase();
                    if (validLevels.contains(level) && index >= 0 && index < batch.size()) {
                        result.put(batch.get(index), level);
                    }
                }
            }
            log.info("Gemini track-classified {}/{} job(s)", result.size(), batch.size());
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse Gemini level response", e);
            return null;
        }
    }

    private Map<String, Object> callApi(String userPrompt, String systemPrompt) {
        Map<String, Object> requestBody =
                Map.of(
                        "system_instruction",
                        Map.of("parts", List.of(Map.of("text", systemPrompt))),
                        "contents",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "parts",
                                        List.of(Map.of("text", userPrompt)))),
                        // Classification is not a creative task. Without this the API defaults to
                        // temperature 1.0 and the same batch can come back labelled differently on
                        // consecutive calls. (Measured: it is not sufficient on its own — a
                        // no-evidence prompt still flipped at temperature 0 — but leaving sampling
                        // on adds variance for nothing.)
                        "generationConfig",
                        Map.of("temperature", 0));

        String url =
                String.format("%s/v1beta/models/%s:generateContent?key=%s", baseUrl, model, apiKey);

        return webClient
                .post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }
}
