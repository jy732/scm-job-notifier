---
name: test-poll
description: Run a one-off SAFE test poll of scm-job-notifier with all outbound email suppressed — validates scrapers/config end-to-end (scrape → filter → classify → persist) without notifying the real recipient. Use when the user says things like "test the poll", "run a test poll", "did the migrated companies work", or wants to verify new scraper config before going live. Optional argument is a company regex filter.
---

# test-poll — safe one-off poll (no email)

Runs a single full poll cycle with **all outbound email disabled**, so scrapers and
new config can be validated without spamming the real `NOTIFICATION_EMAIL`. Leaves the
app **stopped** afterward.

## What to do

```
bash scripts/test-poll.sh [companyRegex] [--no-build]
```

- `companyRegex` — optional grep filter for the per-company breakdown
  (e.g. `"moog|airgas|voyager"`). Omit to see every company.
- `--no-build` — skip the jar rebuild and use the existing `target/` jar.

Then relay: the **Adzuna funnel line** (raw → excluded → long-tail → unseen), the
**per-company pipeline** counts (`scraped → fresh → after exclude → California →
SCM-relevant`), the **notifiable persisted** lines, and the **POLL CYCLE COMPLETE**
summary (`newNotifiable`, `elapsed`).

## How it stays safe

The script starts the jar with these overrides so the *only* poll is the one it
triggers, and **no alert email is ever sent**:

- `--job.notification.scan.cron` → never-fires date (suppresses the 5-min alert scan)
- `--job.summary.cron` → never-fires date (suppresses the daily summary email)
- `--job.poll.cron` → never-fires date (no background auto-poll; only the manual one)
- `--job.adzuna.throttle-minutes=0` → forces Adzuna to fetch this run so its
  exclude/dedup funnel is visible

## Important

- It **stops any running instance first** (to free port 8081). If the live `/app`
  instance was running and sending real alerts, this pauses it — restart with
  `/app start` when finished.
- The poll takes **several minutes** (full scrape of ~150 companies + Gemini
  classification of unseen jobs). The script waits for `POLL CYCLE COMPLETE`.
- Reads/writes H2 (`./data/jobs`) — real rows are persisted (that's intended), but
  `notified` stays false since nothing emails; a later live scan would pick them up.
- A `trap` stops the test instance on exit, so it never leaves a suppressed-email
  app running.
