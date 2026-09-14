#!/usr/bin/env bash
# Validate persisted job-posting URLs — catch broken URL patterns (soft-404s) per company.
#
# Samples the most recent N URLs per company from H2, fetches each, and title-matches the
# job against the fetched page. A company showing "ALL DEAD" means its scraper is building
# the wrong URL (the Ross Stores /job/{ref} vs /search/jobdetails/{slug}/{uuid} bug), which
# an HTTP-status check alone would miss because these sites soft-404 (200 + "Page Not Found").
#
# Reads the DB over AUTO_SERVER, so it works WHILE the app is running (read-only; no lock
# conflict, no need to stop the app).
#
# Usage:
#   scripts/validate-job-links.sh                 # 5 most-recent links for every company
#   scripts/validate-job-links.sh rossstores      # just one company
#   scripts/validate-job-links.sh "rossstores|meta" 10   # filter + sample size
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

FILTER="${1:-}"           # optional company regexp (H2 REGEXP_LIKE)
SAMPLE="${2:-5}"          # links per company
CSV="/tmp/validate-job-links.$$.csv"
rm -f "$CSV"

H2=$(find ~/.m2 -name 'h2-*.jar' | grep -v sources | head -1)
[ -z "$H2" ] && { echo "✗ no H2 jar in ~/.m2 (run a build first)"; exit 1; }

# NB: this WHERE is embedded inside CSVWRITE's single-quoted SQL-string argument, so any
# literal quote must be DOUBLED. We use LENGTH(url)>0 (quote-free) for the empty check, and
# double the filter's quotes to ''...''.
WHERE="url IS NOT NULL AND LENGTH(url) > 0"
[ -n "$FILTER" ] && WHERE="$WHERE AND REGEXP_LIKE(company, ''$FILTER'')"

# CSVWRITE runs server-side; window function gives the N newest per company.
SQL="CALL CSVWRITE('$CSV', 'SELECT company, title, url FROM ( SELECT company, title, url, ROW_NUMBER() OVER (PARTITION BY company ORDER BY detected_at DESC) rn FROM job_posting WHERE $WHERE ) WHERE rn <= $SAMPLE ORDER BY company');"

java -cp "$H2" org.h2.tools.Shell \
  -url "jdbc:h2:file:$(pwd)/data/jobs;AUTO_SERVER=TRUE;IFEXISTS=TRUE" \
  -user sa -password "" -sql "$SQL" >/dev/null 2>&1 \
  || { echo "✗ H2 query failed (is ./data/jobs present?)"; exit 1; }

[ -s "$CSV" ] || { echo "no matching job rows"; rm -f "$CSV"; exit 0; }
python3 scripts/validate_job_links.py "$CSV"
rc=$?
rm -f "$CSV"
exit $rc
