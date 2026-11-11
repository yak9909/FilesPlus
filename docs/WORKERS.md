# Codex worker sessions

Active Codex CLI sessions (see CLAUDE.md "Working with Codex CLI workers").
Delete a session with `codex delete --force <id>` when its context is no longer needed, and remove its row.

| Session ID | Model / effort | Purpose | Status |
|------------|----------------|---------|--------|
| 01a1c8a6-b6d8-72a3-b155-9b8db5ce9f57 | gpt-6.1-sol / high | `tabs`: folder-tab context + delete folder (DESIGN 5.7/5.8), worktree `../ModdedFiles-wt/tabs`, branch `w/tabs` | running (started 2026-11-11) |

Launcher: `scripts/codex-worker.sh` (start/resume). Prompts and logs: `worker-logs/` (git-ignored).
Run at most 1 worker at a time (2 only if needed). Workers each use their own git worktree under `E:/Projects/ModdedFiles-wt/` to avoid build conflicts; Claude merges branches into `dev` after review.

Pitfalls:
- `codex exec resume` has no `-C`; the launcher `cd`s into the worktree. Don't edit the launcher while a worker runs (bash re-reads it).
- Workers leave Gradle daemons running as the sandbox user in the shared `~/.gradle`; if a local build fails with AccessDeniedException, use `bash scripts/build.sh --no-daemon`.
