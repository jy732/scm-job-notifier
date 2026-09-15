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

**Status:** implemented, building, and verified end-to-end. A full poll runs all ~273 companies (259
config-driven across 10 ATS platforms + ~14 bespoke/other-ATS targets) in ~10 min, plus an **Adzuna aggregator source**
that nets long-tail CA-SCM roles at employers not directly monitored. The roster is grown by a
built-in **discovery engine** (JSearch + ATS-dork + ATS-census) — see [ATS-native
discovery](#ats-native-discovery--the-core-enrichment-engine).

---

## How It Works

A single Spring Boot process runs four scheduled jobs against a file-based H2 database. The main poll
cycle:

1. **Scrape** — every 15 min, polls 259 config-driven companies (10 ATS platforms) plus ~14
   bespoke/other-ATS targets (Amazon, Apple, Microsoft, Tesla, Google, ByteDance, Eightfold, Jibe, …)
   using a 12-thread pool (3-min per-company timeout). **Greenhouse and Workday** fetch metadata only
   and defer descriptions to post-dedup;
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

259 config-driven companies across 10 ATS platforms (all verified against the live ATS API when added),
plus ~14 bespoke / other-ATS targets (see [below](#bespoke--other-ats-scrapers-14-targets)).

Counts below are current; the **company lists are representative, not exhaustive** — the
[discovery engine](#ats-native-discovery--the-core-enrichment-engine) adds employers regularly, so
`src/main/resources/application.properties` is the live source of truth. **Bold** = surfaced ≥1
notifiable CA-SCM role in a test poll when added; the rest scrape clean but haven't yet.

| Platform | Method | Count | Representative companies |
|----------|--------|-------|--------------------------|
| **Workday** | CXS JSON API | 98 | nvidia, intel, cisco, broadcom, **appliedmaterials**, **marvell**, **kla**, edwards, gilead, amgen, illumina, dexcom, resmed, stryker, genentech, chipotle, clorox, **niagara**, chevron (+ university site), sunrun, **bloomenergy**, levistrauss, deckers, **skechers**, **northropgrumman**, **johnsonjohnson**, **target**, mondelez, caterpillar, proctergamble, pfizer, cocacola, nissan, conagra, generalmills, kimberlyclark, walmart, toyota, pepsico, **rtx**, hp, **bd**, pwc, bakertilly, trimble, chrobinson, abbott, **thermofisher**, **motorolasolutions**, **avantor**, **teledyne**, bluediamond, worldmarket, saks, veralto, **hyve**, gap, dupont, cardinalhealth, **sysco**, **usfoods**, ingrammicro, cadence, **specialized**, **boeing**, accenture, shoepalace, moog, airgas, peets, safelite, legends, stanfordhealthcare, hdsupply, huntsman, lennar, rosendin, universalmusic, solarturbines, pcipharma, altamed, ryder, novartis, backroads, flex, logitech, micron, nxp, iherb, cellink, anheuserbusch, iqvia, adobe, danaher, nextracker |
| **Greenhouse** | Boards JSON API | 75 | **flexport**, lucidmotors, nuro, samsara, **doordashusa**, instacart, **waymo**, **andurilindustries**, **spacex**, uberfreight, **aloyoga**, **carvana**, **shein**, **rocketlab**, **relativity**, **figureai**, **nerostechnologies**, leolabsinc, **flyzipline**, **vast**, **harbingermotors**, skyryse, sambanovasystems, revolutionmedicines, **purestorage**, **fashionnova**, nordicnaturals, vardaspace, wing, oura, sharpelectronics, smartsheet, stripe, voyagertechnologiesinc, k2spacecorporation, anthropic, gillig, antora, weee |
| **Lever** | Postings JSON API | 25 | **zoox**, veeva, aeratechnology, velo3d, **penumbrainc**, **ambirobotics**, **orcabiosystems**, gopuff, thrivecausemetics |
| **Ashby** | Posting JSON API | 28 | openai, snowflake, **1x**, **mach**, **gritt**, **northwoodspace**, **crusoe**, plasmidsaurus, midjourney, nubank, tandempv, xona-space, hadrian-automation |
| **SmartRecruiters** | Postings JSON API | 8 | **WesternDigital**, AbbVie, MattelInc, **Intuitive**, **TheWonderfulCompany**, RRDonnelley, Sandisk, aristanetworks |
| **OracleCloud** | Recruiting REST API | 10 | fortinet, honeywell, oracle, albertsons, saic, cedarssinai, ichor, williamssonoma, cohu, dpworld |
| **SuccessFactors** | CSB tile-search HTML | 3 | sap, supermicro, pge |
| **iCIMS** | legacy fragment HTML | 9 | **ait**, nikkiso, snapon, yusen, triplessteel, dole |
| **Paylocity** | Recruiting JSON API | 2 | oneill, baycitiescontainer |
| **BambooHR** | hosted careers list | 1 | pivotalsys |

> **SuccessFactors note:** SF has no public JSON API (OData is per-tenant OAuth-gated). This adapter
> scrapes the Career Site Builder `tile-search-results` HTML — tenant-HTML, not a uniform API. It's
> validated on `jobs.sap.com` (SAP's CA roles are dev/enterprise-software, so it's really a validation
> tenant) and now also runs against `jobs.supermicro.com` and `careers.pge.com`. Each high-value SF
> target must be reverse-engineered per host — many aren't reachable at their obvious hosts (non-CSB,
> JS-loaded, or migrated ATS), so onboarding is one tenant at a time.

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

### Bespoke / other-ATS scrapers (14 targets)

Beyond the 10 config-driven ATS platforms, a set of per-target scrapers cover big-tech careers APIs
and a few one-off ATS fragments — each searches supply-chain terms (multi-query, de-duplicated),
narrowed to CA where the site allows and enforced by the California pre-filter. Status below is from a
live poll cycle (2026-09-11); "raw → CA-SCM" is scraped count → CA supply-chain-relevant after filters.

| Scraper | Target(s) | Method | Status (2026-09-11 poll) |
|---------|-----------|--------|--------------------------|
| **Amazon** | amazon | Jobs search JSON API | ✅ 403 raw → 6 CA-SCM |
| **Apple** | apple | Playwright (hydration JSON) | ✅ 216 raw → 3 CA-SCM |
| **Microsoft** | microsoft | PCSX search JSON API | ✅ 26 raw (no description → title+Gemini only) |
| **Tesla** | tesla | Bright Data Web Unlocker | ✅ **8115 raw → 68 CA-SCM** — Akamai-gated, so routed through the Web Unlocker (`job.tesla.*`, needs `BRIGHTDATA_TOKEN`); returns 0 gracefully if the token is unset |
| **Google** | google | Playwright | ✅ 120 raw → 3 CA-SCM |
| **ByteDance** | bytedance, tiktok | Jobs JSON API (7 queries) | ✅ 1039 raw → 19 CA-SCM (all via tiktok) |
| **Eightfold** | qualcomm, lamresearch | Eightfold API (7 queries) | ✅ 644 raw → 2 CA-SCM |
| **Jibe** | rivian, amd | Jibe careers API | ✅ 1967 raw → 0 CA-SCM (scrapes clean; no match this cycle) |
| **PaloAltoNetworks** | paloaltonetworks | Phenom SSR | ✅ 68 raw |
| **RossStores** | rossstores | whole-board recency scan | ✅ 197 raw → 1 CA-SCM |
| **Meta** | meta | careers JSON API | ⚠️ **0 raw** — datacenter-IP block; needs the `job.proxy.*` outbound proxy to yield |
| **BrassRing** | *(none configured)* | Kenexa BrassRing fragment | ⚪ inactive — scraper present, 0 companies wired; also proxy-gated |

Meta and BrassRing hit datacenter-IP blocks (400/429) and only yield through the optional `job.proxy.*`
outbound proxy. Everything else runs on a direct connection.

### Aggregator source (Adzuna) — the long-tail net

The direct scrapers cover ~273 known employers with full metadata and 15-min freshness. **Adzuna**
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

### Discovery source (JSearch) — finding new employers to migrate

Adzuna surfaces long-tail roles but excludes anyone already directly scraped, so it can't tell you
*which new employer to add next*. **JSearch** (`service/discovery/`, Google-for-Jobs via RapidAPI —
aggregates Indeed/LinkedIn/etc.) fills that gap as a pure **discovery** feed, not a poll source:

- **`JSearchDiscoveryService`** runs weekly (`jsearch.discovery.cron`, default Sun 06:00), fires a set
  of `{SCM title} × California` queries (`jsearch.discovery.queries`, `;`-delimited) via
  **`JSearchClient`** (`/search-v2` endpoint), aggregates by employer, filters out staffing agencies
  and any company already covered by a direct scraper *or* Adzuna, and ranks the net-new employers by
  SCM-role count — a ready-made migration shortlist.
- On-demand: `curl -X POST http://localhost:8081/api/discovery/jsearch`.
- Needs `RAPIDAPI_KEY`; disabled (returns empty) if unset. It **never persists jobs or emails** —
  output is a report you act on via the `migrate-companies` workflow.
- **Caveat:** the aggregator samples ~one posting per employer, so JSearch is good at *presence*
  (who's hiring SCM) but not *volume* — confirm "how heavy" by counting a candidate's own ATS board
  before wiring up a scraper.

### ATS-native discovery — the core enrichment engine

Aggregators (Adzuna/JSearch) give you an employer *name*, then leave you to reverse-engineer which ATS
they run. **Searching the ATS directly is strictly better for this pipeline** — every hit already
carries the ATS token, so it's a company + platform + slug ready to wire in. Three skills form the
repeatable "find company → verify ATS → scrape" loop, and this is how the roster grew from ~126 to
~260 employers:

| Skill | What it does | When |
|-------|--------------|------|
| **`discover-ats-dork`** | `site:{ats-domain} {SCM} California` search per platform → extract token from each result URL → dedupe vs config. Fast, sampled. | Quick "who else hires CA SCM", or ATS that can't be censused (Workday/iCIMS — the dork URL carries tenant+site). |
| **`discover-ats-census`** | Exhaustive, free "technographic list": enumerate *every* company on a path-ATS, probe each board's public API, rank net-new by CA-SCM count. | Deep sweeps of Greenhouse/Lever/Ashby. `scripts/discover-ats-census.sh {ats} --source all`. |
| **`migrate-companies`** | Verify a candidate's real ATS (`scripts/ats-detect.sh`), wire it into config, test-poll, diff pre/post notifiable. | Turning any discovered candidate into a live scraper. |

**How the free census works** (the no-cost equivalent of BuiltWith/TheirStack): path-based ATS
(`boards.greenhouse.io/{token}`) are enumerated from **Common Crawl** + **Wayback CDX** (Internet
Archive), unioned and deduped; each board's public jobs API is then probed and filtered to CA-SCM from
the live job data — so no paid firmographic list is needed. Wayback is fully paginated (via `resumeKey`)
and is the workhorse; CC's monthly indexes union in extra coverage when its rate-limit permits.

**Known limits** (documented so they're not re-hit): (1) **Subdomain-based ATS — Workday, iCIMS —
can't be censused for free**: tenants are masked by wildcard TLS certs (invisible to Certificate
Transparency) and the crawl indexes have too many URLs per tenant to page fully; use `discover-ats-dork`
for these. (2) Both crawl indexes rate-limit heavy same-day use — the census self-throttles and skips a
blocked source gracefully. (3) Yield is *presence, not volume*, and includes closed reqs + hourly/senior
roles the pipeline drops — always re-verify a candidate's live board before wiring (the skills do this).

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
   scripts/app.sh start      # builds the jar, sources .env, launches detached (polling on)
   scripts/app.sh status     # running? + last poll / email lines
   scripts/app.sh restart    # rebuild + relaunch (applies latest code/config)
   scripts/app.sh logs       # tail the live log
   scripts/app.sh stop
   ```

   `app.sh` launches from a **stable jar copy** in `run/` (not `target/`) so a later `mvn package`
   can't corrupt the running JVM's jar mid-flight (which otherwise breaks Jakarta Mail's lazy
   `ServiceLoader` on every send). `./start.sh` (`mvn spring-boot:run`) still works for foreground dev.

Runs on **port 8081** so it can run alongside `swe-job-notifier` (port 8080). Its H2 database lives in
this project's own `./data/` directory, independent of the SWE app's.

### Manual / debug endpoints

The scheduled poll runs every 15 min, but you can trigger work on demand (handlers run off the
WebFlux event loop):

```bash
curl -X POST http://localhost:8081/api/test/scrape/greenhouse/spacex   # one company
curl -X POST http://localhost:8081/api/test/scrape-all                 # every company (counts)
curl -X POST http://localhost:8081/api/test/poll                       # one full poll cycle
curl -X POST http://localhost:8081/api/discovery/jsearch               # JSearch discovery report
curl -X POST 'http://localhost:8081/api/replay/classification'         # re-classify past jobs (dry-run by default)
```

`POST /api/replay/classification` (`ClassificationReplayService`) re-runs the classifier over already
-stored postings and re-queues any that materially upgrade (e.g. a job that was auto-approved UNSURE
during a Gemini outage and should have been ENTRY_LEVEL) so a miss-sent batch can be recovered without
a re-scrape. Defaults to `dryRun=true` — pass `dryRun=false` (plus optional `sources`, `since`,
`until`, `limit`) to actually re-queue for the next alert scan.

## Scheduled Jobs

| Job | Property | Schedule | Description |
|-----|----------|----------|-------------|
| **Poll** | `job.poll.cron` | every 15 min | scrape → filter → classify → persist |
| **Alert scan** | `job.notification.scan.cron` | every 5 min | email unnotified ENTRY/INTERN/UNSURE jobs |
| **Daily summary** | `job.summary.cron` | 08:00 | digest of the last 24 h |
| **Cleanup** | `job.cleanup.cron` | 03:00 | delete jobs older than `job.retention.days` (90) |
| **JSearch discovery** | `jsearch.discovery.cron` | Sun 06:00 | net-new-employer report (no persist/email) |

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
│   ├── ScrapeTestController.java           # /api/test/{scrape,scrape-all,poll}
│   ├── DiscoveryController.java            # POST /api/discovery/jsearch
│   └── ClassificationReplayController.java # POST /api/replay/classification
├── model/
│   └── JobPosting.java                     # JPA entity (level = track; classificationSource = origin)
├── notification/
│   └── EmailNotifier.java                  # single-email builder + region (Area) bucketing
├── repository/
│   └── JobPostingRepository.java           # Spring Data JPA (notifiable queries)
├── scraper/
│   ├── JobScraper.java                     # interface (two-phase scrape/fetchDescriptions)
│   │   # config-driven ATS platforms (10):
│   ├── GreenhouseScraper.java  LeverScraper.java  AshbyScraper.java  SmartRecruitersScraper.java
│   ├── WorkdayScraper.java  OracleCloudScraper.java  SuccessFactorsScraper.java  IcimsScraper.java
│   ├── PaylocityScraper.java  BambooHrScraper.java
│   │   # aggregator + single-company / bespoke:
│   ├── AdzunaScraper.java                  # aggregator (long-tail net)
│   ├── AmazonScraper.java  MicrosoftScraper.java  AppleScraper.java  TeslaScraper.java
│   ├── GoogleScraper.java  MetaScraper.java  ByteDanceScraper.java  PaloAltoNetworksScraper.java
│   ├── RossStoresScraper.java  BrassRingScraper.java  EightfoldScraper.java  JibeScraper.java
├── service/
│   ├── JobPollingService.java              # 15-min orchestrator (12-thread pool)
│   ├── NotificationService.java            # 5-min single-email scan
│   ├── DailySummaryService.java            # 8 AM digest
│   ├── JobCleanupService.java              # 90-day retention cleanup
│   ├── ClassificationReplayService.java    # re-classify + re-queue past miss-sent jobs
│   ├── PipelineMetrics.java                # Micrometer counters/gauges
│   ├── discovery/
│   │   ├── JSearchClient.java              # RapidAPI /search-v2 client
│   │   └── JSearchDiscoveryService.java    # weekly net-new-employer discovery report
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
| `jsearch.api.key` | `${RAPIDAPI_KEY:}` | JSearch discovery (off/empty if unset) |
| `jsearch.discovery.cron` / `jsearch.discovery.queries` | `0 0 6 * * SUN` / 10 `{SCM title} × CA` phrases | discovery schedule + `;`-delimited query list |

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
