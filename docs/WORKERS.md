# Worker sessions

Active workers (see CLAUDE.md "Workers"). Claude subagents (Sonnet/Haiku) are preferred; Codex is a fallback.
Delete a session with `codex delete --force <id>` when its context is no longer needed, and remove its row.

| Session ID | Model / effort | Purpose | Status |
|------------|----------------|---------|--------|
| (none) | | | Phase 2 finished 2026-11-11; Codex `tabs` session deleted. Worktree `../ModdedFiles-wt/tabs` (branch `w/tabs`) can be reused or removed. |

Launcher: `scripts/codex-worker.sh` (start/resume). Prompts and logs: `worker-logs/` (git-ignored).
Run at most 1 worker at a time (2 only if needed). Workers each use their own git worktree under `E:/Projects/ModdedFiles-wt/` to avoid build conflicts; Claude merges branches into `dev` after review.

Pitfalls:
- `codex exec resume` has no `-C`; the launcher `cd`s into the worktree. Don't edit the launcher while a worker runs (bash re-reads it).
- Workers leave Gradle daemons running as the sandbox user in the shared `~/.gradle`; if a local build fails with AccessDeniedException, use `bash scripts/build.sh --no-daemon`.
