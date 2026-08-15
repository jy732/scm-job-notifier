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

**Status:** implemented, building, and verified end-to-end. A full poll runs all 126 companies (122
config-driven across 8 ATS platforms + 4 bespoke) in ~6 min, plus an **Adzuna aggregator source**
that nets long-tail CA-SCM roles at employers not directly monitored.

---

## How It Works

A single Spring Boot process runs four scheduled jobs against a file-based H2 database. The main poll
cycle:

1. **Scrape** — every 15 min, polls 122 config-driven companies (8 ATS platforms) plus 4 bespoke
   single-company scrapers (Amazon, Microsoft, Apple, Tesla) using an 8-thread pool (3-min
   per-company timeout). **Greenhouse and Workday** fetch metadata only and defer descriptions to post-dedup;
   **Lever / Ashby / SmartRecruiters / OracleCloud** bundle descriptions into the list response (no
   lighter metadata-only call exists for them).
2. **Pre-filter** — drops stale postings, non-California locations, non-SCM titles, and
   senior/software roles (see [Pre-Filters](#pre-filters)).
3. **Dedup** — loads all known `company:externalId` keys into an in-memory set once per cycle for
   O(1) lookups (no per-job DB query).
4. **Fetch descriptions** — Greenhouse + Workday, and only for the handful of unseen jobs that
   survived the filters — so big boards don't download every job's description up front (Greenhouse's
   `content=true` list is ~10× larger; Workday would issue one detail request per job → HTTP 429).
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
| **Exclude — hourly-labor / non-SCM roles** | Drop titles that pass the SCM keyword gate but aren't professional/analytical SCM: hourly warehouse & distribution labor and clerical (material handler, warehouse associate/worker/operator/selector, order selector/picker, forklift, stocker, freight handler, clerk), materials-science/lab/machining (materials lab/R&D/characterization/technician, machinist), and facilities/safety mismatches (space planner, hazardous materials). **Keeps** professional warehouse roles (Warehouse Coordinator/Analyst/Specialist). | "Warehouse Associate", "Material Handler", "Order Selector", "Purchasing Clerk", "Materials Lab Technician", "Facilities Space Planner" |
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

122 config-driven companies across 8 ATS platforms (all verified against the live ATS API as of Aug
2026), plus 4 bespoke single-company scrapers.

**Bold** = surfaced ≥1 notifiable (entry-level / internship / unsure) CA SCM role in the Aug 2026 test
polls; the rest scrape clean but haven't produced a matching opening yet. The trailing Workday block
(abbott…bluediamond) came from **role-first discovery** — see below.

| Platform | Method | Count | Companies |
|----------|--------|-------|-----------|
| **Workday** | CXS JSON API | 68 | nvidia, intel, cisco, broadcom, **appliedmaterials**, **marvell**, **kla**, edwards, gilead, amgen, illumina, dexcom, resmed, stryker, genentech, chipotle, clorox, **niagara**, chevron (+ university site), sunrun, **bloomenergy**, levistrauss, deckers, **skechers**, **northropgrumman**, **johnsonjohnson**, **target**, mondelez, caterpillar, proctergamble, pfizer, cocacola, nissan, conagra, generalmills, kimberlyclark, walmart, toyota, pepsico, **rtx**, hp, **bd**, pwc, bakertilly, trimble, chrobinson, abbott, **thermofisher**, **motorolasolutions**, **avantor**, **teledyne**, bluediamond, worldmarket, saks, veralto, **hyve**, gap, dupont, cardinalhealth, **sysco**, **usfoods**, ingrammicro, cadence, **specialized**, **boeing**, accenture, shoepalace |
| **Greenhouse** | Boards JSON API | 26 | **flexport**, lucidmotors, nuro, samsara, **doordashusa**, instacart, **waymo**, **andurilindustries**, **spacex**, uberfreight, **aloyoga**, **carvana**, **shein**, **rocketlab**, **relativity**, **figureai**, **nerostechnologies**, leolabsinc, **flyzipline**, **vast**, **harbingermotors**, skyryse, sambanovasystems, revolutionmedicines, **purestorage**, **fashionnova** |
| **Lever** | Postings JSON API | 8 | **zoox**, veeva, aeratechnology, velo3d, **penumbrainc**, **ambirobotics**, **orcabiosystems**, gopuff |
| **Ashby** | Posting JSON API | 8 | openai, snowflake, **1x**, **mach**, **gritt**, **northwoodspace**, **crusoe**, plasmidsaurus |
| **SmartRecruiters** | Postings JSON API | 5 | **WesternDigital**, AbbVie, MattelInc, **Intuitive**, **TheWonderfulCompany** |
| **OracleCloud** | Recruiting REST API | 4 | fortinet, honeywell, oracle, albertsons |
| **SuccessFactors** | CSB tile-search HTML | 1 | sap |
| **iCIMS** | legacy fragment HTML | 2 | **ait**, nikkiso |

> **SuccessFactors note:** SF has no public JSON API (OData is per-tenant OAuth-gated). This adapter
> scrapes the Career Site Builder `tile-search-results` HTML — tenant-HTML, not a uniform API. It's
> validated on `jobs.sap.com` (SAP is a CA employer, but its CA roles are dev/enterprise-software, so
> it yields ~0 CA-SCM and is really a validation tenant). The high-value SF targets (Williams-Sonoma,
> Ross, Nestlé, Colgate) aren't reachable at their obvious hosts (non-CSB, JS-loaded, or migrated ATS)
> and need per-tenant onboarding — the adapter exists, but each host must be reverse-engineered.

> **iCIMS note:** unlike swe-job-notifier's Playwright port, this scrapes iCIMS's *legacy* search
> fragment (`{sub}.icims.com/jobs/search?pr={page}&in_iframe=1`) over plain HTTP — ~50 server-rendered
> job cards/page, no browser needed. Locations (`US-CA-City`) are normalized to `City, CA`. Validated
> on AIT Worldwide (3PL). General Atomics / Hyundai Mobis are iCIMS too but hide their subdomain behind
> a JS careers page, so they need a one-time DevTools lookup before they can be added.

#### Deferred employers (known CA-office SCM targets, not yet scrapeable)

These came out of the strict "has a real California office" pass (Tiers 1–4: tech / semiconductor /
pharma / CPG / auto / aerospace / consulting / 3PL) but sit on an ATS we don't have an adapter for,
or on a Workday tenant that blocks the CXS API. **Deferred**, not rejected — adding one adapter would
unlock a whole batch:

| Blocker | Deferred companies | Unlock |
|---------|--------------------|--------|
| **SAP SuccessFactors** | Bayer, Nestlé, Colgate-Palmolive, ExxonMobil, Williams-Sonoma, Ross Stores (Schneider → migrated to Jibe) | adapter built (validated on SAP); each tenant's CSB host must be reverse-engineered — most aren't at obvious hosts |
| **~~Phenom~~ (mislabeled)** | On verification none were Phenom: Mattel + Intuitive Surgical → SmartRecruiters (now added); Rivian → iCIMS/Jibe; L'Oréal → Avature; Cummins, Bio-Rad, The Wonderful Company → custom | no Phenom targets exist — per-ATS |
| **Eightfold** | Lam Research, Kroger (Ralphs) | Eightfold adapter |
| **iCIMS** | General Atomics, Hyundai Mobis (AIT Worldwide now added) | adapter built (HTTP); these two hide their subdomain behind a JS careers page — need a DevTools lookup |
| **Custom / in-house site** | Boeing, Lockheed Martin, TSMC, Siemens, Honda, IBM, Verizon, Accenture, Bain, Deloitte (US), KPMG, GEODIS, Expeditors, Keysight, Coupa | bespoke scraper each |
| **Workday but unreachable** | `qualcomm` (auth-gated), `seagate` + `dell` (custom domain), `lilly` (tenant bot-blocks CXS) | n/a |

#### How the target list is built (role-first discovery)

The "big brand with a California office" heuristic proved weak — a CA office full of software
engineers isn't CA supply-chain hiring, so most of the Fortune-500 additions yielded ~0 notifiable.
The better method is **role-first**: query a jobs API (Adzuna) for `{SCM titles} × California`,
rank the *employers* that actually post those roles, then wire up the ones on a supported ATS. The
first discovery pass (`abbott, thermofisher, motorolasolutions, avantor, teledyne, bluediamond`)
landed **11 notifiable across 6 companies — 4 hit on the first poll**, vs. ~2 total from the 11
brand-name additions. A second, entry-focused pass added `worldmarket, aloyoga, carvana, velo3d`
(Alo Yoga + Carvana yielded 5 more), and a third added `saks, veralto, shein` (SHEIN yielded). A later
**exhaustive** pass (30 SCM phrases, ~500 genuine-SCM employers) was tiered by CA-HQ + entry-title
signal; its **Tier 1** added 11 companies (Rocket Lab, Relativity, Figure, Neros, 1X, Mach, Penumbra, Hyve, …)
— **8 of 11 yielded on the first poll (18 notifiable)** — and **Tier 2** added 18 more (Vast, Zipline,
Orca Bio, Sysco, US Foods, Cardinal Health, Ingram Micro, Cadence, Pure Storage, Crusoe, …) for **25
notifiable from 11 of 18**. Across all discovery, total poll notifiable rose from ~78 → ~151. Winners
skew to CA-HQ space / robotics / hardware / life-science / food-distribution ops, not tech brands.

### Single-company scrapers (bespoke)

Ported from swe-job-notifier and re-targeted for SCM — each searches supply-chain terms (multi-query,
de-duplicated), narrowed to CA where the site allows and enforced by the California pre-filter. No
company list; one scraper per class.

| Scraper | Method | Status (Aug 2026) |
|---------|--------|-------------------|
| **Amazon** | Jobs search JSON API | ✅ working (~375 raw SCM hits before filtering) |
| **Microsoft** | PCSX search JSON API | ✅ working — but the search API returns **no description**, so its postings are classified by title (Stage 1) + Gemini (Stage 3) only |
| **Apple** | Playwright (hydration JSON) | ✅ working (~192 raw hits; descriptions inline) |
| **Tesla** | Playwright (DOM) | ⚠️ **blocked by Akamai WAF** — headless requests get "Access Denied", so the scraper returns 0 and fails gracefully (no error). Structurally correct; yielding results would need a stealth / residential-proxy setup or Tesla's internal API. |

Still **deferred** (not yet ported, low SCM yield): Google, Meta, Netflix, TikTok.

### Aggregator source (Adzuna) — the long-tail net

The direct scrapers cover ~126 known employers with full metadata and 15-min freshness. **Adzuna**
(`AdzunaScraper`) complements them by querying the [Adzuna jobs API](https://developer.adzuna.com) for
`{SCM titles} × California` across *every* board — surfacing roles at the ~450 long-tail employers
(small/custom ATSs) we can't scrape directly. Same `JobScraper` interface, so it reuses the whole
pipeline (CA/SCM filters, dedup, classifier, email). Design:

- **Additive only** — excludes any employer already covered by a direct scraper (built from all
  configured slugs + aliases), so it never duplicates their jobs; it's purely the long-tail net.
- **Self-throttled** — participates in the poll but only hits the API every `throttle-minutes` (240 = 4 h),
  staying under the ~250 calls/day free tier (30 phrases × 6 runs/day ≈ 180 calls/day).
- **Separate email section** — every Adzuna posting is tagged `source="adzuna"` and rendered in its own
  "Additional postings via Adzuna (third-party API · pending migration)" table, below the directly-monitored
  postings, clearly flagged as aggregator data pending migration.
- **Discovery → migration** — Adzuna doubles as a discovery feed: employers it surfaces that run a
  supported ATS get **migrated to a direct scraper** (full-board coverage vs Adzuna's thin sample, and
  Adzuna then excludes them). See the `migrate-companies` skill (`scripts/ats-detect.sh` +
  `scripts/test-poll.sh`) for the verify → wire-in → test-poll → pre/post-diff workflow.
- **Trade-off** — breadth over freshness: Adzuna lags hours–days and gives snippet-only descriptions
  (title + snippet + Gemini still classify). Disabled automatically if the API keys are blank.

_Verified: one live fetch → 989 raw → 683 long-tail → 370 CA → 190 SCM-relevant → 168 notifiable at long-tail
employers outside the directly-monitored set._

> **Playwright note:** Tesla and Apple use a headless Chromium browser (Playwright). This pushes the
> runnable jar to ~275 MB and downloads Chromium on first run. If you don't need them, removing the
> `com.microsoft.playwright` dependency + `PlaywrightConfig` + the two scrapers drops the jar to ~75 MB.

---

## Prerequisites

- Java 17+ (builds/runs on 21)
- Maven (wrapper included)
- Chromium — auto-installed by Playwright on first run (needed only for the Tesla/Apple scrapers)
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
| `job.workday.companies[n].*` / `job.oraclecloud.companies[n].*` / `job.icims.companies[n].*` | populated | indexed ATS configs |
| `job.adzuna.enabled` / `app-id` / `app-key` | `true` / `${ADZUNA_APP_ID:}` / `${ADZUNA_APP_KEY:}` | Adzuna aggregator source (off if keys blank) |
| `job.adzuna.throttle-minutes` / `max-days-old` / `pages` | `240` / `30` / `1` | Adzuna cadence + query window |

## Tech Stack

- **Framework:** Spring Boot 4.0.5, Java 17
- **HTTP scraping:** WebClient (WebFlux), 64 MB buffer, 10 s connect / 30 s read timeouts
- **Browser scraping:** Playwright 1.52.0 (headless Chromium) for Tesla + Apple
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
