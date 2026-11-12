# CLAUDE.md — FilesPlus

Morphe patch bundle that adds features to **Files by Google** (`com.google.android.apps.nbu.files`) without changing its UI.
Design: `docs/DESIGN.md` (source of truth, Japanese). Build/apply procedure: `docs/BUILD_PROCEDURE.md`.
Talk to the user in Japanese.

## User environment (do not assume otherwise)
- Device: OPPO A54 5G (Snapdragon 480, arm64, ColorOS). **Non-root. Avoid adb.** Diagnostics must be in-app ("Mod 設定 → 診断").
- Files is a **system app** on the device → the patched app must use a different package name.
- Patches are applied on the phone with **Morphe Manager**; remote patch source = this GitHub repo.
- Keep the original UI design. New UI only as items in existing menus, long-press, or on-demand bottom sheets.
## Repository / release
- GitHub: https://github.com/yak9909/FilesPlus (public). Use `gh` for GitHub operations (account `yak9909`).
- Work on `dev`; merge `dev` → `main` for stable (no squash). Semantic commits: `feat:` / `fix:` create releases (CI `release.yml`), `chore:` does not.
- Never hand-edit generated files: `patches-list.json`, `patches-bundle.json`, `CHANGELOG.md`.
- Build: `./gradlew buildAndroid` → `patches/build/libs/patches-*.mpp` (needs JDK 21: `C:\Program Files\Java\jdk-21.0.3`; jdk-26 breaks tools; Android SDK; GitHub token with `read:packages`).

