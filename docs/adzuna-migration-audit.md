# Adzuna → ATS Migration Audit

Running record of which Adzuna long-tail employers have been evaluated for migration to a
direct ATS scraper, so future passes don't re-investigate the same names. Adzuna is a keyword
"long-tail net": a hit is only dropped once its employer matches a direct scraper's exclude token
(built from the configured company names in `application.properties`). Migrating an employer =
adding it to the relevant `job.<ats>.companies` list, which both scrapes it directly **and**
excludes it from the Adzuna source (avoids cross-source dupes).

Supported ATS scrapers: **Greenhouse, Lever, Ashby, SmartRecruiters, Workday, Oracle Cloud (CE),
iCIMS (legacy search fragment), SuccessFactors, Paylocity.**

---

## Pass 2026-08-15 — long-tail discovery (238 distinct un-migrated Adzuna employers)

Reviewed the multi-job un-migrated employers (≥2 fresh notifiable Adzuna postings). Findings:

### ✅ Newly migrated this pass
| Employer | ATS | Config | Notes |
|---|---|---|---|
| **AltaMed** | Workday | `altamed` / wd1 / `Careers` | LA/OC FQHC healthcare. CXS verified; thin but real SCM (procurement ~6, inventory ~25). |
| **Ryder** | Workday | `ryder` / wd5 / `RyderCareers` | 3PL/logistics giant, many CA sites. **Strong** CA SCM — logistics ~16, warehouse ~11, supply chain ~13, inventory ~5 (Customer Logistics Mgr/Supervisor, Inventory Mgr). |
| **HD Supply** | Workday | `hdsupply` / wd1 / `External` | Industrial/MRO distribution, CA DCs (City of Industry). 464 total; Supply Chain Analyst / Implementation Specialist atop labor the pipeline drops. |
| **Meta** | Bespoke GraphQL | `MetaScraper` (ported from swe-job-notifier, then fixed) | Menlo Park / Sunnyvale / Fremont CA offices. New `@Component` scraper, SCM free-text queries, Adzuna-excluded. **Verified live: 63 jobs / 44 CA** (Supply Chain Capacity Engineer, Supply Chain Enterprise PM, Supply Planner–Reality Labs …). See the fix note below. |

