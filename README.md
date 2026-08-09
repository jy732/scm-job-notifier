# SCM Job Notifier

Monitors company career sites for **entry-level and internship Supply Chain Management** postings,
classifies them, and sends email notifications.

Structural sibling of [`swe-job-notifier`](../swe-job-notifier) — same Spring Boot 4 / Java 17 /
H2 stack and the same package layout. See [`docs/technical-design.html`](docs/technical-design.html)
for the full design (classification tracks, California filter, employer/ATS list, decisions D1–D4).

## Status

**Implemented and building.** Full pipeline runs end-to-end against live career sites: scrape →
freshness → exclude → California → SCM-relevance → dedup → 3-stage classify → persist → single-email
alert.

| Piece | State |
|---|---|
| `pom.xml`, Maven wrapper, config, logging | done |
| Model, repository, config-driven ATS scrapers (Greenhouse, Lever, Ashby, SmartRecruiters, Workday, OracleCloud) | done |
| Classification pipeline (title → description → Gemini), single-email notifier, poll/summary/cleanup services, metrics | done |
| 38 CA SCM employers configured with ATS mappings | done |
| Playwright / bespoke single-company scrapers (Amazon, Apple, Google, …) | **deferred** — need per-scraper SCM query rework |
| A few Workday `wd{N}`/`site` params (Seagate, Bio-Rad, Chevron, and others returning 0 jobs) | **verify** |

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
├── config/                  # @ConfigurationProperties, WebClient, Playwright beans
├── controller/              # test/debug endpoints
├── model/                   # JobPosting JPA entity
├── notification/            # email sending
├── repository/              # Spring Data JPA repositories
├── scraper/                 # one class per ATS platform + JobScraper interface
├── service/                 # polling, daily summary, cleanup, metrics
│   └── classification/      # title filter → signal extraction → LLM classify
└── util/                    # shared helpers
```

## Intended pipeline

Mirrors the SWE app, with the classification target changed from SWE level (L3/L4) to
SCM **entry-level vs. internship**:

```
scrape → dedup (company + externalId) → exclude filter → US location filter
       → SCM-relevance title filter → signal extraction → LLM classify → notify
```

## Configuration

Company lists in `application.properties` are empty placeholders — populate per ATS platform
(`job.companies.greenhouse`, `.lever`, `.ashby`, `.smartrecruiters`, plus the indexed Workday /
OracleCloud / iCIMS blocks) once target employers are chosen.

Cron schedules carried over from the SWE app: poll every 15 min, notification scan every 5 min,
daily summary at 8:00 AM, retention cleanup at 3:00 AM (90-day retention).

## Notes

- The SMTP socket timeouts in `application.properties` are deliberate — without them JavaMail
  defaults to infinite and a dropped TCP connection wedges the scheduler thread.
- Run `mvn spotless:apply` after editing Java (AOSP style, google-java-format 1.22.0).
- Stop the app before running Maven — a running instance holds the H2 file lock.
