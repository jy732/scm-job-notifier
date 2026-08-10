# SCM Job Notifier

Automated job-posting monitor that scrapes California company career sites, filters for **entry-level
and internship Supply Chain Management** roles, classifies each posting via Gemini AI, and sends a
single email alert with a Type + Area breakdown.

Structural sibling of [`swe-job-notifier`](../swe-job-notifier) — same Spring Boot 4 / Java 17 / H2
stack and package layout. Three axes are re-targeted for SCM: **classification** (SWE level L3/L4 →
SCM track ENTRY_LEVEL / INTERNSHIP / UNSURE), **location** (US → California only), and **relevance**
(software titles → supply-chain titles). Full design rationale and decisions D1–D4 are in
[`docs/technical-design.html`](docs/technical-design.html).

> 📨 **Receiving the alert emails and not an engineer?** See the plain-language guide:
> [中文使用说明 (Chinese guide for email recipients)](README.zh-CN.md).

**Status:** implemented, building, and verified end-to-end. A full poll runs all 41 companies across
6 scrapers in ~150s with zero scrape failures.

---

## How It Works

A single Spring Boot process runs four scheduled jobs against a file-based H2 database. The main poll
cycle:

1. **Scrape** — every 15 min, polls 41 company career sites using an 8-thread pool (3-min per-company
   timeout). API scrapers (Greenhouse/Lever/Ashby/SmartRecruiters/OracleCloud) return descriptions
   in the list response; Workday returns metadata only and defers descriptions to post-dedup.