These came from a second sweep of **single-Adzuna-hit but large-footprint** employers (value = the
employer's whole board, not its current Adzuna count).

#### Meta scraper fix — the `datr` cookie (root cause of the ~2026-06-06 stall)
Meta's careers scraper (here and in swe-job-notifier) silently stopped returning jobs around
**2026-06-06** — logs showed a repeating `could not extract LSD token, skipping`. Root cause: since
early June, metacareers.com / facebook.com return a generic **400 "Sorry, something went wrong"**
error page (no LSD token) to any careers request that lacks a **`datr` cookie**. `fetchLsdToken`
received that 400 page (non-null HTML), the LSD regex didn't match, and the run aborted — so it was
never a stale `doc_id` or a changed token format. Fix: **(1)** bootstrap a `datr` cookie from
`m.facebook.com`, **(2)** thread the accumulated cookie jar through the job-search GET and the
GraphQL POST, and **(3)** read the response body regardless of status (Meta serves the real page
under a `429` soft rate-limit, so `.retrieve()` — which throws on non-2xx — must not be used).
Verified after the fix: **63 jobs / 44 CA**. The `doc_id` (`29615178951461218`) and LSD regex were
unchanged.

### ✔ Already migrated (verified still-configured — do NOT re-investigate)
These surfaced as "un-migrated" only because of **stale pre-migration Adzuna rows** in the DB; they
are already in the config and excluded from the Adzuna source.

| Employer | ATS | Token / tenant |
|---|---|---|
| Ōura | Greenhouse | `oura` |
| Xona Space Systems | Ashby | `xona-space` |
| Universal Music Group | Workday | `umusic` / wd5 / `UMGUS` |
| Huntsman | Workday | `huntsman` / wd1 / `huntsman` |
| Rosendin | Workday | `rosendin` / wd1 / `Careers` |
| Solar Turbines | Workday | `cat` / wd5 / `SolarTurbines` (Caterpillar tenant) |
| PCI Pharma Services | Workday | `pciservices` / wd1 / `External` |
| Voyager Technologies | Greenhouse | `voyagertechnologiesinc` |
| Sharp Electronics | Greenhouse | `sharpelectronics` |
| Smartsheet, Stripe, Wing | Greenhouse | `smartsheet`, `stripe`, `wing` |
| Nordic Naturals, Varda Space | Greenhouse | `nordicnaturals`, `vardaspace` |
| Midjourney, Nubank, Tandem PV | Ashby | `midjourney`, `nubank`, `tandempv` |
| Sandisk, RR Donnelley | SmartRecruiters | `Sandisk`, `RRDonnelley` |

### ✕ On UNSUPPORTED ATS (skip until a scraper exists)
| Employer | ATS | Why skipped |
|---|---|---|
| Obagi Cosmeceuticals | JazzHR (`applytojob.com`) | No JazzHR scraper. |
| PacSun | UltiPro / UKG Pro | No UKG scraper. iCIMS legacy fragment 404s; `onboarding-pacsun.icims.com` is post-offer onboarding only. |
| Setna iO | Indeed (+ Paylocity for one subsidiary) | Main board is Indeed (not scrapeable); Paylocity only covers subsidiary LGT. |
| Schneider Electric | SAP SuccessFactors (custom `careers.se.com`) | Huge global tenant, high dedupe/noise risk. |
| Simplehuman | Undetermined (JS careers page) | ATS not detectable; has a Supply Planner role — revisit with a DevTools lookup. |
| STIIIZY | Undetermined | ATS not detectable. |
| Reyes Holdings | iCIMS (new UI) | `careers-reyesholdings` / `careers-reyesbeveragegroup` return 200 but **0 job cards** on the legacy `/jobs/search` fragment our scraper reads — API-gated new iCIMS UI. Food/bev distribution, would be high-value; revisit if we add a modern-iCIMS reader. |
| Penske Truck Leasing | Custom (`penske.jobs`) | Not a supported ATS; ~1,600 CA-inclusive SCM roles behind a custom Phenom-style portal. |
| C&S Wholesale, Keurig Dr Pepper, Grocery Outlet, Imperial Dade, Parts Authority, CommonSpirit, Dignity Health, Harbor Freight | Not on a guessable Workday tenant (422) | Use SuccessFactors / Phenom / modern-iCIMS. Not reachable with current scrapers. |

### ⚠ False leads
- **Williams-Sonoma** — NOT on `williams.wd5.myworkdayjobs.com`. That tenant is **Williams
  Companies** (Tulsa energy — HQ "OK Tulsa"). WSM itself is on SuccessFactors/NAS (already noted
  as dropped in `application.properties`). Confirmed skip.

### Staffing agencies / recruiters / IT body-shops — never migrate (~48 of ~230)
They aggregate other companies' reqs; migrating them re-imports the noise we filter. Examples:
Kelly Services, VOLT, System One, RGP, Harvey Nash, US Tech Solutions, Collabera, Gpac,
22nd Century Technologies, Cynet Systems, Apidel, Lancesoft, DGN Technologies, Trident Consulting,
SBT Global, V R Della Infotech, Software Guidance & Assistance, ATR International, Crystal Equation,
Pinnacle Technical Resources, Signature Consultants, Kalon Executive Search, CornerStone
Professional Placement, The Select Group, NextDeavor, Katalyst Healthcares, Hospitality Hiring Hub.

### IP-block mitigation — proxy support + Playwright hardening (2026-08-15)
Meta Careers and BrassRing return a 400/429 error page to this project's scrapers. Root cause is
**dynamic anti-bot + the CI sandbox's TLS interception**, not a code bug or a static IP block:

- The **sandbox MITMs HTTPS**; Meta/BrassRing reject the intercepted TLS outright (400). A *direct*
  connection (curl with the sandbox disabled) reaches the real page — same egress IP either way, so
  it isn't an IP reputation issue per se.
- Meta additionally applies **escalating rate-limiting**: a fresh direct call returned 63 jobs /
  44 CA, but rapid automated retries got the same IP throttled (429 → 400). The scrapers are
  therefore *correct* (Meta proven end-to-end); the environment just can't sustain access.

The production fix is a proxy (rotating/residential distributes load and avoids rate-limit
escalation) + the app's normal polite cadence (one poll / 15 min, not rapid-fire testing).
Two-part mitigation shipped:

1. **Configurable outbound proxy** (`ProxyProperties`, prefix `job.proxy`) — routes the Meta
   WebClient (reactor-netty `ProxyProvider`) *and* all Playwright scrapers (`BrowserType.LaunchOptions.setProxy`)
   through a residential/rotating HTTP proxy. Disabled by default; enable via `.env`:
   `SCRAPER_PROXY_ENABLED=true`, `SCRAPER_PROXY_HOST/PORT` (+ `USERNAME/PASSWORD`). **This is the
   actual fix** — point it at a clean IP and Meta/BrassRing return jobs.
2. **Playwright anti-automation hardening** — launch with `--disable-blink-features=AutomationControlled`,
   context `locale=en-US` + `timezoneId=America/Los_Angeles`, and an init script hiding
   `navigator.webdriver`. Helps against behavioral checks; insufficient on its own against a pure IP block.

Until a proxy is configured (or the app runs from an unblocked IP), Meta + the 3 BrassRing scrapers
return 0 — an environment limitation, not a scraper bug (Meta was validated at 63 jobs / 44 CA via
`curl` with a fresh `datr` cookie before this IP got throttled).

### BrassRing — DISABLED: public search is login-gated (2026-08-19)
Ran the scraper from a genuinely un-intercepted network (macOS Terminal.app on the dev machine) and
proved the earlier 0s there were **not** the IP block — the real General Atomics board loads fine.
The blocker is that GA's BrassRing **public (not-logged-in) search doesn't expose an automatable
keyword search**:
- The home **"Search" button** runs `searchMatchedJobs` → the `MatchedJobs` endpoint, a résumé/profile
  match that returns `JobsCount:0` without login and **ignores the typed keyword** (confirmed: the
  keyword was verified present in the input, still 0).
- The **Advanced/power keyword search** (`powerSearchJobs` / `#powerSearchKeyWord`) is `ng-hide`den and
  **won't render even when force-clicked** — effectively disabled for public users.
- Navigating straight to the results-hash URL (`#keyWordSearch=…`) **redirects back to `#home`**.

So reliable BrassRing scraping would require a real **login** (credentials + résumé, fragile, ToS) —
not worth it for 3 companies. **Resolution:** companies commented out in `application.properties`
(scraper kept as reference, disabled). With them empty, `AdzunaScraper` no longer excludes them, so
**General Atomics / Harbor Freight / Lockheed Martin return to the Adzuna long-tail** (how they
originally surfaced). The Playwright thread-safety fix (serialize the shared browser across
Apple/Tesla/BrassRing) and the CA-location fix stay — both are independently valuable.

### Tesla — SOLVED via Bright Data Web Unlocker (2026-08-20)
Tesla's whole board is one JSON call — `GET /cua-api/apps/careers/state` returns ~7.8k listings plus
a `lookup` dict (locations/types/departments) — but it's guarded by **Akamai Bot Manager AND an
app-level `cpr_chlge` challenge**. Proven end-to-end that nothing direct/Playwright gets in: bare
`curl`, headless Chromium, and **headful real Google Chrome on the user's own residential IP** all
returned `Access Denied`. The Akamai `_abck`/`bm_*` cookies are only minted by a genuine
sensor-validated browser and carry a ~57-min validity window, so borrowed cookies aren't a
production answer. Endpoint + schema were reverse-engineered from a DevTools cURL capture (listing
row `{id, t=title, l=locationId, y=typeId, dp=deptId}`; `lookup.locations[l]` → `"City, State"`,
`lookup.types` → fulltime/intern).

Fix: `TeslaScraper` fetches the state JSON through **Bright Data Web Unlocker** (`POST
api.brightdata.com/request`, zone `scm_unlocker`, `format:raw`), which mints valid cookies and
returns the board; `TeslaStateParser` resolves the codes into `JobPosting`s with canonical Tesla ids
(dupe-clean). The unlocker intermittently returns the `cpr_chlge` stub instead of the board (rotating
IP hasn't solved it; a real board is ~1.5 MB), so the scraper **retries, spaced**, until the body
contains `listings`, and **throttles to hourly** to stay within the free 5,000-credit/month tier.
Validated live: **7,817 listings → 2,101 CA → 176 CA-SCM**. Token/zone come from `.env`
(`BRIGHTDATA_TOKEN`/`BRIGHTDATA_ZONE`), disabled unless set. Note the Web Unlocker fixes
**anti-bot/IP/JS-challenge** blocks only — it does **not** fix BrassRing (login wall) and is
unnecessary for the open-API scrapers (Workday/Greenhouse/etc.). Meta (IP/rate-limit block) is the
next candidate for the same treatment.

### ByteDance — added via open careers API (2026-08-21)
ByteDance runs its own recruiting platform (not a supported ATS), but its careers site fetches from a
**fully open** JSON endpoint: `POST jobs.bytedance.com/api/v1/search/job/posts?keyword=&limit=&offset=`
— no auth, no bot wall, returns `{"code":0,"data":{"count":N,"job_post_list":[…]}}`. New
`ByteDanceScraper` runs the SCM free-text queries, paginates by `offset` to `count`, and unions by id;
location comes from each row's `city_list[].en_name` (ByteDance) / `city_info.en_name` (TikTok) —
global boards, so the CA pre-filter enforces California (US SCM hubs: San Jose, LA, Fontana).
`ByteDanceScraper` covers **two brands**:
- **bytedance** — `POST jobs.bytedance.com/api/v1/search/job/posts` — live ~**477 SCM → 32 CA**.
- **tiktok** — `POST api.lifeattiktok.com/api/v1/public/supplier/search/job/posts` with a
  **`website-path: tiktok`** header (the brand selector; without it the call 405s) — live ~**506 SCM
  → 117 CA** (Fontana/San Jose/LA TikTok Shop supply-chain roles, incl. 2027 new-grad).

Added `bytedance`/`tiktok`/`douyin` to the Adzuna exclude tokens (dupe hygiene).

### Bay-Area large-employer sweep (2026-08-22)
Systematic hunt for big Bay SCM employers we were missing (the ByteDance/TikTok lesson). Key
finding: "large company" ≠ "large SCM" — most big tech on easy ATSes (Roku, Block, Arista, PayPal,
Visa, eBay) is tech-heavy with ~0 supply-chain. The real misses are **physical-ops** employers.
Added (all validated via test poll unless noted):

| Employer | ATS / how | Live CA-SCM |
|---|---|---|
| **Flex/Flextronics** | Workday `flextronics`/wd1/`Careers` | 1085 → 29 CA → 3 SCM |
| **Logitech** | Workday `logitech`/wd5/`logitech` | thin (1 CA) but real CA HQ |
| **Google** | Playwright DOM scrape of `google.com/about/careers/applications/jobs/results` (ported from swe-job-notifier; no public JSON API — v3 404s) | 136 → 25 CA (huge board; SCM mostly senior) |
| **Supermicro** | SuccessFactors `jobs.supermicro.com` (CSB tile-search) | 370 → 42 CA → **9 notifiable** |
| **Lam Research** | Eightfold **pcsx** API `careers.lamresearch.com/api/pcsx/search` (no auth) | 393 → **71 CA** (Livermore/Fremont) |

Excluded google/lamresearch from the Adzuna long-tail (bytedance/tiktok/douyin already; supermicro
auto-excludes via its SF company name).

**Deferred (need work):** Albertsons/Safeway (Oracle CE `eofd`/us6/`CX_1001` verified, but our Oracle
scraper fetches the whole board — needs keyword-filtering added for grocery volume); Ross Stores
(`jobs.rossstores.com/Search/SearchResults` JSON works but is session/anti-bot gated — needs a
cookie bootstrap); Palo Alto Networks (Phenom **SSR** — no jobs XHR, only 18 SCM results, thin).

### Takeaway
The migratable pool is **nearly exhausted**. Of ~230 distinct un-migrated employers: ~48 are
staffing/recruiters (skip), a large block are already-migrated stale rows, a handful are on
unsupported ATSes, and the rest are genuine one-off long-tail employers (avg ~1.4 postings each,
mostly with no queryable ATS). Net-new additions this pass: **AltaMed, Ryder, HD Supply** (all
Workday). The biggest remaining prizes (Reyes Holdings, Penske) are on ATSes we can't yet read —
future gains require new scraper support (**modern iCIMS UI, UKG Pro, JazzHR, SuccessFactors/Phenom
at scale**) rather than more config.
