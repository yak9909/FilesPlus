# Preview Move Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** In the file preview, "Send to…" moves the current file (or the preview selection) to a chosen folder through a background queue, remembering destinations (pinned, then recent), and the preview advances at once.

**Architecture:** Extension-side `DestinationHistory` (SharedPreferences) and `MoveQueue` (single background thread owned by ModKit, outliving the Activity; reuses `TabMove` file logic). A plain bottom-aligned `Dialog` lists history plus a simple folder browser. A bytecode patch adds the menu item and bridges to the app's own "remove current file and go to next" method.

**Tech Stack:** Morphe patcher (Kotlin patch, smali bridges, string/shape fingerprints), Java extension (no Android resources, no app types in Java signatures).

**Spec:** `docs/DESIGN.md` §5.4 including the investigation notes ("調査結果"). Worker rules: `docs/WORKER_BRIEF.md`. Same-style code: `extensions/.../preview/` (PreviewHooks, SelectionStore, ListHandoff), `patches/.../preview/PreviewMultiSelectPatch.kt`, `extensions/.../foldertabs/TabMove.java`.

## Global Constraints

- UI stays original: only an item in the existing preview overflow menu and on-demand dialogs. No new screens.
- Fingerprints use strings, resource literals and type shapes only — never obfuscated names (names in the plan are orientation for 2081362).
- Every hook catches `Throwable`, logs via `DiagLog`, falls back to original behavior. Extension has no Android resources.
- Never overwrite: name conflicts become `name (1).ext` (existing `TabMove` rule). Moves need all-files access (already granted in the manifest).
- New visible patch "Move queue in preview" (default true) may be added to `PreviewMultiSelectPatch.kt` as a separate patch in `patches/.../preview/PreviewMoveQueuePatch.kt` depending on `modKitIntegrationPatch` and the multi-select patch. Worktree `E:/Projects/ModdedFiles-wt/queue` (branch `w/queue`). Do not commit.
- Item titles: ja `移動先へ送る…` / en `Send to…`.

## Review Focus

1. A playing video/audio is moved only after the user leaves its page (or the preview closes); not moved while open.
2. Name conflict in the destination → renamed, never overwritten; moving into the same folder is skipped; moving a folder into itself fails safely.
3. Process/Activity lifecycle: queue survives the preview Activity closing; no Activity/View leaks in statics.
4. Destination that no longer exists or is not writable → failure is counted and reported, queue continues.
5. History: duplicates collapse, pinned stay first, size is capped (e.g. 20 recent).

## File Structure

- Create `extensions/extension/src/main/java/app/filesplus/extension/move/DestinationHistory.java`
- Create `extensions/extension/src/main/java/app/filesplus/extension/move/MoveQueue.java`
- Modify `extensions/.../foldertabs/TabMove.java` (make the class and `pathsOf`/`move`/`Result` public, no behavior change)
- Create `extensions/.../move/DestinationSheet.java` (Task 2), `FolderBrowser.java` (Task 2)
- Modify `extensions/.../preview/PreviewHooks.java` (menu item, page-change/close notifications)
- Create `patches/.../preview/PreviewMoveQueuePatch.kt` (Tasks 2-3)

### Task 1: DestinationHistory and MoveQueue (no UI)

**Interfaces:**
- Produces `DestinationHistory`: `static List<String> pinned()`, `static List<String> recent()`, `static List<String> ordered()` (pinned then recent, deduplicated), `static void record(String folderPath)`, `static void setPinned(String folderPath, boolean pinned)`. Keys in `ModSettings` SharedPreferences: `move.pinned`, `move.recent` (newline-separated; recent capped at 20).
- Produces `MoveQueue`: `static void enqueue(Context app, long[] ids, String[] mimes, File destination)`; `static void onPageChanged(long currentId)` (releases deferred jobs whose id is no longer current); `static void onPreviewClosed()` (releases all deferred jobs); queue runs on one daemon-free background thread held in a static executor; on completion posts one Toast on the main thread (`n 件を "<folder>" に移動しました` / `Moved n items to "<folder>"`, mention failures/skips) and calls `DestinationHistory.record(destination.getPath())` once per enqueue.
- Consumes `TabMove.pathsOf(Context, long[])`, `TabMove.move(Context, List<String>, File)`.

- [ ] **Step 1:** Make `TabMove` and the members above `public` (visibility only).
- [ ] **Step 2:** Implement `DestinationHistory` (normalize paths, drop empty, cap).
- [ ] **Step 3:** Implement `MoveQueue`: each enqueue becomes jobs per id; a job with `mime` starting `video/` or `audio/` whose id equals the id given by the most recent `onPageChanged` (initially unknown → set by the caller via `onPageChanged` before enqueue) is held in a deferred list; everything else runs at once in submission order. Resolve paths with `TabMove.pathsOf`, move with `TabMove.move`, then sync MediaStore (already inside `move`).
- [ ] **Step 4:** Build `bash scripts/build.sh --no-daemon` (worktree). Expected: BUILD OK. Apply all patches to the original base.apk with the scratchpad harness `.../scratchpad/verify` (VerifyPreview.java + Dump.java; base.apk at `.../scratchpad/v2/base.apk`). Expected: all "Applied ..." lines.

