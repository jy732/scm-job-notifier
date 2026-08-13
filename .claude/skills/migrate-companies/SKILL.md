---
name: migrate-companies
description: End-to-end playbook for migrating employers from the Adzuna aggregator to direct ATS scrapers — pull candidates from H2, verify each employer's real ATS, wire them into config, then test-poll and compare pre-vs-post notifiable stats. Use when the user says things like "migrate companies from adzuna", "find migratable companies", "verify the ATS for these employers", or "continue the sweep".
---

# migrate-companies — Adzuna → direct-scraper migration

Adzuna is a third-party aggregator (thin sample per employer, no cross-source dedup).
Migrating an employer onto its real ATS gives full-board coverage at the source and lets
Adzuna exclude it (no duplication). This is the repeatable procedure.

## 1. Pull candidates from H2

The app holds an exclusive lock on the H2 file, so **stop it first** (`bash scripts/app.sh stop`),
then query the Adzuna-sourced employers:

```
H2=$(find ~/.m2 -name 'h2-*.jar' | grep -v sources | head -1)
java -cp "$H2" org.h2.tools.Shell -url "jdbc:h2:file:$(pwd)/data/jobs" -user sa -sql \
"SELECT company, COUNT(*) c FROM JOB_POSTING WHERE source='adzuna' \
 AND level IN ('ENTRY_LEVEL','INTERNSHIP','UNSURE') GROUP BY company ORDER BY c DESC;"
```

Record each employer's **Adzuna notifiable count** — that's the *pre*-migration baseline.
Skip staffing agencies (Cynet, Collabera, Apidel, NextDeavor…), municipal/gov, and noise
("Warehouse", "Confidential"): they originate on ZipRecruiter/Indeed and have no ATS to migrate to.

## 2. Verify the real ATS (never slug-guess blindly)

```
bash scripts/ats-detect.sh "Employer Name" [slug1,slug2,...]
```

It probes every supported ATS with the reliable method and prints config-ready tokens. Read
its header for the false-positive traps it guards against — the important ones:

- **SmartRecruiters & iCIMS wildcard** — both answer for non-existent IDs; require
  `totalFound>0` / a large page with real job markers.
- **Workday** — the myworkdayjobs edge returns 406 for any host; a resolving host proves
  nothing. The detector uses the CXS trick (`POST /wday/cxs/{tenant}/ZZNOSITE/jobs` → `404`
  = tenant exists, `422` = not) and reads the real site id from `sitemap.xml`.
- **Greenhouse/Lever** dead boards return 200 with 0 jobs — require jobs>0.

If it finds nothing, the careers page is likely a **JS-SPA** (ATS loads via XHR) needing a
Playwright network-capture pass, or an unsupported ATS (Oracle/Avature/Phenom/Eightfold/
Brassring/ADP — the detector flags these as NOTE lines so you stop chasing them).

## 3. Wire into config (`src/main/resources/application.properties`)

- Greenhouse/Lever/Ashby → append the token to the CSV `job.companies.<ats>` line.
- SmartRecruiters → append the exact CamelCase id.
- Workday → new `job.workday.companies[N]` block (`name`, `subdomain`, `instance`=wdN, `site`).
- iCIMS → new `job.icims.companies[N]` block (`name`, `subdomain`).

**Dedup invariant (critical):** the AdzunaScraper builds its exclude set from these same
lists, matched against the Adzuna employer string (normalized, substring). So the config
`name`/token **must match the Adzuna display name** or Adzuna won't exclude it and you'll
get cross-source dups. Parent-branded Workday tenants are the trap: set `.name` to the
Adzuna employer (e.g. `name=airgas` even though `subdomain=airliquidehr`).

## 4. Test-poll and compare pre vs post

Run the safe poll (email suppressed) — see the `/test-poll` skill:

```
bash scripts/test-poll.sh "employer1|employer2|..."
```

Verify from its output:
- **Scraper init counts** rose by the number added; **Adzuna exclude tokens** rose by the
  same number.
- **Adzuna funnel**: `excluded` went up, migrated employers show `0 unseen` (no new Adzuna
  rows → no dups).
- **Per-company pipeline** for each migrated employer: `scraped → CA → SCM-relevant →
  notifiable`.

Then compute the **diff**: post-migration `notifiable` (direct, full board) vs the
pre-migration Adzuna count from step 1. Expect it to *rise* — direct scraping pulls the
whole board, not Adzuna's sample (e.g. RR Donnelley 1 → 5, Moog 1 → 3).

## Notes & guardrails

- Commit config + any template/skill changes only when the user asks.
- One-time transition effect: employers Adzuna already emailed get re-notified once under
  the new key, then never again. Not an ongoing dup.
- Leave the app **stopped** after (test-poll does this); restart via `/app start` only on
  the user's OK (real emails).
