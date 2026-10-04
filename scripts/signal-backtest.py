#!/usr/bin/env python3
"""Backtest the Gemini signal keywords against the JDs already in H2.

Stage 3 only ever shows Gemini the job TITLE plus a few short snippets mined from the
description by SignalExtractor. This script measures how well that keyword set actually
performs on real stored JDs, and mines candidate phrases that would separate the classes
better.

Two things it deliberately does NOT do, because both produce confident nonsense:

  * It does not treat GEMINI labels as ground truth. Those labels were produced from the
    very signals under test, so scoring against them is circular. They are reported as a
    weak reference only; the graded corpus is TITLE_RULE / DESC_RULE, which are
    deterministic local rules, plus (optionally) GEMINI as --weak-labels.
  * It does not mine raw text. One employer can own a third of a class — Target alone was
    314/1057 OTHER docs, and a naive run surfaced "nights weekends" and "the guest" as the
    top OTHER markers, i.e. Target's store boilerplate. The corpus is de-duplicated by JD
    text and capped per employer before anything is counted.

Usage:
    python3 scripts/signal-backtest.py                  # evaluate current keywords + mine candidates
    python3 scripts/signal-backtest.py --export         # re-export from H2 first (app may stay running)
    python3 scripts/signal-backtest.py --cap 25         # max docs per employer per class (default 20)
    python3 scripts/signal-backtest.py --weak-labels    # also grade against GEMINI labels
    python3 scripts/signal-backtest.py --candidates "sales floor,stock room"   # score specific phrases
"""

import argparse
import collections
import csv
import hashlib
import math
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(REPO, "target", "signal-backtest-jobs.csv")
SIGNAL_JAVA = os.path.join(
    REPO, "src/main/java/com/github/jingyangyu/scmjobnotifier/service/classification/SignalExtractor.java"
)

# Labels produced by deterministic local rules — safe to grade against.
TRUSTED_SOURCES = ("TITLE_RULE", "DESC_RULE")
NOTIFIABLE = ("ENTRY_LEVEL", "INTERNSHIP")

csv.field_size_limit(10**7)


# ── the deployed configuration, parsed from Java so the backtest can't drift ──────────


def load_deployed_config():
    """Reads SIGNAL_KEYWORDS / SIGNAL_WINDOW / MAX_SIGNALS out of SignalExtractor.java.

    Parsed rather than hard-coded: a backtest that scores a stale keyword list is worse
    than no backtest, because it reads as evidence.
    """
    src = open(SIGNAL_JAVA, encoding="utf-8").read()
    block = re.search(r"SIGNAL_KEYWORDS\s*=\s*List\.of\((.*?)\);", src, re.S)
    if not block:
        sys.exit("could not parse SIGNAL_KEYWORDS from " + SIGNAL_JAVA)
    keywords = re.findall(r'"([^"]+)"', block.group(1))
    window = int(re.search(r"SIGNAL_WINDOW\s*=\s*(\d+)", src).group(1))
    max_signals = int(re.search(r"MAX_SIGNALS\s*=\s*(\d+)", src).group(1))
    return keywords, window, max_signals


