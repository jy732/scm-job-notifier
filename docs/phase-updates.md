# Phase updates — sent announcement log

Human-readable record of the user-facing "phase update" emails sent via the `phase-update` skill.
The `last-phase-update` git tag marks the commit of the **most recent** entry below; the skill diffs
`git log last-phase-update..HEAD` to gather what's new, and force-moves the tag + appends here on send.

Newest first.

---

## 2026-08-31 — Reliability wave: email-delivery fix + backlog recovery + classification replay
- **Marker commit:** `8394e22`
- **Recipient:** jollyy1999@gmail.com
- **Gist:** (1) fixed the **email-delivery** bug — Jakarta Mail's lazy StreamProvider ServiceLoader
  broke when the jar was rebuilt under the running app, silently failing ~619 alert sends; now runs
  from a stable jar copy so alerts land reliably; (2) **re-sent the backlog** of alerts that had
  failed during that window; (3) **Gemini outage** noted briefly — it degraded gracefully (UNSURE
  fallback still emailed, no misses), and `.env` sourcing in the launcher prevents the key from
  silently dropping again; (4) fixed an NPE that could silently drop a company's jobs mid
  classify/persist; (5) new **classification replay** (`/api/replay/classification`) that re-checks
  and recovers mis-filtered ("OTHER") jobs — recovered 11 this round.

---

## 2026-08-27 — Company-expansion wave + shift-labor filter + missed-jobs keywords
- **Marker commit:** `ab53f11`
- **Recipient:** jollyy1999@gmail.com
- **Gist:** (1) big batch of new **official career-page** sources — semis (AMD/Intel/Qualcomm/Applied
  Materials/Micron/NXP/Lam), AI (Anthropic/OpenAI), retail/CPG (Williams-Sonoma/Ross/Albertsons),
  EV/space/defense (Rivian/K2 Space/Hadrian), and CA local mfrs (GILLIG/CelLink/Antora/Cohu/iHerb/
  Pivotal); (2) hourly **shift/night-shift** labor now filtered out; (3) previously-missed job types
  caught (**Purchaser**, **Shipping**).

---

## 2026-08-XX — Tesla + Workday multi-location fix (boundary seed)
- **Marker commit:** `210d512` (Tesla: scrape careers board via Bright Data Web Unlocker)
- **Recipient:** jollyy1999@gmail.com
- **Gist:** Tesla careers now scraped (via Bright Data Web Unlocker); Workday multi-location CA facet
  fix recovered jobs that were previously dropped.
- Note: this entry seeds the tracking boundary. Everything committed *after* `210d512` (the whole
  company-expansion wave: Jibe/AMD+Rivian, semis, K2/Hadrian/Anthropic, Williams-Sonoma/Cohu/iHerb,
  Bay-Area locals, BambooHR/Pivotal, the shift-labor filter fix, audit tooling) is **unannounced**.
