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
# Env: NO_BUILD=1 skips the Maven build and launches the last staged run/ jar (use when the
#      toolchain is broken but you need the app up; the banner prints that jar's build time).
#
# NOTE: a running app sends REAL alert emails to the configured NOTIFICATION_EMAIL.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

# We launch from a STABLE COPY of the jar (run/), never the build artifact in target/.
# Why: Jakarta Mail resolves its StreamProvider lazily via ServiceLoader on every send,
# re-reading META-INF/services from the on-disk jar. If a later `mvn package` overwrites
# target/*.jar under the running JVM, that lazy read hits NoSuchFileException and EVERY
# alert email fails ("Cannot load interface jakarta.mail.util.StreamProvider"). Running
# from run/ (outside target/, survives `mvn clean package`) keeps the live jar intact.
BUILD_JAR="target/scm-job-notifier-0.0.1-SNAPSHOT.jar"
RUN_JAR="run/scm-job-notifier.jar"
LOG="app.log"
PATTERN="scm-job-notifier.*jar"

is_running() { pgrep -f "$PATTERN" >/dev/null 2>&1; }

# Resolve a RUNNABLE JDK and export JAVA_HOME for both java and mvn.
# Why this exists: the macOS 27 upgrade (2026-09-22) left this Mac with only an x86_64 JDK
# (Microsoft OpenJDK 21) while the machine is arm64, and Rosetta no longer runs it — so `java`
# and `./mvnw` both died with "Bad CPU type in executable" and the app could not start at all.
# We therefore pick the first JDK whose `java -version` actually executes, preferring an
# architecture match, instead of trusting whatever JAVA_HOME/java_home points at.
resolve_java() {
  local candidates=() c
  [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME")
  # Homebrew (arm64 on Apple silicon): versioned first, then the generic keg.
  for c in /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk; do
    [ -d "$c/libexec/openjdk.jdk/Contents/Home" ] && candidates+=("$c/libexec/openjdk.jdk/Contents/Home")
  done
  for c in /opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home; do
    [ -d "$c" ] && candidates+=("$c")
  done
  # Whatever macOS itself knows about (often the x86 one here — tried last, and still verified).
  c=$(/usr/libexec/java_home 2>/dev/null) && [ -n "$c" ] && candidates+=("$c")
  for c in "${candidates[@]}"; do
    if [ -x "$c/bin/java" ] && "$c/bin/java" -version >/dev/null 2>&1; then
      export JAVA_HOME="$c"
      export PATH="$c/bin:$PATH"
      return 0
    fi
  done
  echo "✗ no runnable JDK found (tried: ${candidates[*]:-none})"
  echo "  On Apple silicon install an arm64 build, e.g.: brew install openjdk@21"
  return 1
}

start() {
  if is_running; then echo "already running (pid $(pgrep -f "$PATTERN" | tr '\n' ' '))"; return 0; fi
  resolve_java || return 1
  echo "java: $JAVA_HOME"
  if [ "${NO_BUILD:-0}" = "1" ]; then
    # Escape hatch for a broken toolchain: run the last staged jar as-is. It may predate your
    # working tree, so the banner says what it is rather than pretending the code is current.
    [ -f "$RUN_JAR" ] || { echo "✗ NO_BUILD=1 but $RUN_JAR does not exist"; return 1; }
    echo "⚠ NO_BUILD=1 — skipping build, launching staged jar from $(date -r "$RUN_JAR" '+%Y-%m-%d %H:%M')"
  else
    echo "building jar…"
    ./mvnw -q -DskipTests package || { echo "✗ build failed"; return 1; }
  fi
  # Copy to a stable path so a future rebuild can't corrupt this running JVM's jar.
  if [ "${NO_BUILD:-0}" != "1" ]; then
    mkdir -p "$(dirname "$RUN_JAR")"
    cp -f "$BUILD_JAR" "$RUN_JAR" || { echo "✗ could not stage $RUN_JAR"; return 1; }
  fi
  # Load .env so config (GEMINI_API_KEY, EMAIL_*, ADZUNA_*, NOTIFICATION_EMAIL…) is present
  # regardless of the launching shell — otherwise a bare/cron/launchd context starts the app
  # with keys unset (e.g. "GEMINI API KEY NOT CONFIGURED"). .env is gitignored; never commit it.
  if [ -f .env ]; then
    set -a; . ./.env; set +a
    echo "loaded .env ($(grep -cE '^\s*(export\s+)?[A-Za-z_][A-Za-z0-9_]*=' .env) vars)"
  else
    echo "⚠ no .env found — app will start with keys unset (Gemini/email/Adzuna disabled)"
  fi
  nohup java -jar "$RUN_JAR" > "$LOG" 2>&1 &
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
