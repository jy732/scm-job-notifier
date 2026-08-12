---
name: app
description: Start, stop, restart, or check status of the scm-job-notifier Spring Boot app (the CA SCM job poller/emailer). Use when the user says things like "start the app", "stop the app", "restart the app", or "is the app running". Argument selects the action: start | stop | restart | status | logs.
---

# app — scm-job-notifier lifecycle

Manage the local Spring Boot app via the helper script `scripts/app.sh`.

## What to do

Run the helper with the action from the user's argument (`$ARGUMENTS`), defaulting to `status` if none was given:

```
bash scripts/app.sh <action>
```

Valid actions: `start` · `stop` · `restart` · `status` · `logs`.

- **start** — rebuilds the jar (`./mvnw -DskipTests package`) so it runs the latest code, launches it detached with polling enabled, and waits for startup. Reports the configured email recipient.
- **stop** — kills the running instance.
- **restart** — stop, then start (use this to apply code/template changes).
- **status** — whether it's running, plus the last poll-complete and email-sent log lines.
- **logs** — tail the live log (`app.log`).

Then relay the script's output (running/stopped, pid, last poll/email lines).

## Important

- A **running app sends REAL alert emails** to the configured `NOTIFICATION_EMAIL` every poll cycle. Only `start` / `restart` when the user has OK'd a live run — if it's ambiguous, confirm first.
- The app polls every 15 min and scans/sends every 5 min once started; it keeps running until `stop`.
- To trigger a poll immediately after starting (instead of waiting for the 15-min cron): `curl -s -X POST http://localhost:8081/api/test/poll`.
- To send a template test email without a poll (goes to the hardcoded test address, not the real recipient): `curl -s -X POST http://localhost:8081/api/test/email`.
