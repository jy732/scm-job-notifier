---
name: validate-job-links
description: Validate that persisted job-posting URLs actually resolve to the right job page — catches broken URL patterns per company (a scraper building the wrong link), including soft-404s that return HTTP 200 with a "Page Not Found" body. Use when the user says a company's email links 404, "check the job links", "are the URLs valid", or after adding/changing a scraper's URL format.
---

# validate-job-links — catch broken job-URL patterns

A wrong URL is a **silent** bug: the scraper succeeds, jobs get emailed, and nothing errors —
only a human clicking the link finds the 404. Worse, career sites usually **soft-404**: they
return HTTP 200 with a "Page Not Found" body, so a status-only check passes. This skill fetches
each URL and **title-matches** it against the job, so a broken pattern shows up as a company whose
links are all DEAD (the Ross Stores `/job/{ref}` → `/search/jobdetails/{slug}/{uuid}` bug).

## Run it

Reads the DB over `AUTO_SERVER`, so it works **while the app is running** (read-only, no lock):

```
scripts/validate-job-links.sh                    # 5 newest links per company, all companies
scripts/validate-job-links.sh rossstores         # one company
scripts/validate-job-links.sh "rossstores|meta"  # regex filter (H2 REGEXP_LIKE)
scripts/validate-job-links.sh rossstores 10      # + sample size per company
```

## Read the output

Per-company table of `OK / DEAD / UNKNOWN`, then a detail list of every DEAD link. Verdicts:

- **OK** — reachable and the page `<title>`/`<h1>` contains the job's title tokens. The link works.
- **DEAD** — HTTP 404/410, **or** a soft-404 (HTTP 200 whose title/body says "not found"). This is
  the bug signal. A company showing **`⛔ ALL DEAD`** almost certainly has a wrong URL pattern in
  its scraper — go fix `toJobPosting`'s URL construction.
- **UNKNOWN** — reachable (HTTP 200) but unverifiable. Two honest causes, **not** failures:
  - **JS-SPA job pages** (Workday, Ashby, iCIMS, …) render the title client-side, so a plain
    `curl` sees only the app shell with an empty `<title>`. Can't confirm via fetch.
  - **Anti-bot blocks** (Tesla/Meta 403/429 datacenter IPs) — the fetch is blocked, link may be fine.

**Key limitation:** the validator is authoritative for **server-rendered** career sites
(Greenhouse, Lever, Ross, most `.com/careers` job pages → OK/DEAD are reliable) but can only say
UNKNOWN for **JS-SPA** ATS (Workday et al.). A SPA that soft-404s with an *empty* title is a blind
spot — verify those by hand (open one link) or with a headless-browser pass. Don't read a wall of
UNKNOWN as broken; read **DEAD** (especially ALL DEAD) as broken.

## When a company is ALL DEAD — fix the pattern

1. Get the real URL from an authoritative source (the site's `sitemap.xml` lists canonical job
   URLs; or inspect how the search page/API links to a job).
2. Compare against the scraper's `JOB_URL` format and which record field it uses (the Ross bug used
   `ReferenceNumber` in `/job/{ref}`; the real format is `/search/jobdetails/{title-slug}/{ID-uuid}`).
3. Fix `toJobPosting`, update the scraper's test URL assertion, run `./mvnw -q verify` (100% line
   coverage gate must stay green).
4. Backfill existing DB rows so replays/summaries use working links: build the correct URL from each
   row's stored `title` + `external_id` and `UPDATE job_posting SET url=… WHERE external_id=…`
   (stop the app or use the `AUTO_SERVER` URL).

## Notes

- Exit code is non-zero if any company is entirely DEAD — usable as a CI/cron guard later.
- Pure shell + Python (`scripts/validate_job_links.py`); no Java, so it doesn't touch the coverage gate.
- It only checks URLs already persisted — a company with 0 recent rows simply won't appear.
