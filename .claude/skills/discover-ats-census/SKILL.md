---
name: discover-ats-census
description: Exhaustive, FREE company discovery — enumerate (nearly) every company on an ATS and rank the net-new ones by California-SCM role count. The no-cost version of BuiltWith/TheirStack "companies using Greenhouse" lists, using Common Crawl (path-based ATS) + crt.sh certificate transparency (subdomain-based ATS). Use when the user wants the FULL universe of SCM employers on an ATS, "all companies on Greenhouse", "exhaustive discovery", or more candidates than the sampled dork/JSearch methods give.
---

# discover-ats-census — the free "technographic list" approach

`discover-ats-dork` samples whatever a search engine indexed; this enumerates the **whole** ATS.
Paid tools (BuiltWith/TheirStack) just resell the same two free data sources and add firmographic
filters — and we filter straight from live job data, so we don't need theirs.

## Enumeration source depends on ATS URL structure

| ATS | URL shape | Free enumeration |
|-----|-----------|------------------|
| Greenhouse / Lever / Ashby / SmartRecruiters | company in the **path** (`boards.greenhouse.io/{tok}`) | **Common Crawl** index |
| Workday / iCIMS / SuccessFactors | company is a **subdomain** (`{tenant}.myworkdayjobs.com`) | **crt.sh** certificate transparency |

- Common Crawl: `IDX=$(curl -s https://index.commoncrawl.org/collinfo.json | jq -r '.[0]["cdx-api"]')`
  then `curl "$IDX?url=boards.greenhouse.io/*&output=json&fl=url&limit=20000&page=N"` — extract the
  token from each URL. Pages beyond what CC has for the domain return HTTP 400 (normal; stop there).
- crt.sh: `curl "https://crt.sh/?q=%25.myworkdayjobs.com&output=json"` → `.[].name_value`; keep the
  leftmost label, drop infra subs (`api`, `admin`, `analytics`) and keep customer patterns
  (iCIMS `careers-*`/`jobs-*`).

## Run it (Greenhouse implemented)

```
scripts/discover-ats-census.sh greenhouse                    # full sweep (~10-20 min)
scripts/discover-ats-census.sh greenhouse --limit-probe 300  # quick sample
scripts/discover-ats-census.sh lever --source all --workers 12
scripts/discover-ats-census.sh smartrecruiters --source all  # staffing-filtered (SR-only)
```

Supports the four **path-based** ATS: `greenhouse`, `lever`, `ashby`, `smartrecruiters`. SmartRecruiters
carries a built-in `staff_filter` (SR-only) that drops staffing-agency tokens before probing — SR is
staffing-dominated, so without it the results are ~70% Cynet/Collabera-type noise. Even filtered, SR is
a **thin/low-signal** vein for CA-SCM (a 5k-board sweep yielded ~3 clean employers); Greenhouse is the
rich one. Subdomain ATS (Workday/iCIMS) still can't be censused (see limits below) — use the dork.

Pipeline: enumerate tokens (CC) → dedupe vs `application.properties` → probe each net-new board's
`boards-api.greenhouse.io/v1/boards/{tok}/jobs` (threaded) → keep roles that are SCM-title AND
CA-location AND not hourly/senior → rank by CA-SCM count. **Run it OFFLINE, not inline with the poll**
(thousands of rate-limited probes). Expect ~25% of enumerated boards to be dead/renamed.

## Read the output & wire winners

Ranked `CA-SCM | TOTAL | token | sample role`. **Always re-verify a candidate before wiring** — the
pre-screen regex is looser than the real `JobTitleFilter` and has two known false-positive classes:

- **Ambiguous city names** — `Dublin`, `Ontario`, `Chino` match both a CA city and a foreign
  place; a hit's sample location may read "Dublin, Ireland". Anchor on `", CA"`/`California` when
  verifying, or check the full board.
- **Non-SCM "buyer" / title collisions** — `Media Buyer` (ads), `Counsel, Supply Chain` (legal),
  `Software Engineer, ... Logistics` (SWE). And hourly `Fulfillment/Warehouse/Inventory Control`.

Verify with the per-board API (list the actual CA-SCM titles), then wire the good ones into the
`job.companies.<ats>` CSV with a dated comment — see `migrate-companies` for format, then `test-poll`.

## Extending to other ATS

- Lever/Ashby/SmartRecruiters: same Common Crawl / Wayback enumeration, swap the domain + the
  per-board API in `discover_ats_census.py` (add an `enum_*`/`probe_*` pair; the file is built for it).
- **Workday/iCIMS can NOT be censused for free** — verified 2026-09-13. Subdomain-based ATS hide
  tenants behind wildcard TLS certs (`*.wdN.myworkdayjobs.com`), so Certificate Transparency
  (crt.sh/certspotter) only returns the wildcard + instance hosts, never `boeing.wd1`. And
  Common Crawl / Wayback order by URL alphabetically with thousands of pages per tenant, so full
  enumeration means paging millions of rows. Use **`discover-ats-dork`** for these instead — the
  dork URL already contains tenant+instance+site. (A broad Workday/iCIMS dork pass on 2026-09-13
  surfaced only big national cos — Catalent/Leidos/FMC/GD-OTS — with ~0 current CA-SCM; the good
  ones, guardanthealth/interiorlogicgroup/getty/missionlinen/iehp, came from the earlier dork pass.)

## Notes

- The 2026-09-13 Greenhouse census (1,782 enumerated → 1,716 net-new → 27 CA-SCM) added 16:
  trueanomalyinc, epirus, oklo, valaratomics, generalmatter, archer56, astranis, gatikaiinc,
  neuralink, pacificfusion, peakenergy, salientmotion, senrasystems, databricks, verkada, zscaler.
- Coverage is bounded by what Common Crawl indexed for that domain (~1.8k GH boards here); pulling
  more CC monthly indexes or `&showNumPages=true` deepens it toward the full universe.
- Cross-check `validate-job-links` after new adds first yield, to confirm emailed URLs resolve.