def extract_signals(title, jd, keywords, window, max_signals):
    """Mirrors SignalExtractor.extract: title first, then description, first match wins,
    capped at max_signals, each snippet a +/- window/2 character context."""
    out = []
    for text, origin in ((title or "", "TITLE"), (jd or "", "DESC")):
        low = text.lower()
        for kw in keywords:
            idx = 0
            while len(out) < max_signals:
                pos = low.find(kw, idx)
                if pos < 0:
                    break
                start = max(0, pos - window // 2)
                end = min(len(text), pos + len(kw) + window // 2)
                out.append((kw, origin, re.sub(r"\s+", " ", text[start:end]).strip()))
                idx = pos + len(kw)
            if len(out) >= max_signals:
                return out
    return out


# ── corpus ───────────────────────────────────────────────────────────────────────────


def export_from_h2():
    """CSVWRITE straight out of H2. AUTO_SERVER lets this run while the app is polling."""
    jdk = "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java"
    java = jdk if os.path.exists(jdk) else "java"
    h2 = subprocess.run(
        "find ~/.m2 -name 'h2-*.jar' | grep -v sources | head -1",
        shell=True, capture_output=True, text=True,
    ).stdout.strip()
    if not h2:
        sys.exit("h2 jar not found in ~/.m2")
    os.makedirs(os.path.dirname(CSV_PATH), exist_ok=True)
    if os.path.exists(CSV_PATH):
        os.remove(CSV_PATH)
    sql = (
        "CALL CSVWRITE('{out}', 'SELECT company, title, level, classification_source AS src, "
        "REGEXP_REPLACE(description, ''<[^>]*>'', '' '') AS jd FROM JOB_POSTING "
        "WHERE description IS NOT NULL AND LENGTH(description) > 200');"
    ).format(out=CSV_PATH)
    r = subprocess.run(
        [java, "-cp", h2, "org.h2.tools.Shell",
         "-url", "jdbc:h2:file:{}/data/jobs;AUTO_SERVER=TRUE".format(REPO),
         "-user", "sa", "-sql", sql],
        capture_output=True, text=True,
    )
    if "Exception" in r.stdout + r.stderr:
        sys.exit("export failed:\n" + (r.stdout + r.stderr)[:600])
    print("exported -> {}".format(CSV_PATH))


def load_corpus(cap, weak_labels):
    if not os.path.exists(CSV_PATH):
        sys.exit("no export found — run with --export first")
    rows = list(csv.DictReader(open(CSV_PATH, encoding="utf-8", errors="replace")))
    sources = TRUSTED_SOURCES + (("GEMINI",) if weak_labels else ())
    graded = [r for r in rows
              if r["SRC"] in sources and r["LEVEL"] in NOTIFIABLE + ("OTHER",)]

    # Collapse identical/near-identical JDs (same employer reposting one template per store),
    # then cap each employer per class so no single board defines a class's vocabulary.
    seen, per_employer, kept = set(), collections.Counter(), []
    for r in graded:
        fingerprint = hashlib.md5(
            re.sub(r"\s+", " ", (r["JD"] or "")[:3000]).encode()
        ).hexdigest()
        if fingerprint in seen:
            continue
        key = (r["COMPANY"], r["LEVEL"])
        if per_employer[key] >= cap:
            continue
        seen.add(fingerprint)
        per_employer[key] += 1
        kept.append(r)
    return rows, graded, kept


# ── reporting ────────────────────────────────────────────────────────────────────────


def report_current(rows, keywords, window, max_signals):
    fire = collections.Counter()
    slots = collections.Counter()
    boiler = re.compile(
        r"(rate of pay|compensation|salary range|pay range|benefits|equal opportunity"
        r"|factors that may be used|base pay)", re.I)
    boiler_hits = years_hits = 0
    for r in rows:
        sig = extract_signals(r["TITLE"], r["JD"], keywords, window, max_signals)
        slots[len(sig)] += 1
        for kw, _origin, snippet in sig:
            fire[kw] += 1
            if kw == "years":
                years_hits += 1
                if boiler.search(snippet):
                    boiler_hits += 1
    n = len(rows)
    print("\n=== CURRENT KEYWORDS — coverage on {} stored JDs ===".format(n))
    print("  {:<24}{:>10}{:>10}".format("keyword", "jobs", "%"))
    for kw in keywords:
        flag = "   <- never fires" if fire[kw] == 0 else ""
        print("  {:<24}{:>10}{:>9.1f}%{}".format(kw, fire[kw], 100 * fire[kw] / n, flag))
    zero = slots[0]
    print("\n  jobs with ZERO signals : {} ({:.1f}%)  <- Gemini sees the TITLE only"
          .format(zero, 100 * zero / n))
    print("  slot usage (max {})     : {}".format(
        max_signals, {k: slots[k] for k in sorted(slots)}))
    if years_hits:
        print("  'years' snippets that are pay/benefits boilerplate: {}/{} ({:.1f}%)"
              .format(boiler_hits, years_hits, 100 * boiler_hits / years_hits))


def phrase_sets(text, max_words=4000):
    words = re.findall(r"[a-z][a-z'&/-]+", (text or "").lower())[:max_words]
    out = set(words)
    out.update(" ".join(words[i:i + 2]) for i in range(len(words) - 1))
    out.update(" ".join(words[i:i + 3]) for i in range(len(words) - 2))
    return out


def mine(kept, keywords, min_docs, top):
    pos = [r for r in kept if r["LEVEL"] in NOTIFIABLE]
    neg = [r for r in kept if r["LEVEL"] == "OTHER"]
    if not pos or not neg:
        print("\n(not enough graded docs on both sides to mine)")
        return []
    dfp, dfn = collections.Counter(), collections.Counter()
    for r in pos:
        dfp.update(phrase_sets(r["JD"]))
    for r in neg:
        dfn.update(phrase_sets(r["JD"]))
    P, N = len(pos), len(neg)

    def logodds(p):
        return math.log(((dfp[p] + 0.5) / (P + 1)) / ((dfn[p] + 0.5) / (N + 1)))

    cands = [p for p in set(list(dfp) + list(dfn))
             if dfp[p] + dfn[p] >= min_docs and p not in keywords]
    ranked = sorted(cands, key=logodds)
    print("\n=== CANDIDATE PHRASES — de-duplicated, employer-capped ({} notifiable / {} OTHER) ==="
          .format(P, N))
    print("  {:<34}{:>12}{:>9}{:>10}".format("phrase", "notifiable%", "OTHER%", "log-odds"))
    print("  -- predicts OTHER (would let Gemini rule a job out) --")
    for p in ranked[:top]:
        print("  {:<34}{:>11.1f}%{:>8.1f}%{:>10.2f}"
              .format(p, 100 * dfp[p] / P, 100 * dfn[p] / N, logodds(p)))
    print("  -- predicts NOTIFIABLE --")
    for p in ranked[-top:][::-1]:
        print("  {:<34}{:>11.1f}%{:>8.1f}%{:>10.2f}"
              .format(p, 100 * dfp[p] / P, 100 * dfn[p] / N, logodds(p)))
    return ranked


def project(rows, keywords, window, max_signals, extra):
    """What would adding `extra` actually change? Only the jobs that currently yield NO
    signal can gain evidence, so that is the number worth reporting."""
    if not extra:
        return
    gained = 0
    zero_before = 0
    for r in rows:
        before = extract_signals(r["TITLE"], r["JD"], keywords, window, max_signals)
        if before:
            continue
        zero_before += 1
        after = extract_signals(r["TITLE"], r["JD"], list(keywords) + extra, window, max_signals)
        if after:
            gained += 1
    print("\n=== PROJECTED EFFECT of adding: {} ===".format(", ".join(extra)))
    print("  jobs with zero signals today : {}".format(zero_before))
    print("  would gain >=1 signal        : {} ({:.1f}% of the blind set)"
          .format(gained, 100 * gained / max(zero_before, 1)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--export", action="store_true", help="re-export JDs from H2 first")
    ap.add_argument("--cap", type=int, default=20, help="max docs per employer per class")
    ap.add_argument("--min-docs", type=int, default=25, help="min doc frequency to mine a phrase")
    ap.add_argument("--top", type=int, default=15, help="phrases to show per direction")
    ap.add_argument("--weak-labels", action="store_true", help="also grade against GEMINI labels")
    ap.add_argument("--candidates", default="", help="comma-separated phrases to project")
    args = ap.parse_args()

    if args.export:
        export_from_h2()
    keywords, window, max_signals = load_deployed_config()
    print("deployed config: {} keywords, window {}, max {} signals/job"
          .format(len(keywords), window, max_signals))

    rows, graded, kept = load_corpus(args.cap, args.weak_labels)
    print("corpus: {} JDs | graded {} ({}) | after dedup+cap {}".format(
        len(rows), len(graded),
        "TITLE_RULE+DESC_RULE+GEMINI" if args.weak_labels else "TITLE_RULE+DESC_RULE",
        len(kept)))

    report_current(rows, keywords, window, max_signals)
    mine(kept, keywords, args.min_docs, args.top)
    extra = [c.strip().lower() for c in args.candidates.split(",") if c.strip()]
    project(rows, keywords, window, max_signals, extra)


if __name__ == "__main__":
    main()
