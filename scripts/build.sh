#!/usr/bin/env bash
# Build the patch bundle. Requires GITHUB_TOKEN (read:packages) in env, or a logged-in gh CLI.
# Prints only errors on failure (full log: build/build.log).
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME_21:-/c/Program Files/Java/jdk-21.0.3}"
export ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
export GITHUB_ACTOR="${GITHUB_ACTOR:-yak9909}"
export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
mkdir -p build
if ./gradlew buildAndroid --console=plain "$@" > build/build.log 2>&1; then
  echo "BUILD OK: $(ls patches/build/libs/*.mpp)"
else
  grep -E '^e: |error:|What went wrong' -A3 build/build.log | head -60
  echo "BUILD FAILED (see build/build.log)"; exit 1
fi
