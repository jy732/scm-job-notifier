---
name: phase-update
description: Draft a user-facing "phase update" email (friendly Chinese, README voice) from the git changes since the last update, show it for review, and — only after the user approves — send it via the announcement endpoint. Use when the user says things like "phase update", "announce the changes", "tell the user what's new", "send a product update".
---

# phase-update — announce product changes to the email recipient

Turns engineering changes into a short, non-engineer "what's new" email in Chinese — warm &
playful casual net-speak (大保健 / 搬砖 / 又双叒叕 / 就酱 vibe, light emoji), addressed to
「小严同学」 and signed 「本喵」. NOT heavy Beijing dialect, but NOT dry/formal either — match the
reference below. Sends through the reusable announcement endpoint after the user reviews.

## 1. Gather the changes since the last update
The cut-off is **self-tracked** by the `last-phase-update` git tag (moved on every send, step 4) and
logged in `docs/phase-updates.md`. Diff from it — no need to ask the user for a range:
```
git log --oneline last-phase-update..HEAD        # everything unannounced
```
- If the tag is missing (fresh clone), fall back to asking / the last ~N commits, and seed it:
  `git tag -f last-phase-update <last-announced-commit>`.
- **Keep** only what a job-seeker would notice: new companies/sources, cleaner results, fixed
  missed-jobs, email/inbox behavior, new role types.
- **Drop** internal noise: refactors, test scaffolding, skills, docs, audits, config-only migrations
  (summarize many company adds as "more companies", don't list slugs).

## 2. Draft the copy (Chinese, README voice)
- **Subject** — the user's preferred style: `📮 本喵升级公告 🐱✨`
- **Body (HTML)** — `<h2>更新了点东西 ✨</h2>` + a one-line intro + a `<ul>` of 3–6 bullets, each
  `<li><b>一句话好处</b>……</li>`, then a short sign-off. Translate each change into the *benefit*
  ("盯的公司更多、也更准了"), not the implementation. Keep it warm and plain, like the reader README.

**Canonical reference (the user's approved voice — match this):**

> **主题:** `📮 本喵升级公告 🐱✨`
>
> 嗨，小严同学：
>
> 本喵最近偷偷优化了好几轮，捞工作更靠谱了。这阵子的变化 👇
>
> **1. 盯的公司更多、也更准了 👀** 从第三方「二手」渠道迁到 100 多家公司的官方招聘页，信息更新鲜、更全（大厂和加州本地「小而美」都有）。
>
> **2. 「搬砖」的活儿筛掉了 💪→🚫** 仓库、叉车、理货这类纯体力活一律挡掉，只留正经的白领供应链岗。
>
> **3. 修了个 bug，捞回一大批漏掉的岗位 🐟** 之前有几家大厂只扒到第一页，后面成百上千个岗位全漏了，现在补齐了。
>
> **4. 邮件不再挤成一坨了 📬** 每封都带时间戳，各回各家，不再被 Gmail 折叠。
>
> **5. 新收录了「排产计划」这类岗 🗓️** Master / Production Scheduler 之前漏了，现在也抓上来了。
>
> **6. 每日汇总换了张新头图 🖼️** 顺手给汇总邮件换了张新头像（喵～）。
>
> —— 你的供应链捞人小助手 本喵

## 3. Show it for review — WAIT for approval
Render the subject + body plainly and ask the user to approve or edit. **Do not send yet.**

## 4. Send (only after approval) via the announcement endpoint
The endpoint is reusable — the copy goes in the request body (not in code). Write the payload to a
file to avoid shell-escaping the Chinese/HTML, then POST:
```
cat > /tmp/announce.json <<'JSON'
{"subject":"📮 本喵升级公告 · …","html":"<h2>…</h2>…"}
JSON
# preview: no "to" → defaults to the hardcoded test address (jy63@illinois.edu)
curl -s -X POST http://localhost:8081/api/test/announcement \
  -H 'Content-Type: application/json' -d @/tmp/announce.json
```
- **Preview first** to the test address (omit `to`). Confirm it looks right (mascot image + copy).
- **Send to the real recipient ONLY on the user's explicit OK** — add `"to":"<NOTIFICATION_EMAIL>"`
  to the JSON. Same safety rule as alerts: never auto-send to the real inbox.

## 5. Record the send — move the marker (only after the real send succeeds)
So the next run's `last-phase-update..HEAD` diff is exact (no double-send, no misses):
```
git tag -f last-phase-update HEAD        # advance the cut-off to what was just announced
```
Then prepend a dated entry to `docs/phase-updates.md` (marker commit SHA, recipient, one-line gist
of the bullets sent) and commit both the tag-move note + the changelog. Do this **only** after the
approved send to the real recipient — not for a test-address preview.

## Notes
- Needs the app running (endpoint on :8081). If it's down, start it via `/app` (or a suppressed
  instance) — but avoid unnecessary restarts; prefer sending when it's already up.
- The email reuses the working-Kitty mascot header (brand), with your reviewed copy as the body.
- Subject carries no timestamp, but announcements are infrequent so Gmail threading isn't a concern.
