#!/usr/bin/env python3
"""Part B (Gemini) audit — analyze persisted title->level decisions for both error directions.

Unlike analyze-filter-audit.py (which audits the deterministic PRE-filter from a live scrape), this
mines Gemini's REAL, already-persisted decisions in H2 — no re-classification cost. Export the CSV
from the H2 shell first (works while the app is running, via AUTO_SERVER; read-only):

  CALL CSVWRITE('gemini-audit.csv',
      'SELECT title, company, location, level, notified, source FROM job_posting');

Two checks:
  - OVER-DROP (false negatives): OTHER-level titles that look entry-SCM but weren't emailed. Filters
    out the usual correct-OTHER noise (level-numbered II/III/IV, HR "talent sourcing", medical
    "procurement surgeon", real-estate "buyer"). Eyeball the rest and check the description's YOE
    before acting — bare "Specialist/Planner/Analyst" are often legitimately mid-level.
  - LEAKAGE (false positives): emailed (notified) titles that look senior / hourly-labor / shift and
    should not have shipped.

Usage: python3 scripts/analyze-gemini-audit.py [gemini-audit.csv]
"""
import csv
import collections
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "gemini-audit.csv"
rows = [{k.lower(): (v or "") for k, v in r.items()} for r in csv.DictReader(open(path, newline=""))]
by_level = collections.Counter(r["level"] for r in rows)
emailed = sum(1 for r in rows if r["notified"].upper() == "TRUE")
print(f"persisted decisions: {len(rows)} | by level: {dict(by_level)} | emailed: {emailed}\n")

entry_scm = re.compile(
    r"(?i)\b(analyst|coordinator|planner|buyer|procurement|sourcing|specialist|inventory|"
    r"logistics|supply chain|purchasing|materials|demand)\b"
)
# markers that make an OTHER call correct (so we DON'T flag them as over-drops)
level_num = re.compile(r"(?i)\b(ii|iii|iv|v)\b|\b[2-9]\b")  # "Analyst IV", "Planner 3" = senior
senior = re.compile(
    r"(?i)\b(senior|sr\.?|staff|principal|manager|\bmgr\b|director|lead|vp|head|chief|supervisor|president)\b"
)
hr = re.compile(r"(?i)talent|candidate|\brecruit|people ops")  # "Talent Sourcing" = HR, not SCM
medical = re.compile(r"(?i)surgeon|organ|clinical|\bnurse|donor")  # "Procurement Surgeon" = organ
realestate = re.compile(r"(?i)real estate|realtor|escrow|mortgage")
# other tracks that are legitimately OTHER (not the entry analyst/planner/buyer track we hunt):
# drivers/warehouse labor, engineering/architect/dev, consultants, technicians, ops managers.
other_track = re.compile(
    r"(?i)\b(driver|engineer|architect|developer|operator|consultant|technician|steward|recovery)\b"
)

over = [
    r
    for r in rows
    if r["level"] == "OTHER"
    and entry_scm.search(r["title"])
    and not (
        senior.search(r["title"])
        or level_num.search(r["title"])
        or hr.search(r["title"])
        or medical.search(r["title"])
        or realestate.search(r["title"])
        or other_track.search(r["title"])
    )
]
print(f"=== OVER-DROP — {len(over)} OTHER titles look entry-SCM w/ no correct-OTHER marker (check YOE) ===")
for r in over[:40]:
    print(f"  ? {r['company']} | {r['title']} @ {r['location']}")

# LEAKAGE: emailed rows that look senior / hourly-labor / shift
labor = re.compile(
    r"(?i)\b(senior|\bsr\b|staff|principal|manager|director|supervisor)\b"
    r"|warehouse associate|material handler|forklift|stocker|\bclerk\b"
)
shift = re.compile(
    r"(?i)\b(1st|2nd|3rd|4th|first|second|third|fourth|day|night|evening|weekend|swing|graveyard|"
    r"overnight|morning|multiple|am|pm)\s+shift(s)?\b|\bshift\s+differential\b"
)
leak = [
    r
    for r in rows
    if r["notified"].upper() == "TRUE" and (labor.search(r["title"]) or shift.search(r["title"]))
]
shift_leak = [r for r in leak if shift.search(r["title"])]
print(f"\n=== LEAKAGE — {len(leak)} EMAILED titles look senior/labor/shift (should not have shipped) ===")
print(f"  (of which shift-labor: {len(shift_leak)} — now blocked by EXCLUDE_SHIFT_PATTERN going forward)")
for r in leak[:30]:
    tag = "shift" if shift.search(r["title"]) else "senior/labor"
    print(f"  ! [{tag:11}] {r['title'][:60]} @ {r['location']}")
