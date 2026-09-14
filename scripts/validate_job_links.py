#!/usr/bin/env python3
"""Validate persisted job-posting URLs by fetching each and title-matching the job.

Reads a CSV of (company,title,url) — produced by validate-job-links.sh via H2 CSVWRITE —
fetches every URL (following redirects, browser UA), and classifies each link:

  OK       page reachable AND its <title>/<h1> contains the job's title tokens
  DEAD     unreachable, non-2xx, OR a soft-404 (HTTP 200 whose title says "not found")
  UNKNOWN  reachable 200 but title couldn't be verified — almost always a JS-SPA job
           page (Workday/Greenhouse/iCIMS render the title client-side, so a plain
           fetch only sees the app shell). Not a failure; just unverifiable this way.

The signal that catches a broken URL *pattern* (the Ross Stores bug) is a company whose
links are all DEAD — e.g. "rossstores 0/5 OK, 5 DEAD". Soft-404s are the whole point:
they return HTTP 200, so a status-only check would miss them; we read the title too.

Usage: validate_job_links.py <csv_path>
"""
import csv
import re
import sys
import urllib.request
from collections import defaultdict

UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
)
TITLE_RE = re.compile(r"<title[^>]*>([^<]*)</title>", re.I)
H1_RE = re.compile(r"<h1[^>]*>(.*?)</h1>", re.I | re.S)
TAGS = re.compile(r"<[^>]+>")
NOT_FOUND = re.compile(
    r"page not found|not found|no longer (available|exists)|does not exist"
    r"|has expired|been removed|404 error|job.{0,20}closed",
    re.I,
)
STOP = {"the", "a", "an", "of", "and", "for", "to", "in", "at", "i", "ii", "iii", "iv"}


def tokens(text):
    return {t for t in re.split(r"[^a-z0-9]+", (text or "").lower()) if t and t not in STOP and len(t) > 1}


def fetch(url):
    """Return (status, page_text) following redirects; status 0 on transport error."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read(300_000).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception:
        return 0, ""


def classify(job_title, url):
    status, html = fetch(url)
    # Only an unambiguous "gone" status is DEAD. 403/429/5xx/timeout mean the *fetch* was
    # blocked or flaky (Tesla's Akamai 403s datacenter IPs), not that the link is broken.
    if status in (404, 410):
        return "DEAD", f"http {status}"
    if status == 0:
        return "UNKNOWN", "unreachable (network/timeout)"
    if status in (401, 403, 429) or status >= 500:
        return "UNKNOWN", f"http {status} (blocked/transient — can't verify)"
    page_title = (TITLE_RE.search(html) or [None, ""])[1]
    h1 = TAGS.sub(" ", (H1_RE.search(html) or [None, ""])[1])
    want = tokens(job_title)
    matched = want & tokens(f"{page_title} {h1}")
    if want and len(matched) >= max(1, len(want) // 2):
        return "OK", ""
    # No title match. A server-rendered "Page Not Found" title/body is a real soft-404 (the
    # Ross bug). An EMPTY title is a JS-SPA shell (Workday et al. render the title client-side,
    # so an empty title is unverifiable, NOT dead) — those fall through to UNKNOWN.
    if page_title.strip() and NOT_FOUND.search(page_title):
        return "DEAD", f"soft-404 (title: {page_title.strip()[:50]!r})"
    if NOT_FOUND.search(html[:20000]):
        return "DEAD", "not-found content (HTTP 200)"
    return "UNKNOWN", f"200 but unverifiable (JS-SPA / no title) — {page_title.strip()[:40]!r}"


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: validate_job_links.py <csv_path>")
    rows = list(csv.DictReader(open(sys.argv[1])))
    by_co = defaultdict(list)
    for r in rows:
        verdict, note = classify(r["TITLE"], r["URL"])
        by_co[r["COMPANY"]].append((verdict, note, r["TITLE"], r["URL"]))

    print(f"\nValidated {len(rows)} link(s) across {len(by_co)} company(ies):\n")
    print(f"{'COMPANY':22} {'OK':>3} {'DEAD':>5} {'UNK':>4}  status")
    dead_cos = []
    for co in sorted(by_co):
        res = by_co[co]
        ok = sum(1 for v, *_ in res if v == "OK")
        dead = sum(1 for v, *_ in res if v == "DEAD")
        unk = sum(1 for v, *_ in res if v == "UNKNOWN")
        flag = "  ⛔ ALL DEAD — likely a broken URL pattern" if dead and ok == 0 and unk == 0 else ""
        print(f"{co:22} {ok:>3} {dead:>5} {unk:>4}{flag}")
        if dead:
            dead_cos.append(co)
    # Detail for anything DEAD — that's what a human needs to act on.
    for co in dead_cos:
        print(f"\n  {co} — dead links:")
        for v, note, title, url in by_co[co]:
            if v == "DEAD":
                print(f"    ✗ {title[:45]:45} {note}")
                print(f"      {url}")
    print()
    # Exit non-zero if any company is entirely dead (CI-friendly).
    sys.exit(1 if any(all(v == "DEAD" for v, *_ in by_co[c]) for c in by_co) else 0)


if __name__ == "__main__":
    main()
