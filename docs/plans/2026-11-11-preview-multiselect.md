# Preview Multi-Select Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** In the file preview screen (image / video), the user can select files and see which are selected; on returning to the list, the app's own selection mode contains them.

**Architecture:** A small extension-side store (`SelectionStore`) keeps the selected entries (opaque app objects keyed by MediaStore id). One bytecode patch adds menu items and a check overlay to the preview gallery fragment and feeds the store from smali bridges; a second hook pushes the store into the list screen's selection model on return.

**Tech Stack:** Morphe patcher (Kotlin patch, smali bridges, string/literal fingerprints), Java extension (no Android resources, no app types in Java signatures).

**Spec:** `docs/DESIGN.md` §5.2 including the investigation notes ("調査結果"). Worker rules: `docs/WORKER_BRIEF.md`. Reference implementation of the same style: `patches/.../foldertabs/MoveToCurrentTabPatch.kt`, `extensions/.../foldertabs/FolderTabContext.java`.

## Global Constraints

- UI stays the original: only items in the existing top-bar overflow menu and one small non-interactive check overlay.
- Fingerprints use strings, resource literals and type shapes only — never obfuscated class/method names (names in this plan are orientation for version 2081362).
- Every hook method catches `Throwable`, logs via `DiagLog.e`, falls back to original behavior. Extension has no Android resources; look app resources up by name.
- New visible patch "Multi-select in preview" (default true), depends on `modKitIntegrationPatch`. Work in worktree `E:/Projects/ModdedFiles-wt/preview` (branch `w/preview`). Do not commit.
- Item titles: ja `選択` / `選択を解除` / `選択中 n 件`, en `Select` / `Deselect` / `n selected`. Fixed int menu item ids (no new resources).

## Review Focus

1. Entry has neither MediaStore id (`kqi.b & 131072`) nor content URI (`& 256`) → no "Select" item, no crash.
2. Menu is not inflated for some entries (folders) → our items must not appear and the hook must not NPE.
3. Rotation does not recreate the activity; store survives anyway (static). Process death loses it — acceptable.
4. Page swipe while selected → check overlay follows the current file (pages are recycled; overlay is one view).
5. List activity destroyed while preview open → return hook must run on the new list instance, with nothing left stale.

## File Structure

- Create `extensions/extension/src/main/java/app/filesplus/extension/preview/SelectionStore.java` — store, no Android deps beyond `DiagLog`.
- Create `extensions/extension/src/main/java/app/filesplus/extension/preview/PreviewHooks.java` — menu items, overlay, called from smali.
- Create `patches/src/main/kotlin/app/filesplus/patches/preview/PreviewMultiSelectPatch.kt` — fingerprints and bridges.
- Modify `docs/DESIGN.md` §5.2 status only after both tasks pass.

### Task 1: SelectionStore and preview menu

**Files:** Create `SelectionStore.java`, `PreviewHooks.java`, `PreviewMultiSelectPatch.kt` (menu part only).

**Interfaces:**
- Produces (`SelectionStore`): `static synchronized boolean toggle(long id, Object entry)` (returns new selected state), `static boolean isSelected(long id)`, `static int count()`, `static List<Object> entries()` (insertion order, copy), `static void clear()`.
- Produces (`PreviewHooks`, as built): `static void onMenuCreated(View root, Menu menu, long id, boolean hasId, Object entry)` adds items; `static boolean onMenuItem(View root, int itemId, long id, boolean hasId, Object entry)` returns true if handled (toggles / clears, then invalidates the options menu through the Activity found from `root`); `static boolean handles(int itemId)`. `root` = the gallery fragment's root view (Task 2 reuses it for the overlay).

- [ ] **Step 1:** Implement `SelectionStore` (LinkedHashMap<Long,Object>, synchronized).
- [ ] **Step 2:** Implement `PreviewHooks.onMenuCreated`: skip when `!hasId`; add "Select"/"Deselect" for the current id; when `count() > 0` also add "n selected" (tapping it clears the selection after a confirm-free toast `n 件の選択を解除しました` / `Cleared n`) — keep it one extra item. Use `DiagLog.i` for toggles.
- [ ] **Step 3:** In the patch: find the gallery fragment class by the `(Menu, MenuInflater)V` method that uses literal `0x7f0f000e` (`file_preview_menu`); right after its `MenuInflater.inflate` call (NOT before the `return-void`s: the early returns for folders must stay free of our items) call a bridge that obtains the activity, the current entry (`c()`-style accessor: `()` → proto type whose class has the `"File preview index is not valid."` string path) and its MediaStore id field, then `PreviewHooks.onMenuCreated`. Hook the click handler (method with `MenuItem` parameter that has strings `"Fragment:onOptionsItemSelected"`): at method start call `PreviewHooks.onMenuItem`; if true return true.
- [ ] **Step 4:** Run `bash scripts/build.sh --no-daemon` from the worktree. Expected: BUILD OK.
- [ ] **Step 5:** Apply all patches to the original APK with the harness in `E:/Projects/ModdedFiles/build/verify/` (add the new patch to its `Set.of(...)`; copy the harness to the scratchpad as before — see `docs/BUILD_PROCEDURE.md` / verify README). Expected: "Applied Multi-select in preview" and the dumped bridges look correct.

