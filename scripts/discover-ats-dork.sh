#!/usr/bin/env bash
# Discover net-new SCM-hiring companies BY searching ATS domains directly.
#
# Unlike JSearch/Adzuna (which give an employer name you must then reverse-engineer the ATS
# for), a `site:{ats-domain} {SCM terms} California` search returns URLs whose path already
# CONTAINS the ATS token — so each hit is a company + platform + slug, ready to wire in.
#
# This script prints the raw candidate URLs per platform; extract the slug and dedupe against
# application.properties (the companion skill explains the wire-in step). It does NOT call any
# search engine itself (no key here) — it prints the exact queries to run, and if you paste
# result URLs into a file it will parse the slugs for you.
#
# Usage:
#   scripts/discover-ats-dork.sh queries              # print the dork queries to run
#   scripts/discover-ats-dork.sh parse <urls-file>    # extract platform+slug from pasted URLs,
#                                                       # flagging any already in config
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
PROPS=src/main/resources/application.properties

TERMS='"supply chain" OR procurement OR "materials planner" OR buyer OR sourcing OR "demand planner"'
LOC='California'
declare -a DOMAINS=(
  "boards.greenhouse.io"        # cleanest — real employers
  "jobs.lever.co"               # clean
  "jobs.ashbyhq.com"            # clean, startup-heavy
  "myworkdayjobs.com"           # biggest, subdomain=tenant in URL
  "icims.com"                   # subdomain in URL
  "jobs.smartrecruiters.com"    # NOISY — ~70% staffing agencies, down-weight
)

cmd="${1:-queries}"

if [ "$cmd" = "queries" ]; then
  echo "# Run each in WebSearch (allowed_domains=[the domain]); paste the result URLs into a file,"
  echo "# then: scripts/discover-ats-dork.sh parse <that-file>"
  echo
  for d in "${DOMAINS[@]}"; do
    printf 'site:%-28s %s %s\n' "$d" "$TERMS" "$LOC"
  done
  exit 0
fi

if [ "$cmd" = "parse" ]; then
  FILE="${2:?usage: discover-ats-dork.sh parse <urls-file>}"
  [ -f "$FILE" ] || { echo "no such file: $FILE"; exit 1; }
  echo "PLATFORM      SLUG/TENANT                    STATUS   (against $PROPS)"
  # Extract (platform, slug) from each known ATS URL shape.
  grep -oE 'https?://[^ ")]+' "$FILE" | while read -r url; do
    case "$url" in
      *boards.greenhouse.io/embed*for=*) slug=$(sed -E 's/.*for=([A-Za-z0-9_-]+).*/\1/' <<<"$url"); plat=greenhouse ;;
      *boards.greenhouse.io/*) slug=$(sed -E 's#.*boards.greenhouse.io/([A-Za-z0-9_-]+).*#\1#' <<<"$url"); plat=greenhouse ;;
      *jobs.lever.co/*)        slug=$(sed -E 's#.*jobs.lever.co/([A-Za-z0-9_-]+).*#\1#' <<<"$url"); plat=lever ;;
      *jobs.ashbyhq.com/*)     slug=$(sed -E 's#.*jobs.ashbyhq.com/([A-Za-z0-9_-]+).*#\1#' <<<"$url"); plat=ashby ;;
      *.myworkdayjobs.com/*)   slug=$(sed -E 's#https?://([A-Za-z0-9_-]+)\.wd[0-9]+\.myworkdayjobs\.com.*#\1#' <<<"$url"); plat=workday ;;
      *.icims.com/*)           slug=$(sed -E 's#https?://([A-Za-z0-9_-]+)\.icims\.com.*#\1#' <<<"$url"); plat=icims ;;
      *jobs.smartrecruiters.com/*) slug=$(sed -E 's#.*jobs.smartrecruiters.com/([A-Za-z0-9_-]+).*#\1#' <<<"$url"); plat=smartrecruiters ;;
      *) continue ;;
    esac
    [ -z "$slug" ] && continue
    if grep -qiF "$slug" "$PROPS"; then st="already-configured"; else st="NET-NEW ⭐"; fi
    printf '%-13s %-30s %s\n' "$plat" "$slug" "$st"
  done | sort -u
  echo
  echo "Next: for each NET-NEW, verify with scripts/ats-detect.sh (or the ATS API) that the board"
  echo "has real professional CA-SCM roles — dork hits include closed reqs and hourly-labor-only"
  echo "boards the pipeline filters out — then wire the good ones into $PROPS."
  exit 0
fi

echo "usage: discover-ats-dork.sh {queries|parse <urls-file>}"; exit 1
