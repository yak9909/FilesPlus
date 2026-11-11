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
- **Claude (Opus 5.5, main session)**: design, task breakdown, review, and integration. Design decisions go into `docs/DESIGN.md`.
- **Workers implement. Prefer Claude subagents** (Agent tool, `model` override):
  - `haiku` (Haiku 5.5) — simple/mechanical work (renames, boilerplate, resource XML, repetitive edits).
  - `sonnet` (Sonnet 5.5) — everything else (patch logic, fingerprints, smali, Android code).
  - Effort: Claude (main) decides per task (`effort: low|medium|high|xhigh`).
  - Brief them with `docs/WORKER_BRIEF.md` + the task prompt (English). They work in a worktree under `E:/Projects/ModdedFiles-wt/<name>` (branch `w/<name>`), do not commit, and reply with changed files + a short summary.
  - Continue a running/finished subagent with SendMessage when its context is still useful; otherwise start a new one.
- **Codex CLI workers are a fallback only** (e.g. when Claude usage is tight). Procedure below.
- If a task is light enough that doing it directly costs fewer tokens than briefing a worker, Claude does it itself.

Usage limit (usage-guard, account-wide):
- Watch **only the 5-hour window**; weekly window is not watched. Threshold **80%** (`usage_guard.py set --five-hour 80 --weekly off`).
- When the guard notice (「【利用枠の見張り】…」) arrives: **graceful stop, not a hard kill** — start nothing new, let the running worker/build finish, reach a clean checkpoint (no half-applied edits; commit/merge only if reviewed), update `docs/HANDOFF.md`, and tell the user the progress and the next step. Continue only if the user says so.

Rules (all workers):
- **Communicate with workers in English** (prompts and requested replies). Ask for short replies: changed files + a summary, no full diffs.
- Track active workers (subagent name/ID or Codex session id, model, purpose, status) in `docs/WORKERS.md` so they survive `/clear`.
- Claude reviews every worker result (build + diff review) before committing.
- **Run at most 1 worker at a time** (the user's PC gets slow). 2 in parallel only when really needed. Never 3+.

Codex fallback:
- Models: `gpt-6-luna` (simple) / `gpt-6.1-sol` (other); effort via `-c model_reasoning_effort=...`.
- Use persistent sessions (no `--ephemeral`). Launcher: `scripts/codex-worker.sh start|resume ...`.
- Delete sessions no longer needed with `codex delete --force <SESSION_ID>`.

## Notes that must survive /clear
- Memory files may be lost when the session is cleared, so anything important lives here or in `docs/`.
- Key analysis findings and hook points are in `docs/DESIGN.md` §2 and per-feature sections.
- Third-party DocumentsProviders are NOT browsable inside Files (tapping launches the provider's app) → SMB uses its own browser (DESIGN §5.3).
