# Codex worker sessions

Active Codex CLI sessions (see CLAUDE.md "Working with Codex CLI workers").
Delete a session with `codex delete <id>` when its context is no longer needed, and remove its row.

| Session ID | Model / effort | Purpose | Status |
|------------|----------------|---------|--------|
| 01a1c6ce-7ec9-7ca3-974d-5d83535687af | gpt-6.1-sol / high | `pkg`: Change package name patch (worktree `../ModdedFiles-wt/pkg`, branch `w/pkg`) | stopped by usage limit (resume after 07:40) |
| 01a1c6ce-87dc-75a1-b5d4-808fb4b5182b | gpt-6.1-sol / high | `core`: ModKit integration + Mod settings/diagnostics (`../ModdedFiles-wt/core`, `w/core`) | stopped by usage limit (resume after 07:40) |
| 01a1c6d0-87e6-7a92-a72c-288fcc90899a | gpt-6.1-sol / medium | `skip`: Custom skip duration (`../ModdedFiles-wt/skip`, `w/skip`) | stopped by usage limit (resume after 07:40) |
| 01a1c6d0-905d-7682-9630-9e16e0025970 | gpt-6.1-sol / xhigh | `media`: High-res playback L1 + diagnostics (`../ModdedFiles-wt/media`, `w/media`) | stopped by usage limit (resume after 07:40) |

Launcher: `scripts/codex-worker.sh` (start/resume). Prompts and logs: `worker-logs/` (git-ignored).
Parallel workers each use their own git worktree under `E:/Projects/ModdedFiles-wt/` to avoid build conflicts; Claude merges branches into `dev` after review.
