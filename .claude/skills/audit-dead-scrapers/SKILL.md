---
name: audit-dead-scrapers
description: Find scrapers that have silently stopped working — companies erroring on every poll, or returning 0 scraped without an error — then diagnose whether the ATS migrated, the host moved, or the Workday site was renamed, and fix the config. Use when the user says "are any scrapers broken", "silent failures", "why so few jobs", "audit the scrapers", "is anything dead", or after a long run without a config review.
---

# audit-dead-scrapers — find scrapers that died quietly

`POLL CYCLE COMPLETE companies=355, scraped=~95000` looks healthy even when individual
employers have been returning **nothing for weeks**. On 2026-09-28 three companies had failed
**242/242 consecutive polls** (5+ days) with no visible signal: MACH (Ashby→Greenhouse
migration), SAP (host moved), Nextracker (Workday site renamed). Two failure classes hide here:

- **Loud-but-buried** — the scraper throws, logged per company, invisible in the summary.
- **Silent** — the scraper returns `0 scraped` with no error at all. Nextracker was in this
  state for 9 days *before* it started erroring. This is the one nothing else catches.

## 1. Find the failures

Needs a **completed** cycle in `app.log` (`app.sh` truncates the log on every start, so after a
restart wait for the next `POLL CYCLE COMPLETE`; a cycle takes ~20 min).

```bash
# a) erroring companies, grouped — anything at ~1 per cycle is permanently dead
grep -oE "Failed to scrape [A-Za-z]+ for company: [a-zA-Z0-9_-]+" app.log \
  | sed -E 's/Failed to scrape ([A-Za-z]+).*: (.*)/\1 \2/' | sort | uniq -c | sort -rn

# b) SILENT failures — returned zero jobs without erroring
grep "scraped →" app.log | grep -E "— 0 scraped" \
  | sed -E 's/.*\[([a-z]+)\] ([a-zA-Z0-9_.-]+) — 0 scraped.*/\1  \2/' | sort -u

# c) sanity: every configured company should appear
grep -c "scraped →" app.log
```

Cross-check the underlying HTTP status — it drives the diagnosis in §2:

```bash
grep -A 2 "Failed to scrape .* company: <name>" app.log \
  | grep -oE "[0-9]{3} [A-Za-z ]+|UnknownHost[A-Za-z]*|ConnectTimeout[A-Za-z]*" | sort | uniq -c
```

**A company with 0 rows in H2 is NOT evidence of breakage** — plenty scrape fine and simply have
no entry-level CA SCM roles (SAP: 291 scraped → 2-5 California → **0 relevant**, every time).
Judge on `scraped`, not on job count.

## 2. Diagnose by status code

| Symptom | Almost always means | Fix |
|---|---|---|
| **Workday 403** `errorCode S22 "permission denied"` | **Site renamed/decommissioned** — tenant is fine | §3 |
| Ashby/Greenhouse/Lever **404** on the token | Employer **migrated ATS** | `scripts/ats-detect.sh "Name" slug1,slug2` → rewire |
| **403 on every path incl. the homepage** | **Host moved** (SAP: jobs.sap.com → careers.sap.com) | re-detect, swap `host` |
| 403 only on the API, homepage fine | genuine bot/IP block | proxy (`job.proxy.*`) or drop |
| Timeouts/`WebClientRequestException` in bulk across MANY companies | **not the scrapers** — host TCP/port exhaustion | see [[poll-frequency]]; check `netstat -an -p tcp \| grep -c TIME_WAIT` |

## 3. The Workday 403 trap (worth its own section)

Workday answers a **decommissioned site on a live tenant** with `403 permission denied`, *not*
404, and `GET /{site}` 302s to `community.workday.com/maintenance-page`. Every probe therefore
reads as an IP block — it is not, and **a proxy will not help**. Tells it is a rename:

```bash
# tenant still exists on that pod? 404 = yes, 422 = no such tenant
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  "https://{sub}.wd{N}.myworkdayjobs.com/wday/cxs/{sub}/ZZNOSITE/jobs" \
  -H 'Content-Type: application/json' --data '{"limit":1,"offset":0,"appliedFacets":{},"searchText":""}'
# other tenants on the same pod still 200? (rules out a pod outage)
# a cookie handshake (GET board, POST with cookies) still 403s? (rules out session)
```

**The fix is a web search, not more probing.** Search `<Company> "myworkdayjobs.com" apply` — a
live posting URL carries the real site id:
`nextracker.wd5.myworkdayjobs.com/en-US/`**`nextpower_careers`**`/job/...` revealed
`nextracker_careers` → `nextpower_careers` (225 jobs), dead 5+ days. Probing alone had led to
"it's gone, drop it", which was wrong.

## 4. Verify before wiring, and record why

Confirm the new coordinates return jobs **and** what they yield for CA-SCM before editing config —
a fix that restores a scraper yielding 0 relevant roles is still worth making (correctness), but
say so rather than implying new volume. Then in `application.properties`, comment **what broke,
how it presented, and the evidence**, e.g. "403 on every request incl. homepage → host moved",
so the next reader doesn't re-litigate it.

Apply with a rebuild + restart (`scripts/app.sh restart`) — config is baked into the jar. Prefer
the gap between cycles; a restart mid-poll abandons that cycle (harmless, results persist per
company, but it wastes ~20 min).

## Notes & guardrails

- Commit config fixes only when the user asks.
- Restarting sends real email for anything newly notifiable — a revived board's entire backlog is
  unseen, so expect a batch (MACH = 132 CA jobs on revival). Warn first. See
  [[no-auto-run-app-or-polls]].
- Don't judge a scraper by H2 rows (§1). Don't reach for a proxy on a Workday 403 (§3).
