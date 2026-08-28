---
name: filter-audit
description: Run the end-to-end filter QA audit — dump every scraped job's outcome at each pre-filter stage + Gemini's real classifications, then analyze both directions (leakage AND over-filtering) at every stage. Use when the user says things like "audit the filter", "check the filter", "is anything getting wrongly dropped/passed", or after changing SCM_KEYWORDS / exclude lists.
---

# filter-audit — end-to-end filter QA

Audits every spec of the classification funnel for **both** error directions:
- **Leakage** — bad jobs (non-SCM / labor / senior / non-CA) reaching email.
- **Over-filtering** — good entry-CA-SCM jobs silently dropped (the location-parse / keyword-gap bug class — see [[scraper-silent-location-drops]]).

## Part A — pre-filter audit (deterministic, no Gemini cost)

1. **Get the app running with email suppressed.** If it isn't already up, start a suppressed
   instance (never-fires crons, so no alerts) — same overrides `test-poll.sh` uses:
   ```
   nohup java -jar target/scm-job-notifier-0.0.1-SNAPSHOT.jar \
     --job.notification.scan.cron="0 0 5 31 12 *" --job.summary.cron="0 0 5 31 12 *" \
     --job.poll.cron="0 0 5 31 12 *" --job.adzuna.throttle-minutes=999999 > audit.log 2>&1 &
   ```
   (Rebuild first with `./mvnw -q -DskipTests package` if code changed.)

2. **Trigger the audit** — scrapes every company and writes `filter-audit.csv` (one row per job:
   `platform, company, disposition, excludeReason, fresh, california, scmRelevant, autoLevel,
   location, title`). Full sweep is sequential → **~15 min**:
   ```
   curl -s -m 900 -X POST http://localhost:8081/api/test/filter-audit > filter-audit-result.json
   ```
   **Scope it** to audit an arbitrary set of companies in seconds (e.g. after adding scrapers).
   Both `?platform=` and `?company=` are case-insensitive and accept a **comma-separated list**;
   pass whatever companies you want to audit — not just the newest adds:
   ```
   curl -s -X POST 'http://localhost:8081/api/test/filter-audit?platform=jibe'                       # whole platform(s)
   curl -s -X POST 'http://localhost:8081/api/test/filter-audit?company=amd,rivian,appliedmaterials' # any cos, any platform
   curl -s -X POST 'http://localhost:8081/api/test/filter-audit?platform=workday&company=intel,nxp'   # intersect
   ```
   The JSON response echoes `scope`, `companiesAudited`, `byDisposition` (incl. `PASSED` = would
   reach Gemini), and `unmatchedCompanies` (requested names that hit no scraper — a typo). Each call
   overwrites `filter-audit.csv` with just the scoped rows — pass the file directly to the analyzer;
   no need to copy it aside since one scoped call already contains the whole set you asked for.

3. **Analyze:**
   ```
   python3 scripts/analyze-filter-audit.py [path/to/filter-audit.csv]
   ```
   It prints the disposition distribution, a **per-company funnel table** (one row per company with
   the count at each stage in pipeline order — `tot → stale → lead → senr → tech → role → nonCA →
   nonSCM → PASS`; stages sum to `tot`, sorted by `PASS` desc), then 5 checks:
   1. **Leakage** — `PASSED` jobs that look non-SCM/labor/senior. *SCM engineering roles (SQE,
      Supplier Development Engineer, Sourcing/Supply Chain Engineer) are legit — kept on purpose.*
   2. **Location miss** — `DROPPED_NON_CA` with a `, CA`/`California` token → **should be ~0**;
      any hits are a location-parse bug.
   3. **Keyword gap** — `DROPPED_NON_SCM` whose title looks SCM → missing `SCM_KEYWORDS`.
   4. **Labor over-reach** — labor-excluded titles with a professional word → filter too aggressive.
   5. **Seniority false-drop** — seniority-excluded with an entry marker (mostly `Assistant
      Manager` = still management = correct; scan for genuine entry roles).

## Part B — Gemini audit (from real classifications in H2, no re-classification cost)

Mines Gemini's already-persisted `title → level` decisions — the direction that catches leaks/drops
that happen *at classification* (e.g. a title that passes the pre-filter but Gemini mis-levels).
No app-stop needed: `AUTO_SERVER=TRUE` allows a concurrent read-only connection while the app runs.

1. **Export the decisions to CSV** (H2 shell, read-only):
   ```
   H2=$(find ~/.m2 -name 'h2-*.jar' | grep -v sources | head -1)
   java -cp "$H2" org.h2.tools.Shell -url "jdbc:h2:file:$(pwd)/data/jobs;AUTO_SERVER=TRUE;IFEXISTS=TRUE" \
     -user sa -password "" -sql \
     "CALL CSVWRITE('$(pwd)/gemini-audit.csv', 'SELECT title, company, location, level, notified, source FROM job_posting');"
   ```
2. **Analyze:** `python3 scripts/analyze-gemini-audit.py` — prints level counts + two checks:
   - **OVER-DROP** (false-negatives): `OTHER`-level titles that look entry-SCM, minus the correct-
     OTHER noise (level-numbered II/III/IV, HR "talent sourcing", medical "procurement surgeon",
     engineering/driver/consultant tracks). The remainder is heuristic — **check the description's
     YOE before acting** (bare "Specialist/Planner/Analyst" are often legitimately mid-level; e.g. a
     "Procurement Specialist" requiring 8 yrs is correctly OTHER).
   - **LEAKAGE** (false-positives): emailed (`notified=TRUE`) titles that look senior/hourly-labor/
     shift and shouldn't have shipped (breaks out the shift-labor subset).

## Interpreting / acting
- Location-miss > 0 → a scraper location bug ([[scraper-silent-location-drops]]).
- Keyword-gap clusters → add the missing term to `SCM_KEYWORDS` (keep it specific — e.g. add
  "master schedul"/"production schedul", not bare "scheduler", to avoid surgery/project schedulers).
- Over-reach / false-drop → tighten `NON_SCM_ROLE_KEYWORDS` / exclude lists.
- Re-run after any filter change to confirm.

## Notes
- The audit endpoint sends no email; still prefer a suppressed instance so it doesn't compete with
  the live poll. Leaves the app running — stop it for Part B, and restart via `/app` only on the
  user's OK (real emails). Avoid frequent restarts.
- `filter-audit.csv` / `filter-audit-result.json` / `audit.log` are gitignored.
