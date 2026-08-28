#!/usr/bin/env bash
# Ad-hoc FULL filter audit — run manually, NOT scheduled.
#
#   Part A  deterministic pre-filter, from a LIVE scrape (spins a suppressed in-mem instance —
#           no email, does NOT touch the live data/jobs DB). Runs analyze-filter-audit.py:
#           per-company funnel + 6 checks (leakage / location-miss / keyword-gap / labor
#           over-reach / seniority false-drop / shift-labor leakage).
#   Part B  Gemini's real decisions, from the LIVE data/jobs DB via H2 AUTO_SERVER (read-only;
#           works while the app is running). Runs analyze-gemini-audit.py: over-drop + leakage.
#
# Usage:
#   scripts/full-audit.sh                # LITE: all HTTP/JSON scrapers + Adzuna (skips heavy ones)
#   scripts/full-audit.sh --all          # + apple, google, tesla(!Bright Data $), meta, brassring
#   scripts/full-audit.sh --no-build     # reuse the existing target/ jar
#   scripts/full-audit.sh company=cohu,iherb        # custom scope (passed straight to the endpoint)
#   scripts/full-audit.sh platform=adzuna,workday   #   "
#   scripts/full-audit.sh --part=a       # only Part A   (also --part=b for only Part B)
#
# Outputs (both gitignored): filter-audit.csv + Part A analysis, gemini-audit.csv + Part B analysis.

cd "$(dirname "$0")/.." || exit 1

PORT=8085
NEVER="0 0 5 31 12 *"
JAR="target/scm-job-notifier-0.0.1-SNAPSHOT.jar"
# Cheap, reliable scrapers. Excludes apple/google (Playwright), tesla (Bright Data credits),
# meta (TLS-fragile in some envs), brassring (login-gated / disabled). Use --all to include them.
LITE="adzuna,amazon,ashby,bamboohr,bytedance,eightfold,greenhouse,icims,jibe,lever,microsoft,oraclecloud,paloaltonetworks,paylocity,rossstores,smartrecruiters,successfactors,workday"

BUILD=1
SCOPE="platform=$LITE"
PART="ab"
for a in "$@"; do
  case "$a" in
    --all) SCOPE="" ;;                 # empty scope = every scraper bean
    --no-build) BUILD=0 ;;
    --part=a) PART="a" ;;
    --part=b) PART="b" ;;
    platform=* | company=*) SCOPE="$a" ;;
    *) echo "unknown arg: $a (see header for usage)"; exit 1 ;;
  esac
done
[ -z "$SCOPE" ] && echo "⚠  --all includes Tesla (Bright Data credits) + Playwright browsers — slow/costly."

# Cleanup: kill only the suppressed instance THIS script started (scoped to APP_PID).
APP_PID=""
cleanup() { [ -n "$APP_PID" ] && kill -9 "$APP_PID" >/dev/null 2>&1 && echo "✓ suppressed instance stopped"; }
trap cleanup EXIT

run_part_a() {
  if [ "$BUILD" = 1 ]; then
    echo "building jar…"
    ./mvnw -q -DskipTests -Dspotless.check.skip=true package || { echo "✗ build failed"; exit 1; }
  fi
  # Secrets (Adzuna keys, etc.) so Spring resolves ${ADZUNA_APP_ID}/${ADZUNA_APP_KEY}.
  [ -f .env ] && { set -a; . ./.env; set +a; }

  local log
  log="$(mktemp -t fullaudit)"
  echo "starting suppressed in-mem instance on :$PORT (no email, no live-DB touch)…"
  nohup java -jar "$JAR" --server.port="$PORT" \
    --spring.datasource.url="jdbc:h2:mem:fullaudit;DB_CLOSE_DELAY=-1" \
    --spring.jpa.hibernate.ddl-auto=create-drop \
    --job.notification.scan.cron="$NEVER" --job.summary.cron="$NEVER" \
    --job.poll.cron="$NEVER" --job.adzuna.throttle-minutes=0 > "$log" 2>&1 &
  APP_PID=$!
  local ok=0
  for _ in $(seq 1 120); do
    if grep -q "Started ScmJobNotifierApplication" "$log" 2>/dev/null; then ok=1; echo "✓ started"; break; fi
    if grep -qi "APPLICATION FAILED TO START" "$log" 2>/dev/null; then echo "✗ startup failed"; tail -20 "$log"; return 1; fi
    sleep 1
  done
  [ "$ok" = 1 ] || { echo "✗ startup timed out"; tail -20 "$log"; return 1; }

  echo; echo "===== PART A — pre-filter audit (${SCOPE:-ALL scrapers}); several min ====="
  local url="http://localhost:$PORT/api/test/filter-audit"
  [ -n "$SCOPE" ] && url="$url?$SCOPE"
  curl -s -m 1800 -X POST "$url" -o /tmp/full-audit-result.json -w "  endpoint HTTP %{http_code}\n"
  python3 -c "import json;d=json.load(open('/tmp/full-audit-result.json'));print('  companiesAudited=%s totalJobs=%s'%(d.get('companiesAudited'),d.get('totalJobs')));u=d.get('unmatchedCompanies');print('  unmatched=',u) if u else None;print('  byDisposition=',d.get('byDisposition'))" 2>/dev/null
  echo
  python3 scripts/analyze-filter-audit.py filter-audit.csv
}

run_part_b() {
  echo; echo "===== PART B — Gemini audit (persisted decisions from data/jobs) ====="
  local h2
  h2=$(find ~/.m2 -name 'h2-*.jar' 2>/dev/null | grep -v sources | head -1)
  if [ -z "$h2" ] || [ ! -f data/jobs.mv.db ]; then
    echo "  (skipped — no H2 jar or no data/jobs.mv.db)"; return
  fi
  java -cp "$h2" org.h2.tools.Shell \
    -url "jdbc:h2:file:$(pwd)/data/jobs;AUTO_SERVER=TRUE;IFEXISTS=TRUE" -user sa -password "" -sql \
    "CALL CSVWRITE('$(pwd)/gemini-audit.csv', 'SELECT title, company, location, level, notified, source FROM job_posting');" \
    >/dev/null 2>&1 \
    && python3 scripts/analyze-gemini-audit.py gemini-audit.csv \
    || echo "  (Part B export failed — DB locked exclusively? try again, or stop the live app)"
}

case "$PART" in
  a) run_part_a ;;
  b) run_part_b ;;
  ab) run_part_a; run_part_b ;;
esac
echo; echo "===== full audit complete ====="
