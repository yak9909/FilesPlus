# Worker sessions

Active workers and reviewers (see CLAUDE.md "Workers" / "Review"). Claude subagents (Sonnet/Haiku implement, Opus reviews) are preferred; Codex is a fallback. Always pass `model` explicitly when dispatching.
Delete a Codex session with `codex delete --force <id>` when its context is no longer needed, and remove its row.

| Session ID | Model / effort | Purpose | Status |
|------------|----------------|---------|--------|
| subagent a0454dddf4d7bc645 | Claude sonnet / high | `smb` Task 3 (manifest provider patch + read-only `SmbDocumentsProvider`), plan `docs/plans/2026-11-12-smb.md`, worktree `../ModdedFiles-wt/smb`, branch `w/smb` | Done. Opus-reviewed (no Critical/Important), merged into dev. Tasks 1-3 are in dev. Next: Task 4 with a fresh implementer. |
| subagent a41f9a4f20322a77e | Claude sonnet / high | `move` U3 (preview Move takeover, "Unified Move"), plan `docs/plans/2026-11-12-unified-move-browser.md`, worktree `../ModdedFiles-wt/move`, branch `w/move` | FROZEN (2026-11-12). Implemented, built, all 8 patches apply; UNCOMMITTED, not reviewed. Do not merge: its UI (MoveSheet etc.) violates DESIGN P8 (reuse the app's real UI). Keep the worktree; logic may be reused after the §5.11 redesign. |
| subagent aa28c6b5b76a70910 | Claude opus / high | Plan `2026-11-12-original-screens.md` Task 0 + Task 1 (static analysis only, read-only; report → `docs/research/2026-11-12-original-screens-p0.md`) | Running (2026-11-12) |
| subagent adee4ffc7671eb2b8 | Claude opus | Builder check of DESIGN §5.12 (read-only, no worktree) | Done (9 questions; answered in DESIGN §5.12 or asked to the user). Can be dropped. |
| (done) | | | `ui` (Mod settings restyle) merged into dev (0fd6ece); worktree `../ModdedFiles-wt/ui` can be removed. |
| (none) | | | `queue` (DESIGN 5.4, tasks 1-3) finished and merged into dev (d1fc25d). Worktrees `../ModdedFiles-wt/{tabs,preview,queue}` (branches `w/tabs`, `w/preview`, `w/queue`) can be removed. No Codex sessions. |

Launcher (Codex fallback): `scripts/codex-worker.sh` (start/resume). Prompts and logs: `worker-logs/` (git-ignored).
Run at most 1 worker at a time (2 only if needed). Workers each use their own git worktree under `E:/Projects/ModdedFiles-wt/` to avoid build conflicts; Claude merges branches into `dev` after review.

Pitfalls:
- `codex exec resume` has no `-C`; the launcher `cd`s into the worktree. Don't edit the launcher while a worker runs (bash re-reads it).
- Workers leave Gradle daemons running as the sandbox user in the shared `~/.gradle`; if a local build fails with AccessDeniedException, use `bash scripts/build.sh --no-daemon`.