## Local files (git-ignored)
- The project was renamed ModdedFiles → **FilesPlus** (2026-11-11). The local folder is still `E:/Projects/ModdedFiles` (renaming it would break Claude's session/memory path); worker worktrees go under `E:/Projects/ModdedFiles-wt/`.
- `original/` — original APKM (Files 1.13157.1011540316.0-release, versionCode 2081362, armeabi-v7a).
- `analysis/jadx/` — jadx 1.5.6 decompiled sources (`sources/defpackage/*` = obfuscated app code).
- `analysis/apktool/` — apktool 3.0.3 decoded resources/manifest (`res/`, `AndroidManifest.xml`).
- `analysis/tools/` — jadx, apktool.jar.
- Obfuscated names (e.g. `kwd`, `jpx`, `kxo`, `imh`) are valid **only for versionCode 2081362**. In patches, locate code with Morphe fingerprints (strings, resource IDs, unobfuscated classes), never by obfuscated names.

## Workers: Claude subagents first (since 2026-11-11, user instruction)
Roles:
- **Main session = Opus 5.5** (since 2026-11-12, user instruction): coordination — design, task breakdown, dispatch, build/verify, integration — and reviews. Design decisions go into `docs/DESIGN.md` (the spec; do not move it to `docs/superpowers/specs/`). Workers stay Sonnet (Haiku only for exact-edit chores).
- **Workers implement. Prefer Claude subagents** (Agent tool; **always pass `model` explicitly**):
  - `haiku` (Haiku 5.5) — only when the brief can state the exact edits (renames, boilerplate, resource XML, repetitive edits). Haiku takes more turns on judgment work.
  - `sonnet` (Sonnet 5.5) — everything else (patch logic, fingerprints, smali, Android code).
  - Effort: main session decides per task (`effort: low|medium|high|xhigh`).
  - Brief them with `docs/WORKER_BRIEF.md` + the task prompt (English). They work in a worktree under `E:/Projects/ModdedFiles-wt/<name>` (branch `w/<name>`, location is our declared preference), do not commit, and reply with changed files + a short summary. Hand large inputs over as files, not pasted text.
  - Continue a running/finished subagent with SendMessage when its context is still useful; otherwise start a new one. After 3 failed fix rounds, start a fresh implementer on a stronger model.
- **Codex workers are a fallback only** (e.g. when Claude usage is tight): the Codex plugin agent `codex:codex-rescue`, or our CLI launcher (procedure below). Keep the Codex stop-time review gate (`codex:setup`) OFF.
- If a task is light enough that doing it directly costs fewer tokens than briefing a worker, the main session does it itself.

Process (superpowers skills are installed; user rules below override the skills):
- New feature with unclear requirements → `superpowers:brainstorming` (short). Items already specified in `DESIGN.md` skip it.
- Multi-task feature (e.g. §5.2, SMB, libVLC) → `superpowers:writing-plans`, saved to `docs/plans/YYYY-MM-DD-<name>.md` (user location). Keep plans small: tasks, files, interfaces — not code transcripts. Single small tasks only need a task prompt.
- Execution → `superpowers:subagent-driven-development` principles: fresh implementer per task, one at a time. During a multi-task plan keep a ledger in `.superpowers/sdd/` (git-ignored) in addition to `docs/HANDOFF.md`.
- Completion → `superpowers:verification-before-completion`: never claim done/passing without fresh evidence (`bash scripts/build.sh --no-daemon` + `build/verify/` applying all patches to the original APK). Do not trust a worker's success report; check the diff.
- Not used: `planning-with-files` (overlaps HANDOFF.md/WORKERS.md; its hooks add noise).
- No unit tests exist yet. smali hooks are verified by build + patch application only. Pure-Java logic in extensions may get JVM tests later (undecided).

Review (separate reviewer subagent, per `superpowers:requesting-code-review`; the main session does not review diffs inline):
- Default reviewer model is **Opus 5.5** for per-task reviews and for the final whole-branch review. Use Sonnet only for trivial diffs (resource XML, strings) and for scoped re-reviews of small fix diffs.
- Hand the reviewer the diff as a file (`git diff` of the worktree, since workers do not commit), plus the task prompt/requirements. Do not pre-judge findings.
- Fix Critical/Important findings before merge by resuming the implementer (the main session does not fix them itself); note Minor ones in HANDOFF.md.
- Reviews cost usage: the usage-guard limits below always win — stop gracefully instead of downgrading review quality.

Usage limit (usage-guard, account-wide):
- Thresholds: **5-hour window 80%**, **weekly window 95%** (`usage_guard.py set --five-hour 80 --weekly 95`).
- When the guard notice (「【利用枠の見張り】…」) arrives: **graceful stop, not a hard kill** — start nothing new, let the running worker/build finish, reach a clean checkpoint (no half-applied edits; commit/merge only if reviewed), update `docs/HANDOFF.md`, and tell the user the progress and the next step. Continue only if the user says so.

Rules (all workers):
- **Communicate with workers in English** (prompts and requested replies). Ask for short replies: changed files + a summary, no full diffs.
- Track active workers (subagent name/ID or Codex session id, model, purpose, status) in `docs/WORKERS.md` so they survive `/clear`.
- Every worker result is reviewed (build + reviewer subagent, see Review above) before the main session commits it (`feat:`/`fix:`/`chore:` message; one commit per task so CI does not make extra releases).
- The usage guard and the user's rules override superpowers' "do not stop between tasks": stopping at the guard threshold, merge/push/release rules, and the user's batched on-device testing stay as written here.
- **Run at most 1 worker at a time** (the user's PC gets slow). 2 in parallel only when really needed. Never 3+.

Codex fallback:
- Models: `gpt-6-luna` (simple) / `gpt-6.1-sol` (other); effort via `-c model_reasoning_effort=...`.
- Use persistent sessions (no `--ephemeral`). Launcher: `scripts/codex-worker.sh start|resume ...`.
- Delete sessions no longer needed with `codex delete --force <SESSION_ID>`.

## Notes that must survive /clear
- Memory files may be lost when the session is cleared, so anything important lives here or in `docs/`.
- Key analysis findings and hook points are in `docs/DESIGN.md` §2 and per-feature sections.
- Third-party DocumentsProviders are NOT browsable inside Files (tapping launches the provider's app) → SMB uses its own browser (DESIGN §5.3).
