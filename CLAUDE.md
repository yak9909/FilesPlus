# CLAUDE.md — ModdedFiles

Morphe patch bundle that adds features to **Files by Google** (`com.google.android.apps.nbu.files`) without changing its UI.
Design: `docs/DESIGN.md` (source of truth, Japanese). Build/apply procedure: `docs/BUILD_PROCEDURE.md`.
Talk to the user in Japanese.

## User environment (do not assume otherwise)
- Device: OPPO A54 5G (Snapdragon 480, arm64, ColorOS). **Non-root. Avoid adb.** Diagnostics must be in-app ("Mod 設定 → 診断").
- Files is a **system app** on the device → the patched app must use a different package name.
- Patches are applied on the phone with **Morphe Manager**; remote patch source = this GitHub repo.
- Keep the original UI design. New UI only as items in existing menus, long-press, or on-demand bottom sheets.

## Repository / release
- GitHub: https://github.com/yak9909/ModdedFiles (public). Use `gh` for GitHub operations (account `yak9909`).
- Work on `dev`; merge `dev` → `main` for stable (no squash). Semantic commits: `feat:` / `fix:` create releases (CI `release.yml`), `chore:` does not.
- Never hand-edit generated files: `patches-list.json`, `patches-bundle.json`, `CHANGELOG.md`.
- Build: `./gradlew buildAndroid` → `patches/build/libs/patches-*.mpp` (needs JDK 21: `C:\Program Files\Java\jdk-21.0.3`; jdk-26 breaks tools; Android SDK; GitHub token with `read:packages`).

## Local files (git-ignored)
- `original/` — original APKM (Files 1.13157.1011540316.0-release, versionCode 2081362, armeabi-v7a).
- `analysis/jadx/` — jadx 1.5.6 decompiled sources (`sources/defpackage/*` = obfuscated app code).
- `analysis/apktool/` — apktool 3.0.3 decoded resources/manifest (`res/`, `AndroidManifest.xml`).
- `analysis/tools/` — jadx, apktool.jar.
- Obfuscated names (e.g. `kwd`, `jpx`, `kxo`, `imh`) are valid **only for versionCode 2081362**. In patches, locate code with Morphe fingerprints (strings, resource IDs, unobfuscated classes), never by obfuscated names.

## Working with Codex CLI workers (token-saving workflow)
Roles:
- **Claude (Opus 5.5)**: design, task breakdown, review, and integration. Design decisions go into `docs/DESIGN.md`.
- **Codex workers** implement. Choose the model per task:
  - `gpt-6-luna` — simple/mechanical work (renames, boilerplate, resource XML, repetitive edits).
  - `gpt-6.1-sol` — everything else (patch logic, fingerprints, smali, Android code).
  - Reasoning effort: Claude decides per task (`-c model_reasoning_effort=low|medium|high|xhigh`).
- If a task is light enough that doing it directly costs fewer tokens than briefing a worker, Claude does it itself.

Rules:
- **Communicate with workers in English** (prompts and requested replies). Ask for short replies: changed files + a summary, no full diffs.
- **Use normal (persistent) sessions, not ephemeral ones** — do not pass `--ephemeral`. Keep context by resuming:
  - start: `codex exec -m <model> -c model_reasoning_effort=<e> -s workspace-write -C E:/Projects/ModdedFiles "<prompt>"` (note the `session id` it prints)
  - continue: `codex exec resume <SESSION_ID> "<prompt>"`
- **When starting work that does not need the previous worker context, start a new session; if the old session is no longer needed, delete it** with `codex delete <SESSION_ID>`.
- Track active worker sessions in `docs/WORKERS.md` (id, model, purpose, status) so they survive `/clear`.
- Claude reviews every worker result (build + diff review) before committing.

## Notes that must survive /clear
- Memory files may be lost when the session is cleared, so anything important lives here or in `docs/`.
- Key analysis findings and hook points are in `docs/DESIGN.md` §2 and per-feature sections.
- Third-party DocumentsProviders are NOT browsable inside Files (tapping launches the provider's app) → SMB uses its own browser (DESIGN §5.3).
