# Worker sessions

Active workers and reviewers (see CLAUDE.md "Workers" / "Review"). Claude subagents (Sonnet/Haiku implement, Opus reviews) are preferred; Codex is a fallback. Always pass `model` explicitly when dispatching.
Delete a Codex session with `codex delete --force <id>` when its context is no longer needed, and remove its row.

| Session ID | Model / effort | Purpose | Status |
|------------|----------------|---------|--------|
| (none) | | | `queue` (DESIGN 5.4, tasks 1-3) finished and merged into dev (d1fc25d). Worktrees `../ModdedFiles-wt/{tabs,preview,queue}` (branches `w/tabs`, `w/preview`, `w/queue`) can be removed. No Codex sessions. |

Launcher (Codex fallback): `scripts/codex-worker.sh` (start/resume). Prompts and logs: `worker-logs/` (git-ignored).
Run at most 1 worker at a time (2 only if needed). Workers each use their own git worktree under `E:/Projects/ModdedFiles-wt/` to avoid build conflicts; Claude merges branches into `dev` after review.

Pitfalls:
- `codex exec resume` has no `-C`; the launcher `cd`s into the worktree. Don't edit the launcher while a worker runs (bash re-reads it).
- Workers leave Gradle daemons running as the sandbox user in the shared `~/.gradle`; if a local build fails with AccessDeniedException, use `bash scripts/build.sh --no-daemon`.
