#!/usr/bin/env python3
"""A/B the current Stage-3 payload against a hybrid Responsibilities/Qualifications payload.

Variant A (current)  : "Signals:" — up to MAX_SIGNALS keyword windows from SignalExtractor.
                       Median payload at Stage 3 is 0 chars; 84.5% of jobs carry nothing.
Variant B (hybrid)   : the Responsibilities/Qualifications sections when the JD has them,
                       otherwise the head of the JD. Never blind.

Both variants are sent to the real model with the production system prompt, so the only
variable is what the job payload contains.

Graded against cases where the correct answer is known WITHOUT asking Gemini:
  * bootbarn UNSURE batch  — seasonal retail stockroom clerks; correct answer OTHER.
    (Gemini split these 46 UNSURE / 44 OTHER on byte-identical text.)
  * TITLE_RULE INTERNSHIP  — title literally says intern/co-op; correct answer INTERNSHIP.
  * DESC_RULE OTHER        — local YOE rule read >3 years; correct answer OTHER.

Usage:
    python3 scripts/signal-ab-test.py --limit 40          # ~2 calls per variant
    python3 scripts/signal-ab-test.py --limit 40 --dry    # build prompts, call nothing
"""

import argparse, csv, json, os, re, ssl, statistics, subprocess, sys, urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(REPO, "target", "signal-backtest-jobs.csv")
MODEL = "gemini-2.5-flash"
csv.field_size_limit(10**7)

SYSTEM = (
    "You classify supply-chain-management (SCM) job postings by career stage. "
    "Use the title and extracted signals. Categories: "
    "INTERNSHIP = internship / co-op / summer program for a currently-enrolled student. "
    "ENTRY_LEVEL = full-time early-career role: new grad, associate, coordinator, "
    "rotational/leadership development program, or 0-3 years experience. "
    "UNSURE = clearly early-career SCM but you cannot confidently decide between internship "
    "and full-time entry-level. "
    "OTHER = anything else: senior/manager/lead, 4+ years experience, hourly/manual warehouse "
    "or production labor (material handler, warehouse associate, order selector/picker, "
    "forklift, stocker, clerk — we target only professional/analytical SCM roles like analyst, "
    "planner, buyer, coordinator, specialist), OR any role that is NOT supply chain / logistics "
    "/ procurement / sourcing / planning / operations (e.g. software, finance, sales, marketing, "
    "HR). Response format, one line per job: 1:ENTRY_LEVEL\\n2:INTERNSHIP\\n3:OTHER\\n4:UNSURE"
)

# Deployed signal config, parsed from Java so variant A is exactly what production sends.
SIG_JAVA = os.path.join(
    REPO, "src/main/java/com/github/jingyangyu/scmjobnotifier/service/classification/SignalExtractor.java")

RESP = re.compile(r"(responsibilities|what you.{0,3}ll do|duties|the role|day.to.day|"
                  r"essential functions|job summary)", re.I)
QUAL = re.compile(r"(qualifications|requirements|what you.{0,3}ll (bring|need)|"
                  r"experience required|who you are|minimum)", re.I)


def deployed_signal_config():
    src = open(SIG_JAVA, encoding="utf-8").read()
    kws = re.findall(r'"([^"]+)"',
                     re.search(r"SIGNAL_KEYWORDS\s*=\s*List\.of\((.*?)\);", src, re.S).group(1))
    win = int(re.search(r"SIGNAL_WINDOW\s*=\s*(\d+)", src).group(1))
    mx = int(re.search(r"MAX_SIGNALS\s*=\s*(\d+)", src).group(1))
    return kws, win, mx


