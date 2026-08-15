#!/usr/bin/env python3
"""Analyze filter-audit.csv (from POST /api/test/filter-audit) for two-directional filter errors.

For every scraped job the audit records the outcome at each pre-filter stage (fresh, excludeReason,
california, scmRelevant, autoLevel) and a final `disposition` (which stage dropped it, or PASSED →
would reach Gemini/email). This script mines that both ways: leakage (bad jobs passing) and
over-filtering (good jobs silently dropped), per stage.

Usage: python3 scripts/analyze-filter-audit.py [path/to/filter-audit.csv]
"""
import csv
import collections
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "filter-audit.csv"
rows = [x for x in csv.DictReader(open(path, newline="")) if x.get("title")]
print(f"total jobs: {len(rows)}\n")

disp = collections.Counter(x["disposition"] for x in rows)
print("=== DISPOSITION (where each job dropped, or PASSED) ===")
for d, c in disp.most_common():
    print(f"  {d:28} {c}")

# 1) LEAKAGE — PASSED jobs that look non-SCM / labor / senior. NOTE: SCM engineering roles
#    (Supplier Quality/Development Engineer, Sourcing/Supply Chain Engineer) are LEGIT — kept on
#    purpose via the SCM-anchor guard — so treat "…Engineer" hits as expected, not leakage.
passed = [x for x in rows if x["disposition"] == "PASSED"]
bad = re.compile(
    r"(?i)\b(nurse|clinical|physician|patient|marketing|\bsales\b|recruit|financial|"
    r"account(ant|ing)|teacher|cook|chef|barista|cashier|security officer|attorney|counsel|"
    r"social media)\b|warehouse associate|material handler|forklift|stocker|"
    r"order (selector|picker)|\bclerk\b|\bmanager\b|\bdirector\b|\bsenior\b|\bsr\b"
)
leak = [x for x in passed if bad.search(x["title"])]
print(f"\n=== 1) LEAKAGE — {len(leak)} of {len(passed)} PASSED look non-SCM/labor/senior ===")
for x in leak[:40]:
    print(f"  ! {x['company']} | {x['title']} @ {x['location']}")

# 2) LOCATION MISS — dropped as non-CA despite a CA token (the parse-bug class; want ~0)
ca = re.compile(r"(?i),\s*ca\b|\bcalifornia\b")
miss = [x for x in rows if x["disposition"] == "DROPPED_NON_CA" and ca.search(x.get("location") or "")]
print(f"\n=== 2) LOCATION MISS — {len(miss)} DROPPED_NON_CA with a CA token (should be ~0) ===")
for x in miss[:25]:
    print(f"  ! {x['company']} | {x['location']!r} | {x['title'][:50]}")

# 3) KEYWORD GAP — CA jobs dropped for no SCM keyword whose title still looks SCM
scmish = re.compile(
    r"(?i)supply|logistic|procure|sourc|purchas|\bbuyer\b|inventory|warehouse|freight|customs|"
    r"fulfil|shipp|planner|schedul|materials|vendor|import|export|3pl|distribution|replenish"
)
gap = [x for x in rows if x["disposition"] == "DROPPED_NON_SCM" and scmish.search(x["title"])]
print(f"\n=== 3) KEYWORD GAP — {len(gap)} DROPPED_NON_SCM but title looks SCM (missing SCM_KEYWORDS?) ===")
for t, c in collections.Counter(x["title"] for x in gap).most_common(30):
    print(f"  {c:3}  {t[:60]}")

# 4) LABOR/NON-SCM-ROLE OVER-REACH — excluded by the labor tier but contain a professional word
prof = re.compile(
    r"(?i)\banalyst\b|\bplanner\b|\bbuyer\b|coordinator|specialist|procurement|\bsourcing\b|"
    r"demand|category"
)
over = [x for x in rows if x["disposition"] == "DROPPED_NON_SCM_ROLE" and prof.search(x["title"])]
print(f"\n=== 4) LABOR OVER-REACH — {len(over)} labor-excluded contain a professional word (review) ===")
for t, c in collections.Counter(x["title"] for x in over).most_common(20):
    print(f"  {c:3}  {t[:60]}")

# 5) SENIORITY FALSE-DROP — seniority-excluded that also carry an entry marker (mostly
#    "Assistant Manager" = still management = correct; scan for genuine entry roles wrongly caught)
entry = re.compile(r"(?i)\bintern\b|\bentry\b|new grad|\bassociate\b|coordinator|assistant")
sfp = [x for x in rows if x["disposition"] == "DROPPED_SENIORITY" and entry.search(x["title"])]
print(f"\n=== 5) SENIORITY FALSE-DROP? — {len(sfp)} seniority-excluded with an entry marker ===")
for t, c in collections.Counter(x["title"] for x in sfp).most_common(15):
    print(f"  {c:3}  {t[:60]}")
