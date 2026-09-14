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


def urlopen(url, timeout=15, retries=2):
    last = None
    for attempt in range(retries + 1):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout)
        except Exception as e:  # transient CC/API disconnects, timeouts
            last = e
            import time

            time.sleep(1.5 * (attempt + 1))
    raise last


def configured_tokens(csv_key):
    """Exact configured slugs: this ATS's CSV + every other ATS's tokens/subdomains/names for
    cross-ATS dedup. Uses EXACT token sets (not substring-in-file, which false-matches e.g. slug
    'etched' inside the word 'Fetched')."""
    own, allcfg = set(), set()
    for line in open(PROPS):
        line = line.strip()
        if line.startswith("job.companies.") and "=" in line:
            key = line.split("=", 1)[0].rsplit(".", 1)[-1]
            vals = {t.strip().lower() for t in line.split("=", 1)[1].split(",") if t.strip()}
            allcfg |= vals
            if key == csv_key:
                own = vals
        m = re.match(r"job\.\w+\.companies\[\d+\]\.(?:name|subdomain)=(.+)", line)
        if m:
            allcfg.add(m.group(1).strip().lower())
    return own, allcfg


def _keep(t):
    """Reject non-company path segments and opaque tokens; keep original case (Ashby is case-sensitive)."""
    low = t.lower()
    if low in ("embed", "v1", "job_app", "job_board", "jobs", "postings", "api") or low.isdigit():
        return False
    if re.fullmatch(r"[0-9a-f]{16,}", low):  # opaque hex ids
        return False
    return True


def enum_cc(domain, token_re, pages):
    """Enumerate company tokens for a path-based ATS from the Common Crawl index."""
    idx = json.load(urlopen(CC_COLLINFO, 30))[0]["cdx-api"]
    toks = {}
    for pg in range(pages):
        url = f"{idx}?url={domain}/*&output=json&fl=url&limit=20000&page={pg}"
        try:
            data = urlopen(url, 90).read().decode("utf-8", "replace")
        except Exception as e:
            print(f"  (CC page {pg} stopped: {e})", file=sys.stderr)
            break
        for line in data.splitlines():
            m = re.search(token_re, line)
            if m and _keep(m.group(1)):
                toks.setdefault(m.group(1).lower(), m.group(1))  # dedupe by lower, keep first-seen case
    return toks  # {lower: original-case}


def _filter(pairs):
    return [(t.strip(), l.strip()) for t, l in pairs if t and SCM.search(t) and CA.search(l or "") and not DROP.search(t)]


def probe_gh(tok):
    try:
        d = json.load(urlopen(f"https://boards-api.greenhouse.io/v1/boards/{tok}/jobs", 12))
    except Exception:
        return tok, None, []
    jobs = d.get("jobs", [])
    return tok, len(jobs), _filter((j.get("title", ""), (j.get("location") or {}).get("name", "")) for j in jobs)


def probe_lever(tok):
    try:
        d = json.load(urlopen(f"https://api.lever.co/v0/postings/{tok}?mode=json", 12))
    except Exception:
        return tok, None, []
    if not isinstance(d, list):
        return tok, None, []
    return tok, len(d), _filter((j.get("text", ""), (j.get("categories") or {}).get("location", "")) for j in d)


def probe_ashby(tok):
    try:
        d = json.load(urlopen(f"https://api.ashbyhq.com/posting-api/job-board/{tok}", 12))
    except Exception:
        return tok, None, []
    jobs = d.get("jobs", []) if isinstance(d, dict) else []
    return tok, len(jobs), _filter((j.get("title", ""), j.get("location", "")) for j in jobs)


ATS = {
    "greenhouse": dict(domain="boards.greenhouse.io", csv_key="greenhouse", probe=probe_gh,
                       token_re=r"greenhouse\.io/(?:embed/[^?]*(?:for|token)=)?([A-Za-z0-9_-]+)"),
    "lever": dict(domain="jobs.lever.co", csv_key="lever", probe=probe_lever,
                  token_re=r"jobs\.lever\.co/([A-Za-z0-9_-]+)"),
    "ashby": dict(domain="jobs.ashbyhq.com", csv_key="ashby", probe=probe_ashby,
                  token_re=r"jobs\.ashbyhq\.com/([A-Za-z0-9_-]+)"),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ats", choices=list(ATS))
    ap.add_argument("--pages", type=int, default=3, help="Common Crawl pages to pull")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--limit-probe", type=int, default=0, help="cap boards probed (0=all)")
    a = ap.parse_args()
    spec = ATS[a.ats]

    print(f"[1/3] enumerating {a.ats} via Common Crawl ({a.pages} page(s))…", file=sys.stderr)
    toks = enum_cc(spec["domain"], spec["token_re"], a.pages)  # {lower: original-case}
    own, cfg_all = configured_tokens(spec["csv_key"])
    netnew = sorted(orig for low, orig in toks.items() if low not in own and low not in cfg_all)
    print(f"      {len(toks)} distinct tokens, {len(netnew)} net-new (not in config)", file=sys.stderr)
    if a.limit_probe:
        netnew = netnew[: a.limit_probe]

    print(f"[2/3] probing {len(netnew)} boards (workers={a.workers})…", file=sys.stderr)
    results, dead, done = [], 0, 0
    with cf.ThreadPoolExecutor(max_workers=a.workers) as ex:
        for tok, total, hits in ex.map(spec["probe"], netnew):
            done += 1
            if done % 250 == 0:
                print(f"      …{done}/{len(netnew)}", file=sys.stderr)
            if total is None:
                dead += 1
            elif hits:
                results.append((tok, total, hits))

    print(f"[3/3] {len(results)} net-new boards with >=1 CA-SCM role ({dead} dead/unreachable)\n", file=sys.stderr)
    results.sort(key=lambda r: -len(r[2]))
    print(f"{'CA-SCM':>6}  {'TOTAL':>5}  {a.ats.upper() + ' TOKEN':28}  SAMPLE ROLE")
    for tok, total, hits in results:
        staff = " (staffing?)" if STAFF.search(tok) else ""
        print(f"{len(hits):>6}  {total:>5}  {tok:28}  {hits[0][0][:40]} | {hits[0][1][:20]}{staff}")


if __name__ == "__main__":
    main()