### Task 2: Check overlay and hand-off to the list

**Files:** Modify `PreviewHooks.java`, `PreviewMultiSelectPatch.kt`.

**Interfaces:**
- Consumes: `SelectionStore` from Task 1.
- Produces (`PreviewHooks`): `static void onPageSelected(View galleryRoot, long id, boolean hasId)` shows/hides one small check overlay (non-clickable, non-focusable, top-right, above toolbar gradient/scrim); `static List<Object> takePendingForList()` returns `SelectionStore.entries()` and clears the store, or empty list.

- [ ] **Step 1:** Overlay: add one `ImageView`/`TextView` (✓ in a small circle, drawn in code) to the gallery fragment root (parent of the view with id name `toolbar`, found via `getIdentifier`); `onPageSelected` sets visibility from `SelectionStore.isSelected(id)`. Also refresh it from `onMenuItem` after a toggle.
- [ ] **Step 2:** Patch: hook the ViewPager2 page-change callback `G(I)V` (class found by the strings/resources noted in DESIGN §5.2) to call `onPageSelected`.
- [ ] **Step 3:** Hand-off: in the list owner (same owner/selection-model discovery as `MoveToCurrentTabPatch`: field whose class has `SELECTED_DATA_STATE_TAG`), hook its resume/return so that `takePendingForList()` entries are passed to the selection model's `(List)V` add method (`r`-style: takes `List` of the proto entries). Only when the list is non-empty.
- [ ] **Step 4:** Build + apply to original APK as in Task 1. Expected: BUILD OK, all patches applied (Delete folder from tabs, High-resolution playback, Move to current tab, Show empty folder tabs, Custom skip duration, Multi-select in preview).
- [ ] **Step 5:** Final reply lists on-device checks: Select/Deselect in preview; check mark follows swipes; no items on folders; selection appears in the list after back; nothing stale on the next preview visit.

### Task 3: Hand the selection over to the list (added after Task 2 investigation)

Task 2 found: the list's selection model (`lmq`, found by the `SELECTED_DATA_STATE_TAG` string) is created once per Activity component (Hilt); the preview has its own unused instance; many list-side classes hold one, and there is no identifiable resume hook (lifecycle interfaces are identical `void a()` shapes). `lmq.r(List)` merges proto entries into the existing state asynchronously (id set / uri map), skips duplicates, never clears — safe to call on the main thread.

**Approach (decided):** register every `lmq` instance in the extension from its constructor (weak refs), and drain the store when a non-preview Activity resumes, via `Application.ActivityLifecycleCallbacks` registered once (ModKit has the app context). On drain, call `r(List)` through a smali bridge on every live registered instance (instances of other screens/preview are harmless: duplicates are skipped, unused models ignore it). Drain only when `SelectionStore.count() > 0` and the resumed Activity is not the preview Activity class (compare by the class name of the activity that hosts the gallery fragment — find it via the preview menu hook's Activity, not by obfuscated name).

**Files:** modify `PreviewHooks.java` (or new `ListHandoff.java` in the same package), `PreviewMultiSelectPatch.kt`.
**Interfaces:** Produces `static void registerModel(Object model)` (called from the model constructor via smali), `static void install(Application app)` (idempotent; called once from the same constructor hook), and a bridge `void modKitAddSelected(List)` on the model class that calls its `r(List)`.

- [ ] **Step 1:** Fingerprint the model class by `SELECTED_DATA_STATE_TAG`; in its constructor(s) call `registerModel(this)` + `install(context)`; add bridge `modKitAddSelected(List)V` → `r(List)` (find `r` by shape `(List)V` whose body calls the `(List, Z)V` method with true; fail the patch loudly if not unique).
- [ ] **Step 2:** On `onActivityResumed` of a non-preview Activity: `List<Object> pending = SelectionStore.drain()`; if non-empty call `modKitAddSelected` on each live registered model via reflection-free interface (declare a tiny interface in the extension that the model class implements through the patch, e.g. `SelectionTarget { void addSelected(List) }`), main thread only, Throwable-safe.
- [ ] **Step 3:** Update the overlay/badge when the store is drained (nothing to show) — no stale badge on the next preview visit.
- [ ] **Step 4:** Build + apply all patches to the original base.apk (harness in the scratchpad). Report device checks: select 2-3 files in preview, back to list → they are selected and the action bar shows the count; second preview visit shows no stale selection; selecting in the list works as before.
