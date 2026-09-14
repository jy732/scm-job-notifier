#!/usr/bin/env bash
# Exhaustive, FREE ATS census — enumerate every company on an ATS, probe each board, rank
# net-new employers by CA-SCM role count. The no-cost version of BuiltWith/TheirStack: it uses
# the same underlying data (Common Crawl for path-based ATS, crt.sh cert-transparency for
# subdomain-based) and filters straight from live job data instead of paying for firmographics.
#
# Pilot supports GREENHOUSE. Run it as an OFFLINE periodic sweep, not inline with the poll —
# enumeration yields thousands of boards and probing them is a rate-limited batch.
#
# Usage:
#   scripts/discover-ats-census.sh greenhouse                 # full sweep
#   scripts/discover-ats-census.sh greenhouse --limit-probe 300   # quick sample
#
# See the discover-ats-dork skill for the lighter search-engine version, and migrate-companies
# for wiring the winners into application.properties.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
exec python3 scripts/discover_ats_census.py "$@"
