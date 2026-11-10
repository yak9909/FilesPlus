#!/usr/bin/env bash
# Start or resume a Codex worker session (persistent, not ephemeral).
#   start : scripts/codex-worker.sh start  <name> <model> <effort> <prompt-file> [workdir]
#   resume: scripts/codex-worker.sh resume <name> <session-id> <prompt-file> [workdir]
#   (resume has no -C; it runs in workdir, default E:/Projects/ModdedFiles-wt/<name> if it exists)
# Logs: worker-logs/<name>.log, last reply: worker-logs/<name>.last.md (git-ignored).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGDIR="$ROOT/worker-logs"; mkdir -p "$LOGDIR"
export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}" GITHUB_ACTOR=yak9909
W() { cygpath -m "$1"; }
ROOTS="[\"$(W "$USERPROFILE/.gradle")\",\"$(W "$USERPROFILE/.android")\",\"$(W "$LOCALAPPDATA/Android/Sdk")\"]"
# Sandbox settings as -c overrides so they also apply to `exec resume`.
CFG=(-c sandbox_mode=\"workspace-write\"
  -c sandbox_workspace_write.network_access=true
  -c "sandbox_workspace_write.writable_roots=$ROOTS"
  -c shell_environment_policy.ignore_default_excludes=true
  --skip-git-repo-check)
mode=$1; name=$2
if [ "$mode" = start ]; then
  model=$3; effort=$4; prompt=$5; wd=${6:-$ROOT}
  codex exec -m "$model" -c model_reasoning_effort="$effort" "${CFG[@]}" --color never -C "$wd" \
    -o "$LOGDIR/$name.last.md" - < "$prompt" > "$LOGDIR/$name.log" 2>&1
else
  sid=$3; prompt=$(cd "$(dirname "$4")" && pwd)/$(basename "$4")
  wd=${5:-$ROOT/../ModdedFiles-wt/$name}; [ -d "$wd" ] || wd=$ROOT
  cd "$wd" || exit 1
  codex exec resume "${CFG[@]}" "$sid" -o "$LOGDIR/$name.last.md" - < "$prompt" >> "$LOGDIR/$name.log" 2>&1
fi
echo "exit=$? session=$(grep -m1 -oiE 'session id: *[0-9a-f-]+' "$LOGDIR/$name.log" | awk '{print $NF}')"
