---
name: discover-ats-dork
description: Discover net-new SCM-hiring companies by searching ATS domains directly (site:boards.greenhouse.io / jobs.lever.co / jobs.ashbyhq.com / myworkdayjobs.com / icims.com "supply chain" California). Unlike JSearch/Adzuna, each hit's URL already contains the ATS token, so it yields company + platform + slug ready to wire in. Use when the user says "find new companies to scrape", "discover SCM employers", "who else hires supply chain in CA", or wants migration candidates.
---

# discover-ats-dork — find companies already on a scrapable ATS

The pipeline is **find company → verify ATS → wire up scraper**. JSearch/Adzuna give an employer
*name* and leave you to reverse-engineer the ATS. Searching the ATS domain directly is strictly
better: `site:{ats-domain} {SCM terms} California` returns URLs whose path already holds the ATS
token, so every hit is **company + platform + slug in one step**. It complements JSearch (which still
catches employers on ATSs you can't dork, e.g. Oracle/custom sites).

## Workday/iCIMS: the dork is THE effective method (2026-09-17 finding)

Subdomain-based ATS (Workday `{tenant}.wdN.myworkdayjobs.com`, iCIMS) **cannot be enumerated for
free**, and the dork is the effective discovery method — this was established by experiment:

| Enumeration attempt | Result |
|---|---|
| Certificate Transparency (crt.sh/certspotter) | ✗ wildcard-masked — `*.wdN.myworkdayjobs.com`, 0 tenants |
| Common Crawl / Wayback CDX | ✗ SURT `com,myworkdayjobs,wdN,{tenant})`; millions of URLs/instance, `collapse` doesn't reduce to one-row-per-tenant, columnar index needs Athena/DuckDB+TB scan |
| Passive DNS (RapidDNS free) | ✗ 100-capped, and unfiltered → ~0 CA-SCM hit rate |
| GitHub tenant lists | △ real but SWE-internship-skewed (159 tenants → 1 CA-SCM add) |
| JSearch apply-links | ✗ aggregator redirects (LinkedIn/ZipRecruiter), no tenant exposed |
| Paid (TheirStack/BuiltWith/Apify) | △ complete but last-mile resolution still attrition-heavy, poor ROI |

**Why the dork wins:** the CA-SCM Workday *supply* is thin and big-national-skewed, and we only want
the *hiring* subset — so enumerate-all-then-filter is strictly worse than dorking the CA-SCM-hiring
tenants directly. The dork returns exactly that subset, with tenant+instance+site in the URL (no
resolution step). A single broad query undersamples, so run the **niche sweep** — each SCM
sub-discipline surfaces different tenants:

```
scripts/discover-ats-dork.sh workday-queries              # ~12 niche queries for myworkdayjobs.com
scripts/discover-ats-dork.sh workday-queries icims.com    # same for iCIMS
```

Run all ~12 in WebSearch (`allowed_domains=["myworkdayjobs.com"]`), `parse` the URLs, then CXS-probe
the net-new tenants for CA-SCM (drop Canada/Mexico/Serbia false-`CA` matches — check for `", CA"`/
`California`, not a bare `ca`/`CA` token). Validated finds via this method: guardanthealth,
interiorlogicgroup, analogdevices, agilent, + AeroVironment (that one via the GitHub list).

## Procedure (path-ATS or single-platform)

1. **Get the queries:**
   ```
   scripts/discover-ats-dork.sh queries
   ```
   Prints one `site:` dork per platform (Greenhouse, Lever, Ashby, Workday, iCIMS, SmartRecruiters).

2. **Run each dork in WebSearch** with `allowed_domains=["<that domain>"]` (that flag is what scopes
   the search to the ATS). Paste all result URLs into a scratch file, one per line.

3. **Extract slugs + dedupe against config:**
   ```
   scripts/discover-ats-dork.sh parse <urls-file>
   ```
   Prints `platform  slug  {NET-NEW ⭐ | already-configured}` by substring-matching
   `application.properties`. The NET-NEW rows are your candidates.

4. **Verify each NET-NEW has real professional CA-SCM roles — do NOT wire in blindly.** A dork hit
   only means the company posted *a* CA-SCM req at some point; the board may now have zero, or only
   roles the pipeline drops. Check the live board:
   - Greenhouse: `boards-api.greenhouse.io/v1/boards/{slug}/jobs`
   - Lever: `api.lever.co/v0/postings/{slug}?mode=json`
   - Ashby: `api.ashbyhq.com/posting-api/job-board/{slug}`
   - Workday: `POST {tenant}.wdN.myworkdayjobs.com/wday/cxs/{tenant}/{site}/jobs` (find the site id
     from the URL path or `sitemap.xml`; see `migrate-companies`)
   - iCIMS: `{subdomain}.icims.com/jobs/search?searchKeyword=supply%20chain&in_iframe=1`

   Count roles that are **SCM-titled AND CA AND not hourly-labor/senior-only**. Reject boards whose
   only SCM roles are `Warehouse Associate / Fulfillment / Driver` (the filter drops them) — a common
   trap: SunSource had 272 jobs but all-hourly SCM; GOAT Group's were Fulfillment/Inventory Associate.

5. **Wire the good ones into `application.properties`** (see `migrate-companies` for exact per-ATS
   format). The ATS token is right there in the URL:
   - Greenhouse/Lever/Ashby → append slug to the `job.companies.<ats>` CSV.
   - Workday → `job.workday.companies[N]` block: `subdomain` = the URL's leftmost label,
     `instance` = the `wdN` number, `site` = the first path segment after the host.
   - iCIMS → `job.icims.companies[N]` block: `subdomain` = the URL's leftmost label (e.g. `jobs-getty`).
   Add a dated comment noting the discovery source, and keep indices contiguous.

6. **Test-poll and restart** per `test-poll` / `app` skills; commit config only when the user asks.

## Notes & platform quirks

- **SmartRecruiters is noisy** — its dork is ~70% staffing agencies (Collabera, BCforward, Scalian,
  Burnett Search). Down-weight it; prefer Greenhouse/Lever/Ashby which are almost all real employers.
- **Yield is presence, not volume** — same caveat as JSearch. The dork proves a company posted CA-SCM
  once; confirm ongoing volume by counting the board (step 4), and add credible SCM-org companies with
  big boards as growth bets even at 0-today (e.g. hardware/space/biotech).
- The `2026-09-13` pass added 13: natera, planetlabs (GH); loftorbital, hawaiianhost, kepler (Lever);
  marianaminerals, helion (Ashby); guardanthealth, interiorlogicgroup (Workday); getty, missionlinen,
  iehp (iCIMS). Cross-check `validate-job-links` after they first yield, to confirm the emailed URLs resolve.
