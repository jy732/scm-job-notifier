#!/usr/bin/env bash
# ATS detector for the Adzuna -> direct-scraper migration.
#
# Given an employer + candidate slugs, probes every supported ATS with the
# RELIABLE method for each and prints config-ready results. Encodes the
# false-positive traps learned the hard way:
#
#   - SmartRecruiters wildcards: returns 200 with "totalFound":0 for ANY id
#     -> require totalFound > 0.
#   - Greenhouse / Lever / Ashby 404 on an unknown token -> a 200 with jobs is real,
#     but confirm job count > 0 (dead boards return 200 with 0).
#   - Workday: the myworkdayjobs edge answers 406 for ANY host, so a resolving host
#     proves nothing. Instead probe /wday/cxs/{tenant}/ZZNOSITE/jobs:
#       404 = tenant exists on that shard (site just wrong); 422 = no such tenant.
#     Then read the real site ids from {tenant}.wdN.myworkdayjobs.com/sitemap.xml.
#   - iCIMS wildcards generic subdomains (~3 KB placeholder page) -> require a large
#     page with many real job markers, not just HTTP 200.
#
# Usage: scripts/ats-detect.sh "Employer Name" [slug1,slug2,...]
#   Slugs default to the name lowercased/alnum-only if omitted. Pass several
#   comma-separated candidates for parent-branded tenants (e.g. airgas,airliquidehr).
#
# Run with bash (macOS default bash 3.2 is fine): bash scripts/ats-detect.sh ...
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/537.36"
NAME="${1:?usage: ats-detect.sh \"Employer Name\" [slug1,slug2,...]}"
norm(){ echo "$1" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9'; }
SLUGS="${2:-$(norm "$NAME")}"
IFS=',' read -ra CAND <<< "$SLUGS"
echo "== ATS detection: $NAME  (slugs: $SLUGS) =="
HIT=0

# --- Greenhouse / Lever / Ashby / SmartRecruiters (public slug APIs) ---
for s in "${CAND[@]}"; do
  n=$(curl -s -m 8 "https://boards-api.greenhouse.io/v1/boards/$s/jobs" | grep -oE '"id":[0-9]+' | wc -l | tr -d ' ')
  [ "${n:-0}" -gt 0 ] && { echo "GREENHOUSE       token=$s  jobs=$n"; HIT=1; }
  n=$(curl -s -m 8 "https://api.lever.co/v0/postings/$s?mode=json" | grep -oE '"id":"' | wc -l | tr -d ' ')
  [ "${n:-0}" -gt 0 ] && { echo "LEVER            token=$s  jobs=$n"; HIT=1; }
  n=$(curl -s -m 8 "https://api.ashbyhq.com/posting-api/job-board/$s" | grep -oE '"id":"' | wc -l | tr -d ' ')
  [ "${n:-0}" -gt 0 ] && { echo "ASHBY            token=$s  jobs=$n"; HIT=1; }
  tf=$(curl -s -m 8 "https://api.smartrecruiters.com/v1/companies/$s/postings" | grep -oE '"totalFound":[0-9]+' | head -1 | cut -d: -f2)
  [ -n "$tf" ] && [ "$tf" -gt 0 ] && { echo "SMARTRECRUITERS  id=$s  totalFound=$tf"; HIT=1; }
done

# --- Workday (CXS 404 discriminator + sitemap.xml site discovery) ---
for s in "${CAND[@]}"; do
  for wdn in 1 2 3 5 10 12 101 103; do
    code=$(curl -s -m 6 -o /dev/null -w '%{http_code}' -X POST \
      "https://$s.wd$wdn.myworkdayjobs.com/wday/cxs/$s/ZZNOSITE/jobs" \
      -H 'Content-Type: application/json' \
      --data-raw '{"limit":1,"offset":0,"appliedFacets":{},"searchText":""}')
    if [ "$code" = "404" ]; then
      sites=$(curl -sL -m 8 -A "$UA" "https://$s.wd$wdn.myworkdayjobs.com/sitemap.xml" \
        | grep -oiE "$s\.wd$wdn\.myworkdayjobs\.com/[a-zA-Z0-9_%-]+" | sed -E 's#.*/##' | sort -u | tr '\n' ' ')
      echo "WORKDAY          subdomain=$s instance=$wdn  sites: ${sites:-<none in sitemap; scrape careers page>}"
      echo "                 (confirm a site: POST /wday/cxs/$s/<SITE>/jobs -> read \"total\")"
      HIT=1; break
    fi
  done
done

# --- iCIMS (content-verified; the host wildcards) ---
for s in "${CAND[@]}"; do
  for host in "careers-$s" "$s" "$s-careers"; do
    b=$(curl -sL -m 8 -A "$UA" "https://$host.icims.com/jobs/search?pr=0&in_iframe=1")
    markers=$(echo "$b" | grep -oiE 'jobs/[0-9]+/[a-z0-9-]+/job' | wc -l | tr -d ' ')
    if [ "${#b}" -gt 20000 ] && [ "${markers:-0}" -gt 20 ]; then
      echo "iCIMS            subdomain=$host  jobMarkers=$markers"; HIT=1; break
    fi
  done
done

# --- Static careers-page markers (flags UNSUPPORTED ATSes so you don't chase them) ---
for s in "${CAND[@]}"; do
  for u in "https://careers.$s.com/" "https://jobs.$s.com/" "https://www.$s.com/careers"; do
    blob=$(curl -sL -m 10 -A "$UA" "$u" 2>/dev/null | head -c 300000)
    [ -z "$blob" ] && continue
    for pat in phenompeople:Phenom brassring:Brassring kenexa:Brassring \
               workforcenow.adp:ADP oraclecloud:Oracle taleo:Oracle \
               eightfold:Eightfold successfactors:SuccessFactors avature:Avature; do
      echo "$blob" | grep -qi "${pat%%:*}" && echo "NOTE  $u -> ${pat#*:} marker (NO adapter — not migratable)"
    done
    break
  done
done

[ "$HIT" = 0 ] && echo "-> no supported ATS via slug/CXS/iCIMS. Likely a JS-SPA needing Playwright" \
                       "network-capture, or an unsupported ATS (Oracle/Avature/Phenom/Eightfold)."
