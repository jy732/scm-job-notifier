---
name: optimize-signals
description: Backtest and tune the Gemini signal keywords (SignalExtractor.SIGNAL_KEYWORDS) against the JDs already stored in H2 — measure coverage, find dead keywords, mine candidate phrases that separate the classes, and project what a change would do before touching the filter. Use when the user says "optimize the keywords", "backtest the signals", "why is Gemini guessing", "tune SIGNAL_KEYWORDS", or after a batch of obviously-wrong classifications.
---

# optimize-signals — tune what Gemini actually sees

Stage 3 never sees a job description. It sees the **title** plus up to `MAX_SIGNALS` short
snippets that `SignalExtractor` mines with `SIGNAL_KEYWORDS`. If no keyword matches, the
prompt literally says `Signals: (none)` and Gemini classifies on the title alone.

Measured on 3,761 stored JDs (2026-10-03) — the number that matters is the third row:

| population | blind (zero signals) |
|---|---|
| all stored JDs | 72.0% |
| resolved locally, never reached Gemini | 56.7% |
| **jobs that actually reached Stage 3** | **84.5%** |

Stages 1–2 cream off everything with an obvious marker, so the jobs that reach Gemini are
exactly the ones the keywords cannot see. Tuning this list is therefore high-leverage, and
also easy to get wrong — hence the guardrails below.

## Run it

```bash
python3 scripts/signal-backtest.py --export          # pull JDs from H2 (app can keep running)
python3 scripts/signal-backtest.py                   # coverage + dead keywords + mined candidates
python3 scripts/signal-backtest.py --weak-labels     # add GEMINI labels for a balanced OTHER class
python3 scripts/signal-backtest.py --candidates "sales floor,stock room"   # project a change
```

The script parses `SIGNAL_KEYWORDS` / `SIGNAL_WINDOW` / `MAX_SIGNALS` straight out of
`SignalExtractor.java`, so it always scores what is deployed. Never hard-code the list in an
ad-hoc script — a backtest of a stale list reads as evidence while being wrong.

## Three traps, all hit for real

**1. One employer owns a class.** Target was 314 of 1,057 OTHER docs and only 592 of those
1,057 JDs were textually distinct. A naive mining run returned `nights weekends`, `the guest`,
`lifting or moving` as the top OTHER markers — Target's store boilerplate, not signal. The
script de-duplicates by JD fingerprint and caps docs per employer per class (`--cap`), and any
hand-rolled analysis must do the same.

**2. GEMINI labels are not ground truth.** They were produced *from the signals under test*;
scoring against them is circular. Graded corpus defaults to `TITLE_RULE` + `DESC_RULE`
(deterministic local rules). `AUTO_APPROVED` must always be excluded — those are Gemini
failures auto-passed as UNSURE, i.e. unlabelled. `--weak-labels` adds GEMINI deliberately,
because of trap 3.

**3. The trusted corpus is lopsided.** `TITLE_RULE`/`DESC_RULE` almost only produce
ENTRY_LEVEL/INTERNSHIP — a recent run graded 491 notifiable against just 57 OTHER. Mining
OTHER-predictors from that alone yields noise (`define`, `establish`, `influence`). For
OTHER-side candidates use `--weak-labels` and treat the result as a hypothesis to eyeball,
not a verdict.

## What to change, in order of leverage

1. **Scope, not coverage.** Every current keyword asks *how senior is this?* — `years`,
   enrollment phrases, new-grad phrases. None asks *what kind of work is this?* That is why
   Boot Barn's "Inventory Control Associate (Seasonal)" split 46 UNSURE / 44 OTHER on
   byte-identical input: the retail tells (`sales floor`, `stock room`, `selling culture`)
   exist in the JD but no keyword extracts them. Adding role-nature keywords gives Gemini
   evidence it has never had.
2. **Dead weight.** `rising junior` and `current student` fired **0 times in 3,761 JDs**;
   nine more fire under 1% each. `years` alone carries 38.6%.
3. **Window/slots are NOT the constraint.** Only 3.7% of jobs fill all three slots, and a
   50-job batch uses roughly 1% of Gemini 2.5 Flash's ~1M-token context. There is room to
   raise `MAX_SIGNALS`/`SIGNAL_WINDOW`, but it buys nothing until coverage improves.

A measured non-issue: `years` landing in pay/benefits boilerplate is **2.8%** (41/1450), not
the widespread problem it looks like from one example.

## Before/after discipline

1. Run the backtest, note the blind rate for `SRC=GEMINI` specifically.
2. Project candidates with `--candidates` — it reports how many currently-blind jobs would
   gain a signal. A candidate that only fires on jobs that already have signals is worthless.
3. Change `SIGNAL_KEYWORDS` in `SignalExtractor.java`, keep unit tests green
   (`./mvnw verify` enforces 100% line coverage).
4. Re-run the backtest; the blind rate should fall.
5. Validate end-to-end with `/filter-audit`, which shows both leakage and over-filtering, and
   re-classify historical rows with `POST /api/replay/classification` (dry-run first) rather
   than waiting for new postings.

## Notes & guardrails

- This edits shared classification behaviour — **get explicit approval before changing
  `SignalExtractor` or `FilterKeywords`**, and say plainly that it affects every company.
- More signals = more Gemini tokens per job. Cost scales with batch content, not job count.
- Don't infer a keyword's value from a handful of examples; the corpus is right there.
  Earlier guesses that the backtest overturned: "boilerplate is eating the `years` slot"
  (2.8%) and "signals are missing for ~83% of jobs" (72% overall, 84.5% at Stage 3).
