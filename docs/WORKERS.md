# Worker sessions

Active workers and reviewers (see CLAUDE.md "Workers" / "Review"). Claude subagents (Sonnet/Haiku implement, Opus reviews) are preferred; Codex is a fallback. Always pass `model` explicitly when dispatching.
Delete a Codex session with `codex delete --force <id>` when its context is no longer needed, and remove its row.

| Session ID | Model / effort | Purpose | Status |
|------------|----------------|---------|--------|
| subagent afb468b20444485ff | Claude sonnet / high | `smb` Task 2 (SmbVfs, session pool, connection store with Keystore AES-GCM, Mod 設定 → SMB UI), plan `docs/plans/2026-11-12-smb.md`, worktree `../ModdedFiles-wt/smb`, branch `w/smb` | Idle (stopped at the usage threshold). Implemented + Opus-reviewed (no Critical, 4 Important) + fixes applied; built and patched OK; UNCOMMITTED in the worktree; the scoped re-review of the fixes is still pending (see HANDOFF.md item 0). Task 1 is in dev (835eb5d, dev.9). |
| (none) | | | `queue` (DESIGN 5.4, tasks 1-3) finished and merged into dev (d1fc25d). Worktrees `../ModdedFiles-wt/{tabs,preview,queue}` (branches `w/tabs`, `w/preview`, `w/queue`) can be removed. No Codex sessions. |

Launcher (Codex fallback): `scripts/codex-worker.sh` (start/resume). Prompts and logs: `worker-logs/` (git-ignored).
Run at most 1 worker at a time (2 only if needed). Workers each use their own git worktree under `E:/Projects/ModdedFiles-wt/` to avoid build conflicts; Claude merges branches into `dev` after review.

Pitfalls:
- `codex exec resume` has no `-C`; the launcher `cd`s into the worktree. Don't edit the launcher while a worker runs (bash re-reads it).
- Workers leave Gradle daemons running as the sandbox user in the shared `~/.gradle`; if a local build fails with AccessDeniedException, use `bash scripts/build.sh --no-daemon`.
