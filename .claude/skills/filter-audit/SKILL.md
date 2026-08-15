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
   location, title`). Sequential scrape → **~15 min**:
   ```
   curl -s -m 900 -X POST http://localhost:8081/api/test/filter-audit > filter-audit-result.json
   ```

3. **Analyze:**
   ```
   python3 scripts/analyze-filter-audit.py
   ```
   It prints the disposition distribution and 5 checks:
   1. **Leakage** — `PASSED` jobs that look non-SCM/labor/senior. *SCM engineering roles (SQE,
      Supplier Development Engineer, Sourcing/Supply Chain Engineer) are legit — kept on purpose.*
   2. **Location miss** — `DROPPED_NON_CA` with a `, CA`/`California` token → **should be ~0**;
      any hits are a location-parse bug.
   3. **Keyword gap** — `DROPPED_NON_SCM` whose title looks SCM → missing `SCM_KEYWORDS`.
   4. **Labor over-reach** — labor-excluded titles with a professional word → filter too aggressive.
   5. **Seniority false-drop** — seniority-excluded with an entry marker (mostly `Assistant
      Manager` = still management = correct; scan for genuine entry roles).

## Part B — Gemini audit (from real classifications in H2, no re-classification cost)

Stop the app (releases the H2 lock), then query the persisted `title → level` decisions:
```
H2=$(find ~/.m2 -name 'h2-*.jar' | grep -v sources | head -1)
Q() { java -cp "$H2" org.h2.tools.Shell -url "jdbc:h2:file:$(pwd)/data/jobs" -user sa -sql "$1"; }
```
- **Over-drop** (Gemini rejected good entry SCM): `OTHER`-level rows whose title looks entry-SCM
  (analyst/planner/buyer/coordinator/specialist/procurement, no senior marker). Expect mostly
  level-numbered (Analyst IV, Planner 3 = correctly senior) and HR false-positives (Talent Sourcing
  Specialist, Recruiter = correctly OTHER). Investigate bare titles that shouldn't be OTHER.
- **Leakage** (Gemini passed bad ones): notifiable rows (`ENTRY_LEVEL/INTERNSHIP/UNSURE`) that are
  actually senior/labor/non-SCM.

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
