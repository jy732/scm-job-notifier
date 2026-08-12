#!/usr/bin/env bash
# Lifecycle helper for the scm-job-notifier Spring Boot app.
# Usage: scripts/app.sh {start|stop|restart|status|logs}
#
#   start    build the jar + launch detached (polling enabled), wait for startup
#   stop     kill the running instance
#   restart  stop, then start (rebuilds → applies latest code)
#   status   is it running? + last poll / email lines
#   logs     tail the live log
#
# NOTE: a running app sends REAL alert emails to the configured NOTIFICATION_EMAIL.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

JAR="target/scm-job-notifier-0.0.1-SNAPSHOT.jar"
LOG="app.log"
PATTERN="scm-job-notifier-0.0.1-SNAPSHOT.jar"

is_running() { pgrep -f "$PATTERN" >/dev/null 2>&1; }

start() {
  if is_running; then echo "already running (pid $(pgrep -f "$PATTERN" | tr '\n' ' '))"; return 0; fi
  echo "building jar…"
  ./mvnw -q -DskipTests package || { echo "✗ build failed"; return 1; }
  nohup java -jar "$JAR" > "$LOG" 2>&1 &
  echo "starting (pid $!) → log: $LOG"
  for _ in $(seq 1 90); do
    grep -q "Started ScmJobNotifierApplication" "$LOG" 2>/dev/null && { echo "✓ started"; grep -i "Email configured" "$LOG" | tail -1; return 0; }
    grep -qi "APPLICATION FAILED TO START\|Exception" "$LOG" 2>/dev/null && { echo "✗ startup error — see $LOG"; tail -5 "$LOG"; return 1; }
    sleep 1
  done
  echo "⚠ startup not confirmed in 90s — check $LOG"; return 1
}

stop() {
  if is_running; then pkill -9 -f "$PATTERN"; sleep 1; echo "✓ stopped"; else echo "not running"; fi
}

status() {
  if is_running; then
    echo "RUNNING (pid $(pgrep -f "$PATTERN" | tr '\n' ' '))"
    grep "POLL CYCLE COMPLETE" "$LOG" 2>/dev/null | tail -1 || true
    grep -iE "Alert SENT|Email SENT successfully" "$LOG" 2>/dev/null | tail -1 || true
  else
    echo "STOPPED"
  fi
}

case "${1:-status}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; start ;;
  status)  status ;;
  logs)    tail -n "${2:-40}" -f "$LOG" ;;
  *) echo "usage: scripts/app.sh {start|stop|restart|status|logs}"; exit 1 ;;
esac