### Task 2: Destination sheet, folder browser, menu item

**Interfaces:**
- Consumes `DestinationHistory`, `MoveQueue`, `SelectionStore` (entries are app objects; ids come from `PreviewHooks` current id; for the selection use the stored map keys — add `SelectionStore.ids()` returning `long[]` and `mimes()` if the mime is available from the entry, otherwise treat as non-deferred).
- Produces `DestinationSheet.show(Activity activity, Runnable onChosen... )` details are the worker's choice within: a bottom-aligned plain `Dialog` (rounded `GradientDrawable`, programmatic list), pinned and recent destinations first (long-press toggles pin), a "フォルダを選ぶ… / Choose folder…" row opening `FolderBrowser`, which lists folders with `File.listFiles` from the storage root (`Environment.getExternalStorageDirectory()` plus other volumes from `StorageManager`), with a "ここに移動 / Move here" button and a "新しいフォルダ / New folder" row.
- `PreviewHooks.onMenuCreated` adds the fixed-id item `移動先へ送る…` (only when the current file has a MediaStore id, same rule as Select); `onMenuItem` opens the sheet; choosing a destination calls `MoveQueue.enqueue` for the preview selection if `SelectionStore.count() > 0`, else for the current file; then `SelectionStore` entries moved are removed (`clear()` for the selection case).
- `PreviewHooks.onPageSelected` also calls `MoveQueue.onPageChanged(id)`; `ListHandoff` close handling calls `MoveQueue.onPreviewClosed()`.

- [ ] **Step 0 (carry-overs from the Task 1 Opus review, apply first in `MoveQueue`/`DestinationHistory`):** (a) prefer `ModKit.context()` before falling back to the passed Context in `MoveQueue` so no Activity is held in a static; (b) dedupe `ids[]` in `enqueue`; (c) run the queue thread at background priority (`Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)` inside the factory runnable); (d) in `DestinationHistory` check `ModSettings.prefs() == null` explicitly instead of catching NPE; (e) when everything failed, make the toast lead with the failure (`移動できませんでした（n 件）` / `Could not move n items`); (f) log at most one `DiagLog.e` per batch for an unusable destination.
- **Caller conditions (Task 1 review, Important):** call `MoveQueue.onPageChanged(currentId)` right before every `enqueue`; call `MoveQueue.onPreviewClosed()` ONLY when the preview Activity is really finishing or destroyed (never on rotation, Home or Recents — use the same finishing/destroyed rule as `ListHandoff`, and also release held jobs when the Activity is destroyed). The mime passed for the current file must be the real MediaStore mime (query it by id; if unknown, treat as `video/` when the extension looks like video — never as "not media").
- [ ] **Step 1:** `FolderBrowser` + `DestinationSheet` (UI only; Throwable-safe; reachable only from the menu item).
- [ ] **Step 2:** Wire the menu item and the page-change/close notifications.
- [ ] **Step 3:** Build + apply all patches as in Task 1. Report device checks: sheet shows history, pin works, folder browser can create a folder and pick it, current file moves (video after leaving its page), selection moves in one go, name conflicts rename.

### Task 3: Remove moved files from the preview pager

- [ ] **Step 1:** Patch: fingerprint the app's `removeCurrentAndMoveToNextFile` (strings `"removeCurrentAndMoveToNextFile with only 1 file."` and `"removeCurrentAndMoveToNextFile from an invalid position item."`) and the int field it uses as the "selected index" (`C`, set to the current index `B` before calling); add a peer bridge `modKitRemoveCurrent()V` that sets `C = B` and calls it; only when the list has more than one entry, else close the Activity (`Activity.finish()`).
- [ ] **Step 2:** Call the bridge from the extension right after a single-file enqueue (current file moved → page advances at once). For selection moves: after the batch finishes call the app's reload (`B(max(0,B-50), true)`) through a second bridge found by the same peer shape — if that cannot be found without fragile coupling, skip it and report.
- [ ] **Step 3:** Build + apply all patches. Device checks: after sending, the next file shows immediately; single-file preview closes; moved video still plays until the user swipes away.

### Task 4 (optional, later): record the app's own move destinations and a "quick send" top 3

- [ ] Hook `jkt.s(jmq, Z)` by the string "Index for selected file is invalid" and shape `(L,Z)V`; pass the destination object and the operation to the extension; reflect over its String fields, accept values starting with `file:`, `content:` or `/`; record only moves. Best-effort: skip unparsable values.
