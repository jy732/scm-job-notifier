#!/usr/bin/env python3
"""Exhaustive, FREE ATS census — the "technographic list" approach without paid tools.

Enumerate (nearly) every company on an ATS, probe each board's public API, and rank the
net-new ones by California-SCM role count. Free data sources by ATS URL structure:

  path-based    greenhouse / lever / ashby      -> Common Crawl index (company is in the path)
  subdomain     workday / icims                 -> crt.sh cert transparency  [not yet wired here]

This pilot implements GREENHOUSE. The paid tools (BuiltWith/TheirStack) just resell the same
Common-Crawl / cert-transparency data plus firmographic filters — and we filter straight from the
live job data instead, so we don't need theirs.

Usage: discover_ats_census.py greenhouse [--pages N] [--workers N] [--limit-probe N]
"""
import argparse
import concurrent.futures as cf
import json
import re
import sys
import urllib.request
from collections import defaultdict

UA = {"User-Agent": "Mozilla/5.0 (scm-job-notifier ATS census)"}
CC_COLLINFO = "https://index.commoncrawl.org/collinfo.json"
PROPS = "src/main/resources/application.properties"

# Mirror JobTitleFilter intent (approximate — this is a discovery pre-screen, not the real pipeline).
SCM = re.compile(
    r"supply chain|procure|purchas|\bbuyer\b|sourcing|logistic|demand plan|supply plan|"
    r"production plan|\bplanner\b|inventory|warehouse|fulfil|s&op|commodity|supplier|"
    r"distribution|customs|freight|replenish|materials? (planner|control|analyst|coordinator|specialist|manage)",
    re.I,
)
CA = re.compile(
    r"\bCA\b|california|san francisco|san jose|los angeles|santa clara|fremont|sunnyvale|oakland|"
    r"irvine|carlsbad|san diego|milpitas|berkeley|pomona|torrance|redwood|palo alto|bay area|"
    r"hercules|livermore|santa barbara|el segundo|long beach|pasadena|mountain view|alameda|"
    r"hawthorne|goleta|camarillo|fullerton|santa monica|culver|burbank|glendale|anaheim|chino|"
    r"ontario|riverside|sacramento|emeryville|menlo|foster city|south san francisco|san mateo|"
    r"cupertino|campbell|hayward|pleasanton|dublin|walnut creek|san ramon|petaluma|napa|vacaville",
    re.I,
)
# Hourly-labor + senior titles the real pipeline drops — exclude so we don't over-count yield.
DROP = re.compile(
    r"\b(senior|sr\.?|staff|principal|manager|director|vp|head of|chief|supervisor|president|lead)\b"
    r"|warehouse associate|material handler|order (selector|picker)|forklift|stocker|"
    r"freight handler|\bdriver\b|\bclerk\b|associate\b.*warehouse"
    r"|media buyer|ad buyer|time buyer|home buyer|house buyer|space buyer",  # non-SCM "buyer"
    re.I,
)
STAFF = re.compile(
    r"staffing|recruit|talent|consult|collabera|bcforward|aston carter|robert half|randstad|"
    r"adecco|aerotek|actalent|kelly services|cynet|insight global|apex systems|teksystems",
    re.I,
)


def urlopen(url, timeout=15):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout)


def configured_tokens():
    """Tokens already in config (exact greenhouse CSV) + a broad name set for cross-ATS dedup."""
    text = open(PROPS).read()
    gh = set()
    for line in text.splitlines():
        if line.startswith("job.companies.greenhouse="):
            gh = {t.strip().lower() for t in line.split("=", 1)[1].split(",") if t.strip()}
    return gh, text.lower()


def enum_greenhouse(pages):
    idx = json.load(urlopen(CC_COLLINFO, 30))[0]["cdx-api"]
    toks = set()
    for pg in range(pages):
        url = f"{idx}?url=boards.greenhouse.io/*&output=json&fl=url&limit=20000&page={pg}"
        try:
            data = urlopen(url, 90).read().decode("utf-8", "replace")
        except Exception as e:
            print(f"  (CC page {pg} failed: {e})", file=sys.stderr)
            break
        for line in data.splitlines():
            m = re.search(r"greenhouse\.io/(?:embed/[^?]*(?:for|token)=)?([A-Za-z0-9_-]+)", line)
            if not m:
                continue
            t = m.group(1).lower()
            if t in ("embed", "v1", "job_app", "job_board", "jobs") or t.isdigit():
                continue
            if re.fullmatch(r"[0-9a-f]{16,}", t):  # opaque hex tokens
                continue
            toks.add(t)
    return toks


def probe_gh(tok):
    try:
        d = json.load(urlopen(f"https://boards-api.greenhouse.io/v1/boards/{tok}/jobs", 12))
    except Exception:
        return tok, None, []
    jobs = d.get("jobs", [])
    hits = []
    for j in jobs:
        t = j.get("title", "") or ""
        loc = (j.get("location") or {}).get("name", "") or ""
        if SCM.search(t) and CA.search(loc) and not DROP.search(t):
            hits.append((t.strip(), loc.strip()))
    return tok, len(jobs), hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ats", choices=["greenhouse"])
    ap.add_argument("--pages", type=int, default=3, help="Common Crawl pages to pull")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--limit-probe", type=int, default=0, help="cap boards probed (0=all)")
    a = ap.parse_args()

    print(f"[1/3] enumerating {a.ats} via Common Crawl ({a.pages} page(s))…", file=sys.stderr)
    toks = enum_greenhouse(a.pages)
    gh_cfg, cfg_all = configured_tokens()
    netnew = sorted(t for t in toks if t not in gh_cfg and t not in cfg_all)
    print(f"      {len(toks)} distinct tokens, {len(netnew)} net-new (not in config)", file=sys.stderr)
    if a.limit_probe:
        netnew = netnew[: a.limit_probe]

    print(f"[2/3] probing {len(netnew)} boards (workers={a.workers})…", file=sys.stderr)
    results, dead, done = [], 0, 0
    with cf.ThreadPoolExecutor(max_workers=a.workers) as ex:
        for tok, total, hits in ex.map(probe_gh, netnew):
            done += 1
            if done % 250 == 0:
                print(f"      …{done}/{len(netnew)}", file=sys.stderr)
            if total is None:
                dead += 1
            elif hits:
                results.append((tok, total, hits))

    print(f"[3/3] {len(results)} net-new boards with >=1 CA-SCM role ({dead} dead/unreachable)\n", file=sys.stderr)
    results.sort(key=lambda r: -len(r[2]))
    print(f"{'CA-SCM':>6}  {'TOTAL':>5}  {'GREENHOUSE TOKEN':28}  SAMPLE ROLE")
    for tok, total, hits in results:
        staff = " (staffing?)" if STAFF.search(tok) else ""
        print(f"{len(hits):>6}  {total:>5}  {tok:28}  {hits[0][0][:40]} | {hits[0][1][:20]}{staff}")


if __name__ == "__main__":
    main()
