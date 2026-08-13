#!/usr/bin/env bash
# One-off SAFE test poll for scm-job-notifier.
#
# Runs a single full poll cycle with ALL outbound email suppressed, so you can
# validate scrapers / new config without notifying the real NOTIFICATION_EMAIL.
# Leaves the app STOPPED when done.
#
# Usage: scripts/test-poll.sh [companyRegex] [--no-build]
#   companyRegex  optional grep filter for the per-company breakdown
#                 (e.g. "moog|airgas|voyager"); omit to see every company.
#   --no-build    skip the jar rebuild (use the existing target/ jar).
#
# How it stays safe: overrides the notification-scan, daily-summary and auto-poll
# crons to a never-fires date, so the only poll is the one this script triggers
# and NO alert email is ever sent. Adzuna throttle is set to 0 so the aggregator
# actually fetches (lets you see its exclude/dedup funnel this run).
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

JAR="target/scm-job-notifier-0.0.1-SNAPSHOT.jar"
LOG="polltest.log"
PORT=8081
PATTERN="scm-job-notifier-0.0.1-SNAPSHOT.jar"
NEVER="0 0 5 31 12 *"   # 05:00 on Dec 31 — effectively never during a test

BUILD=1
FILTER=""
for a in "$@"; do
  case "$a" in
    --no-build) BUILD=0 ;;
    *) FILTER="$a" ;;
  esac
done

cleanup() { pkill -9 -f "$PATTERN" >/dev/null 2>&1 && echo "✓ test instance stopped"; }
trap cleanup EXIT

# 1) Free the port / stop any live instance (this would otherwise send real email).
if pgrep -f "$PATTERN" >/dev/null 2>&1; then
  echo "stopping existing instance (frees port $PORT)…"; pkill -9 -f "$PATTERN"; sleep 1
fi

# 2) Build (so the test reflects current code/config).
if [ "$BUILD" = 1 ]; then
  echo "building jar…"
  ./mvnw -q -DskipTests package || { echo "✗ build failed"; exit 1; }
fi

# 3) Start with all outbound email + auto-poll suppressed.
echo "starting test instance (email + auto-poll suppressed)…"
nohup java -jar "$JAR" \
  --job.notification.scan.cron="$NEVER" \
  --job.summary.cron="$NEVER" \
  --job.poll.cron="$NEVER" \
  --job.adzuna.throttle-minutes=0 > "$LOG" 2>&1 &
for _ in $(seq 1 120); do
  grep -q "Started ScmJobNotifierApplication" "$LOG" 2>/dev/null && { echo "✓ started"; break; }
  grep -qi "APPLICATION FAILED TO START" "$LOG" 2>/dev/null && { echo "✗ startup failed — see $LOG"; tail -20 "$LOG"; exit 1; }
  sleep 1
done
echo "=== scraper init counts ==="
grep -E "scraper initialized" "$LOG"

# 4) Trigger one poll (detached — it runs server-side even if curl returns).
echo "triggering poll at $(date +%H:%M:%S) …"
curl -s -m 900 -X POST "http://localhost:$PORT/api/test/poll" >/dev/null 2>&1 &

# 5) Wait for completion (poll takes several minutes).
echo "waiting for POLL CYCLE COMPLETE (several min)…"
for _ in $(seq 1 150); do
  grep -q "POLL CYCLE COMPLETE" "$LOG" 2>/dev/null && break
  sleep 8
done

# 6) Report.
echo; echo "======== RESULTS ========"
echo "--- Adzuna funnel (exclusion / dedup) ---"
grep -E "Adzuna: .*raw hits" "$LOG" | tail -1
grep -E "\[adzuna\] adzuna —" "$LOG" | tail -2
echo "--- per-company pipeline (scraped→CA→SCM) ---"
if [ -n "$FILTER" ]; then
  grep -E "] .* — [0-9]+ scraped" "$LOG" | grep -iE "$FILTER"
else
  grep -E "] .* — [0-9]+ scraped" "$LOG"
fi
echo "--- notifiable persisted ---"
{ [ -n "$FILTER" ] && grep -E "notifiable \(" "$LOG" | grep -iE "$FILTER" || grep -E "notifiable \(" "$LOG"; } | grep -vE " 0 notifiable "
echo "--- poll summary ---"
grep -E "POLL CYCLE COMPLETE" "$LOG" | tail -1
echo "========================="
# trap cleanup stops the instance on exit
