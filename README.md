# SCM Job Notifier

Monitors company career sites for **entry-level and internship Supply Chain Management** postings,
classifies them, and sends email notifications.

Structural sibling of [`swe-job-notifier`](../swe-job-notifier) — same Spring Boot 4 / Java 17 /
H2 stack and the same package layout. See [`docs/technical-design.html`](docs/technical-design.html)
for the full design (classification tracks, California filter, employer/ATS list, decisions D1–D4).

## Status

**Implemented, building, and verified end-to-end.** A full poll cycle runs against live career sites
in ~150s with **zero scrape failures**: scrape → freshness → exclude → California → SCM-relevance →
dedup → 3-stage classify → persist → single-email alert.

| Piece | State |
|---|---|
| `pom.xml`, Maven wrapper, config, logging | done |
| Model, repository, config-driven ATS scrapers (Greenhouse, Lever, Ashby, SmartRecruiters, Workday, OracleCloud) | done |
| Classification pipeline (title → description → Gemini), single-email notifier, poll/summary/cleanup services, metrics | done |
| **41 CA companies across all 6 scrapers — every slug/param verified against the live ATS API** | done |
| Playwright / bespoke single-company scrapers (Amazon, Apple, Google, …) | **deferred** — need per-scraper SCM query rework |

All 6 scrapers and 41 companies were tested through the app and confirmed returning data. Three
candidates were **dropped** as unreachable via a supported ATS: `qualcomm` (Workday moved/auth-gated),
`seagate` (Workday on a custom domain), `bio-rad` (Phenom).

## Setup

```bash
cp .env.example .env   # then fill in credentials
./start.sh             # or: mvn spring-boot:run
```

Runs on **port 8081** so it can run alongside `swe-job-notifier`, which owns 8080.
Its H2 database lives in this project's own `./data/` directory, independent of the SWE app's.

## Package layout

```
com.github.jingyangyu.scmjobnotifier
├── config/                  # @ConfigurationProperties, WebClient bean
├── controller/              # test/debug endpoints
├── model/                   # JobPosting JPA entity
├── notification/            # email sending
├── repository/              # Spring Data JPA repositories
├── scraper/                 # one class per ATS platform + JobScraper interface
├── service/                 # polling, daily summary, cleanup, metrics
│   └── classification/      # title filter → signal extraction → LLM classify
└── util/                    # shared helpers
```

## Pipeline

Mirrors the SWE app, with the classification target changed from SWE level (L3/L4) to
SCM **entry-level / internship / unsure**:

```
scrape → freshness → exclude filter → California location filter → SCM-relevance title filter
       → dedup (company + externalId) → fetch descriptions → 3-stage classify → persist → notify
```

## Email alert

The 5-minute scan sends **one email** to `NOTIFICATION_EMAIL` listing every new entry-level /
internship / unsure posting in a single table, with a **Type** column (Decision D1). The daily
8 AM summary uses the same layout.

**Subject:** `[SCM Job Alert] 3 new CA SCM posting(s) detected`

**Body** — *New SCM Postings (California)*:

| Type | Company | Title | Location | Area | Link |
|------|---------|-------|----------|------|------|
| Entry-Level | Edwards Lifesciences | Supply Chain Analyst | Irvine, CA | Greater LA | Apply |
| Internship | Chevron | Supply Chain Intern – Summer 2026 | San Ramon, CA | SF Bay Area | Apply |
| Unsure | Illumina | Inventory Analyst | San Diego, CA | Other | Apply |

The **Area** column buckets each CA location into **SF Bay Area / Greater LA / Other** (San Diego,
Sacramento, Central Valley, remote → Other). `OTHER`-classified jobs are stored but never emailed;
remote roles display as `Remote (CA)`. See [`docs/technical-design.html`](docs/technical-design.html)
§7 for a rendered preview.

## Configuration

Company lists in `application.properties` are populated and verified: `job.companies.greenhouse`
(9), `.lever` (2), `.ashby` (2), `.smartrecruiters` (1), the indexed Workday block (26), and the
OracleCloud block (1) — 41 companies in all.

Cron schedules carried over from the SWE app: poll every 15 min, notification scan every 5 min,
daily summary at 8:00 AM, retention cleanup at 3:00 AM (90-day retention).

## Notes

- The SMTP socket timeouts in `application.properties` are deliberate — without them JavaMail
  defaults to infinite and a dropped TCP connection wedges the scheduler thread.
- Run `mvn spotless:apply` after editing Java (AOSP style, google-java-format 1.22.0).
- Stop the app before running Maven — a running instance holds the H2 file lock.