2. **Pre-filter** — drops stale postings, non-California locations, non-SCM titles, and
   senior/software roles (see [Pre-Filters](#pre-filters)).
3. **Dedup** — loads all known `company:externalId` keys into an in-memory set once per cycle for
   O(1) lookups (no per-job DB query).
4. **Fetch descriptions** — Workday only, and only for the handful of unseen jobs that survived the
   filters — so big boards don't issue thousands of detail requests (which triggered HTTP 429).
5. **Classify** — a three-stage pipeline assigns each posting a track: ENTRY_LEVEL / INTERNSHIP /
   UNSURE / OTHER (see [Classification Pipeline](#classification-pipeline)).
6. **Persist** — batch `saveAll()` with batch-loaded existing rows (single query, no N+1). Gemini
   failures are retried on later polls; after 3 failures a job is auto-approved as UNSURE.
7. **Email alert** — an independent 5-minute scan sends **one** email containing all unnotified
   ENTRY_LEVEL / INTERNSHIP / UNSURE postings, then marks them notified.

### End-to-end flow

```
                        every 15 min
                             │
   ┌─────────────────────────▼─────────────────────────────────┐
   │ scrape → freshness → exclude (senior/software) → California │
   │  → SCM-relevance → dedup → fetch JD (Workday) → classify    │
   │  → persist                                                  │
   └─────────────────────────┬─────────────────────────────────┘
                             │  (level written to DB)
      every 5 min            ▼
   ┌───────────────────────────────────┐    daily 08:00 → summary email
   │ query unnotified ENTRY/INTERN/     │    daily 03:00 → delete jobs >90 days
   │ UNSURE → send ONE email → mark     │
   │ notified                          │
   └───────────────────────────────────┘
```

---

## Classification Pipeline

Every scraped job passes pre-filters, then a three-stage classifier. Jobs failing any pre-filter gate
are silently dropped.

### Pre-Filters

Implemented in `JobTitleFilter`, run in order. Any failure drops the job.

| Filter | Logic | Example drops |
|--------|-------|---------------|
| **Freshness** | Reject postings older than `job.retention.days` (90) by `postedDate`. Jobs with no date pass (all Workday jobs). | Stale re-posts |
| **Exclude — seniority** | Drop titles containing senior, sr., staff, principal, manager, director, VP, head of, chief, supervisor, president, or standalone "lead". Interns are **not** excluded. | "Sr. Buyer", "Supply Chain Manager" |
| **Exclude — non-SCM (guarded)** | Drop titles with `engineer`/`scientist`/`developer` **unless** they carry a strong SCM anchor (supply chain, supplier, sourcing, procurement, purchasing, logistics, warehouse, inventory, commodity, s&op, replenishment). | "Software Engineer, Freight Systems", "Materials Engineer, Metals" — but **keeps** "Supplier Development Engineer", "Sourcing Engineer" |
| **California only** | Keep only CA roles: a "california"/", CA" token, or a known CA city. "Remote" is kept **only** with a CA token. Non-US locations rejected. | "Austin, TX", "Remote - US", "Toronto, Canada" |
| **SCM relevance** | Require ≥1 supply-chain keyword: supply chain, logistics, procurement, sourcing, purchasing, buyer, inventory, warehouse, fulfillment, distribution, transportation, demand/supply/production planning, planner, materials, commodity, s&op, mrp, freight, customs, supplier, replenishment, 3pl. | "Product Manager", "Financial Analyst" |

### Stage 1 — Title rules (`JobTitleFilter.autoClassifyLevel`) — zero cost

Checked in order; first match wins, else `null` → Stage 2.

- **1A INTERNSHIP** (`INTERNSHIP_PATTERN`): `intern`/`internship`, `co-op`, `summer analyst/associate/scholar` → **INTERNSHIP**
- **1B ENTRY_LEVEL** (`ENTRY_LEVEL_PATTERN`): new/recent/university/college grad, entry-level, jr/junior, `"<role> I/1"` (analyst/coordinator/specialist/planner/buyer/associate), rotational / leadership development / management trainee / development program, campus / early career / trainee → **ENTRY_LEVEL**
- **1C ENTRY_LEVEL** (`ENTRY_ROLE_NOUNS`): title contains `coordinator` / `assistant` / `administrator` → **ENTRY_LEVEL**

Precedence: 1A ▸ 1B ▸ 1C. Bare functional titles ("Supply Chain Analyst", "Buyer", "Demand Planner")
match none and defer.

### Stage 2 — Description signals (`SignalExtractor.inferLevelFromDescription`) — local, no API

- **Enrollment signal** ("currently enrolled", "pursuing a degree", "rising senior") → **INTERNSHIP** (checked first)
- **YOE > 3** → **OTHER** (too senior)
- **YOE 0–3**, no enrollment signal → **ENTRY_LEVEL** (Decision D4)
- else `null` → Stage 3

### Stage 3 — Gemini 2.5 Flash (`JobClassifier` + `GeminiClient`)

- Remaining ambiguous jobs batched (50/call) and sent to Gemini for a 4-way call:
  **ENTRY_LEVEL / INTERNSHIP / UNSURE / OTHER**. Prompt includes the title + extracted `Signal`
  snippets and routes non-SCM/senior roles to OTHER.
- Batches retried up to 3× with exponential backoff.
- **No `GEMINI_API_KEY`** → every ambiguous job becomes **UNSURE** (still emailed) so the app runs
  without Gemini.
- **API failure** → `classificationFailures++`, retried next poll; after 3 failures → **UNSURE**
  (Decision D3 — never silently dropped).

### Tracks and routing

| Track | Meaning | Emailed? |
|-------|---------|----------|
| `ENTRY_LEVEL` | full-time early-career (0–3 YOE, new grad, coordinator/associate) | ✅ Type = Entry-Level |
| `INTERNSHIP` | intern / co-op / summer | ✅ Type = Internship |
| `UNSURE` | early-career SCM, can't confidently split entry vs. intern | ✅ Type = Unsure |
| `OTHER` | senior, 4+ YOE, or non-SCM | ❌ stored only |

---

## Email Alert

An independent 5-minute scan (`NotificationService`) sends **one** email to `NOTIFICATION_EMAIL` with
every unnotified ENTRY_LEVEL / INTERNSHIP / UNSURE posting as rows in a single table (Decision D1).
The daily 8 AM summary uses the same layout.

**Subject:** `[SCM Job Alert] 3 new CA SCM posting(s) detected`

**Body** — *New SCM Postings (California)*:

| Type | Company | Title | Location | Area | Link |
|------|---------|-------|----------|------|------|
| Entry-Level | Edwards Lifesciences | Supply Chain Analyst | Irvine, CA | Greater LA | Apply |
| Internship | Chevron | Supply Chain Intern – Summer 2026 | San Ramon, CA | SF Bay Area | Apply |
| Unsure | Illumina | Inventory Analyst | San Diego, CA | Other | Apply |

- **Type** — the track label (Entry-Level / Internship / Unsure).
- **Area** — buckets the CA location into **SF Bay Area / Greater LA / Other** (San Diego, Sacramento,
  Central Valley, and remote → Other).
- **Location** — remote roles display as `Remote (CA)`.
- A rendered preview is in [`docs/technical-design.html`](docs/technical-design.html) §7.

---

## Supported Platforms & Companies

41 companies, all verified against the live ATS API (return jobs) as of Aug 2026.

| Platform | Method | Count | Companies |
|----------|--------|-------|-----------|
| **Workday** | CXS JSON API | 26 | nvidia, intel, cisco, broadcom, appliedmaterials, marvell, kla, edwards, gilead, amgen, illumina, dexcom, resmed, stryker, genentech, chipotle, clorox, niagara, chevron (+ university site), sunrun, bloomenergy, levistrauss, deckers, skechers, northropgrumman |
| **Greenhouse** | Boards JSON API | 9 | flexport, lucidmotors, nuro, samsara, doordashusa, instacart, waymo, andurilindustries, spacex |
| **Lever** | Postings JSON API | 2 | zoox, veeva |
| **Ashby** | Posting JSON API | 2 | openai, snowflake |
| **SmartRecruiters** | Postings JSON API | 1 | WesternDigital |
| **OracleCloud** | Recruiting REST API | 1 | fortinet |

**Dropped** (no supported ATS): `qualcomm` (Workday moved/auth-gated), `seagate` (Workday on custom
domain), `bio-rad` (Phenom). **Deferred:** the bespoke single-company scrapers (Amazon/Apple/Google/…)
hire CA SCM but hardcode SWE-style queries and need per-scraper rework.

---

## Prerequisites

- Java 17+ (builds/runs on 21)
- Maven (wrapper included)
- Gmail account with an [App Password](https://myaccount.google.com/apppasswords) (for sending)
- Gemini API key (optional — without it, all ambiguous jobs are approved as UNSURE)

## Setup

1. Create a `.env` (gitignored) from the template:

   ```bash
   cp .env.example .env
   ```

2. Fill in credentials:

   ```properties
   EMAIL_USERNAME=you@gmail.com
   EMAIL_APP_PASSWORD=your-gmail-app-password
   NOTIFICATION_EMAIL=recipient@example.com     # single recipient list (comma-separated OK)
   GEMINI_API_KEY=your-gemini-api-key           # optional
   ```

3. Run:

   ```bash
   ./start.sh          # sources .env, then mvn spring-boot:run
   ```

Runs on **port 8081** so it can run alongside `swe-job-notifier` (port 8080). Its H2 database lives in
this project's own `./data/` directory, independent of the SWE app's.

### Manual / debug endpoints

The scheduled poll runs every 15 min, but you can trigger work on demand (handlers run off the
WebFlux event loop):

```bash
curl -X POST http://localhost:8081/api/test/scrape/greenhouse/spacex   # one company
curl -X POST http://localhost:8081/api/test/scrape-all                 # every company (counts)
curl -X POST http://localhost:8081/api/test/poll                       # one full poll cycle
```

## Scheduled Jobs

| Job | Property | Schedule | Description |
|-----|----------|----------|-------------|
| **Poll** | `job.poll.cron` | every 15 min | scrape → filter → classify → persist |
| **Alert scan** | `job.notification.scan.cron` | every 5 min | email unnotified ENTRY/INTERN/UNSURE jobs |
| **Daily summary** | `job.summary.cron` | 08:00 | digest of the last 24 h |
| **Cleanup** | `job.cleanup.cron` | 03:00 | delete jobs older than `job.retention.days` (90) |

To disable a schedule without code changes, set its cron to `-` (Spring's disabled-trigger value);
the method stays callable via the debug endpoint.

## Project Structure

```
src/main/java/com/github/jingyangyu/scmjobnotifier/
├── ScmJobNotifierApplication.java          # entry point (@EnableScheduling/@EnableRetry)
├── config/
│   ├── WebClientConfig.java                # shared WebClient (64 MB buffer, timeouts)
│   ├── WorkdayProperties.java              # indexed Workday company configs
│   ├── OracleCloudProperties.java          # OracleCloud company configs
│   └── IcimsProperties.java                # iCIMS configs (none configured)
├── controller/
│   └── ScrapeTestController.java           # /api/test/{scrape,scrape-all,poll}
├── model/
│   └── JobPosting.java                     # JPA entity (level = track string)
├── notification/
│   └── EmailNotifier.java                  # single-email builder + region (Area) bucketing
├── repository/
│   └── JobPostingRepository.java           # Spring Data JPA (notifiable queries)
├── scraper/
│   ├── JobScraper.java                     # interface (two-phase scrape/fetchDescriptions)
│   ├── GreenhouseScraper.java  LeverScraper.java  AshbyScraper.java
│   ├── SmartRecruitersScraper.java  WorkdayScraper.java  OracleCloudScraper.java
├── service/
│   ├── JobPollingService.java              # 15-min orchestrator (8-thread pool)
│   ├── NotificationService.java            # 5-min single-email scan
│   ├── DailySummaryService.java            # 8 AM digest
│   ├── JobCleanupService.java              # 90-day retention cleanup
│   ├── PipelineMetrics.java                # Micrometer counters/gauges
│   └── classification/
│       ├── ClassificationPipeline.java     # 3-stage orchestrator
│       ├── FilterKeywords.java             # exclude / entry / SCM / CA keyword sets + patterns
│       ├── JobTitleFilter.java             # pre-filters + Stage-1 title classification
│       ├── SignalExtractor.java            # Stage-2 signals + YOE/enrollment inference
│       ├── Signal.java  ClassificationResult.java
│       ├── GeminiClient.java               # Gemini prompt + HTTP + parsing
│       └── JobClassifier.java              # batch Gemini classification with retry
└── util/
    └── CsvUtil.java
```

## Observability

**Metrics** — via Actuator at `http://localhost:8081/actuator/metrics/job.*`:

- `job.gemini.calls` (success/failure) · `job.gemini.retries`
- `job.scrape` (success/failure) · `job.email` (success/failure)
- `job.pipeline.scraped` · `job.pipeline.classified` · `job.pipeline.auto_approved` ·
  `job.pipeline.auto_approved_fallback`
- `job.classify.stage` (tag `stage` = `title_rules` | `description_signals` | `gemini`)
- `job.poll.duration` (timer) · `job.unnotified` (gauge)

**Health:** `curl http://localhost:8081/actuator/health` (the `mail` component shows DOWN until SMTP
is configured). **Logs:** rolling files via `logback-spring.xml`.

## Configuration

All settings live in `src/main/resources/application.properties`:

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8081` | runs alongside swe-job-notifier (8080) |
| `job.poll.cron` | `0 */15 * * * *` | poll frequency (`-` to disable) |
| `job.notification.scan.cron` | `0 */5 * * * *` | alert-scan frequency |
| `job.summary.cron` | `0 0 8 * * *` | daily summary time |
| `job.cleanup.cron` / `job.retention.days` | `0 0 3 * * *` / `90` | cleanup schedule / retention |
| `spring.task.scheduling.pool.size` | `4` | scheduler thread pool |
| `gemini.model` | `gemini-2.5-flash` | Gemini model |
| `job.notification.to` | `${NOTIFICATION_EMAIL:}` | single recipient list |
| `job.companies.{greenhouse,lever,ashby,smartrecruiters}` | populated | comma-separated slugs |
| `job.workday.companies[n].*` / `job.oraclecloud.companies[n].*` | populated | indexed ATS configs |

## Tech Stack

- **Framework:** Spring Boot 4.0.5, Java 17
- **HTTP scraping:** WebClient (WebFlux), 64 MB buffer, 10 s connect / 30 s read timeouts
- **Database:** H2 (file-based), Spring Data JPA
- **AI:** Google Gemini 2.5 Flash (3-stage classifier, Stage 3 only)
- **Email:** Spring Mail (Gmail SMTP), retry with backoff
- **Metrics:** Micrometer + Spring Boot Actuator
- **Build:** Maven with Spotless (google-java-format, AOSP)

## Notes

- The SMTP socket timeouts in `application.properties` are deliberate — without them JavaMail defaults
  to infinite and a dropped TCP connection wedges the scheduler thread.
- Run `./mvnw spotless:apply` after editing Java (AOSP style, google-java-format 1.22.0).
- Stop the app before running Maven — a running instance holds the H2 file lock.
- Workday returns no `postedDate`, so those jobs always pass the freshness filter and are deduped by
  `company:externalId` instead.