def variant_a(title, jd, kws, win, mx):
    """Current production payload: keyword windows, title first then description."""
    out = []
    for text in (title or "", jd or ""):
        low = text.lower()
        for kw in kws:
            i = 0
            while len(out) < mx:
                p = low.find(kw, i)
                if p < 0:
                    break
                out.append(re.sub(r"\s+", " ",
                                  text[max(0, p - win // 2): p + len(kw) + win // 2]).strip())
                i = p + len(kw)
            if len(out) >= mx:
                return " | ".join(f'"{s}"' for s in out)
    return " | ".join(f'"{s}"' for s in out) if out else "(none)"


def variant_b(jd, section_cap=1200, head_cap=2500):
    """Hybrid: Responsibilities/Qualifications sections when present, else the JD head.

    Sections are preferred over head-truncation because 47% of discriminating cues sit
    beyond the first 1500 chars — a blind head cut silently drops the evidence.
    """
    jd = re.sub(r"\s+", " ", jd or "").strip()
    parts = []
    for pat in (RESP, QUAL):
        m = pat.search(jd)
        if m:
            parts.append(jd[m.start(): m.start() + section_cap])
    return " … ".join(parts) if parts else jd[:head_cap]


def build_prompt(items, variant, cfg):
    sb = ["Classify these job postings:\n"]
    for i, r in enumerate(items, 1):
        payload = (variant_a(r["TITLE"], r["JD"], *cfg) if variant == "A"
                   else variant_b(r["JD"]) or "(none)")
        sb.append(f'{i}. Title: {r["TITLE"]}\n   Signals: {payload}\n')
    return "\n".join(sb)


def call_gemini(prompt, key, temperature=0.0):
    body = json.dumps({
        "systemInstruction": {"parts": [{"text": SYSTEM}]},
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": temperature},
    }).encode()
    url = (f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}"
           f":generateContent?key={key}")
    # curl rather than urllib: this python build ships no CA bundle (certifi absent), and
    # disabling verification to talk to an API that carries a key is not a trade worth making.
    proc = subprocess.run(
        ["curl", "-sS", "-m", "120", "-X", "POST", url,
         "-H", "Content-Type: application/json", "--data-binary", "@-"],
        input=body, capture_output=True)
    if proc.returncode != 0:
        sys.exit("curl failed: " + proc.stderr.decode()[:300])
    d = json.loads(proc.stdout)
    if "error" in d:
        sys.exit("gemini error: {} {}".format(d["error"].get("code"), d["error"].get("message", "")[:200]))
    text = d["candidates"][0]["content"]["parts"][0]["text"]
    out = {}
    for line in text.splitlines():
        m = re.match(r"\s*(\d+)\s*:\s*([A-Z_]+)", line)
        if m:
            out[int(m.group(1))] = m.group(2)
    usage = d.get("usageMetadata", {})
    return out, usage.get("promptTokenCount", 0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=40, help="jobs per graded group")
    ap.add_argument("--dry", action="store_true", help="build prompts, make no API calls")
    args = ap.parse_args()

    cfg = deployed_signal_config()
    rows = list(csv.DictReader(open(CSV_PATH, encoding="utf-8", errors="replace")))

    groups = {
        "retail clerks (bootbarn) → OTHER":
            ([r for r in rows if r["COMPANY"] == "bootbarn" and r["LEVEL"] == "UNSURE"], "OTHER"),
        "title says intern → INTERNSHIP":
            ([r for r in rows if r["SRC"] == "TITLE_RULE" and r["LEVEL"] == "INTERNSHIP"], "INTERNSHIP"),
        "YOE>3 by local rule → OTHER":
            ([r for r in rows if r["SRC"] == "DESC_RULE" and r["LEVEL"] == "OTHER"], "OTHER"),
    }

    key = os.environ.get("GEMINI_API_KEY", "")
    if not key and not args.dry:
        sys.exit("GEMINI_API_KEY not set — run: set -a; . ./.env; set +a")

    totals = {"A": [0, 0, 0], "B": [0, 0, 0]}  # correct, n, tokens
    for name, (items, truth) in groups.items():
        items = items[: args.limit]
        if not items:
            continue
        blind_a = sum(1 for r in items
                      if variant_a(r["TITLE"], r["JD"], *cfg) == "(none)")
        chars_b = [len(variant_b(r["JD"])) for r in items]
        print(f"\n=== {name}   (n={len(items)}, correct answer = {truth})")
        print(f"    A payload blind: {blind_a}/{len(items)}"
              f"   |   B payload median {statistics.median(chars_b):.0f} chars")
        if args.dry:
            continue
        for variant in ("A", "B"):
            preds, tok = call_gemini(build_prompt(items, variant, cfg), key)
            ok = sum(1 for i in range(1, len(items) + 1) if preds.get(i) == truth)
            dist = {}
            for i in range(1, len(items) + 1):
                dist[preds.get(i, "?")] = dist.get(preds.get(i, "?"), 0) + 1
            totals[variant][0] += ok
            totals[variant][1] += len(items)
            totals[variant][2] += tok
            label = "A current signals" if variant == "A" else "B hybrid sections"
            print(f"    {label}: {ok}/{len(items)} correct ({100*ok/len(items):5.1f}%)"
                  f"  {dist}  [{tok} prompt tokens]")

    if not args.dry:
        print("\n=== OVERALL")
        for variant, label in (("A", "current signals"), ("B", "hybrid sections")):
            c, n, t = totals[variant]
            print(f"  {label:18} {c}/{n} correct ({100*c/max(n,1):5.1f}%)   {t:,} prompt tokens")


if __name__ == "__main__":
    main()
