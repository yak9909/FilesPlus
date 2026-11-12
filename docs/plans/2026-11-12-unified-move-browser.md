# Unified Move + Destination Browser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** One "Move" entry: the app's own Move (preview and list selection) opens our destination sheet (pinned/recent) and a full-screen destination browser modelled on the app's category screens; moves run through our MoveQueue. The same browser (browse mode) is the SMB browser.

**Spec:** `docs/DESIGN.md` §5.11 (source of truth), plus §5.4 (pager rules), §5.9 (KnownFolders), §5.3/§5.3.1/§5.3.2 (SMB), §10 (file-operation safety). Worker rules: `docs/WORKER_BRIEF.md`. Style references: `extensions/.../settings/SettingsLook.java` (inflate app layouts by name with fallback), `patches/.../foldertabs/MoveToCurrentTabPatch.kt` (owner/selection fingerprints), `patches/.../preview/PreviewMoveQueuePatch.kt` (bridges + interface on an app class).

## Global Constraints

- No new resources. App layouts/styles/dimens/drawables/strings are looked up **by name** at runtime (`getIdentifier`, cached). App view classes under `com.google.android.apps.nbu.files.*` must never be instantiated (Hilt peer → crash outside app fragments); substitute them while inflating (DESIGN §5.11.2) or build a look-alike.
- Do not call obfuscated methods of library classes (RecyclerView adapter, MaterialToolbar title). Library views are configured by XML attributes only; call framework methods only.
- Fingerprints: strings, resource names resolved to ids at patch time, type shapes. Never obfuscated names. Every hook catches `Throwable` and falls back to the app's original Move.
- §10 rules for anything that writes: no overwrite, single op thread (MoveQueue), mkdir is one level and fails if it exists.
- U3/U4 must not start before §10 S1–S3 are merged. Cross-volume destinations go to the original Move flow until S4 (DESIGN §5.11.3 step 4).
- Dex method budget: report the extension method count after each task (limit 65,536).
- Worktree `E:/Projects/ModdedFiles-wt/move` (branch `w/move`). Do not commit. JDK 21. One Gradle run at a time.

## Review Focus

1. No app view class instantiated from the extension; graceful fallback when a layout/resource name is missing.
2. The original Move still works whenever we decline (no id, no selection, exception, bypass), and Copy is untouched.
3. Selection read at pick time (not at hook time); selection cleared only after enqueue.
4. No I/O on the UI thread; MediaStore aggregation cached by generation; thumbnails cancelled on recycle.
5. Pick result delivered in-process; nothing moves after cancel, rotation, Home or process death.

## Tasks (one at a time)

### U0: `ui/FilesLook` + diagnostics "UI 見本"

**Files:** create `extensions/.../ui/FilesLook.java`; modify `settings/SettingsLook.java` (use FilesLook for name lookups, theme colors, dialog styling); modify the diagnostics screen in `settings/ModSettingsActivity.java` (one row "UI 見本 / UI sample").

**Interfaces:** `FilesLook.inflate(Context, String layoutName, ViewGroup parent)` → View or null, using a cloned `LayoutInflater` with a `Factory2` that maps tag names: `...itemviews.RowItemView`→`MaterialCardView`, `...itemviews.SquareItemView`→`RelativeLayout`, `...BrowseCapsuleItemView` / `...FolderPickerListItemView`→`LinearLayout`, `...RoundedCornerImageView`→`ImageView` (outline corner radius from a named dimen), `...SelectionIndicatorView`→`View` (GONE), `...BreadcrumbView`→`HorizontalScrollView`, `android.support.v7.widget.RecyclerView` / `...GridLayoutRecyclerView`→`ListView`, `Button`→`MaterialButton`; any other `com.google.android.apps.nbu.files.*` tag → `FrameLayout` + log once. Helpers: `id(name)`, `string(name)`, `plural(name, n)`, `drawable(name)`, `style(name)`, `dimenPx(name, fallbackDp)`, `themeColor(attrName, fallback)`, `styleDialog(Dialog)`.

- [ ] Step 1: Implement FilesLook; move the generic helpers out of SettingsLook (no visible change to Mod settings).
- [ ] Step 2: "UI 見本" opens a plain dialog/activity showing: one `folder_picker_list_item` row, one `browse_capsule_item_view` row (Images icon + label), two `row_item_view` rows (folder icon; thumbnail placeholder), a 3-column strip of `square_item_view`, the `button_section` of `advanced_browsing_picker_fragment` (New folder / Move here), `no_files_blob`, `file_filter_container` with 3 chips. Each element states "original" or "fallback" in DiagLog.
- [ ] Step 3: Build + apply all patches (scratchpad verify harness). Device check §9 #19 (light and dark).

### U1: Browse core (no UI)

**Files:** create `browse/Location.java`, `Entry.java`, `Bucket.java`, `Category.java`, `BrowseSource.java`, `LocalSource.java`, `ui/ThumbLoader.java`; modify `move/DestinationHistory.java`; extract the bucket→folder resolution from `foldertabs/FolderTabContext.java` into a shared static helper (no behavior change there).

**Interfaces:** as DESIGN §5.11.5. `Location.parse/toString` (local = absolute path, backward compatible with the current history; SMB = `smb://<connId>/<share>/<path>`). `LocalSource.buckets(Category)`: one MediaStore query per external volume (`getExternalVolumeNames`) with a minimal projection, grouped in Java by (volume, RELATIVE_PATH) → count, newest id/date; merged with §5.9 KnownFolders for the category (count 0); cached per volume by `MediaStore.getGeneration`. `list(dir, filter)`: direct children, dirs first; hides dot folders, `Android/`, `.FilesPlus`. `mkdir`: `Os.mkdir` one level, `EEXIST` → error, then `scanFile`. `thumbnail`: `ContentResolver.loadThumbnail` for MediaStore items, null otherwise. `ThumbLoader`: memory LRU + 2 background threads + per-view cancellation. `DestinationHistory`: store `Location` strings; `recordSuccess(Location)`; `exists` checked off the UI thread.

- [ ] Steps: implement; build; apply patches; report method count. Log bucket aggregation time per category in DiagLog (device check: 10k images).

### U2: `BrowseActivity` — pick mode (local)

**Files:** create `browse/BrowseActivity.java`, screen classes (`RootScreen`, `CategoryScreen`, `FolderScreen`), `browse/PickRequests.java`; modify `patches/.../misc/ModKitIntegrationPatch.kt` (manifest: activity `exported=false`, SettingsActivity theme, `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`).

**Interfaces:** `PickRequests.start(Activity from, PickSpec spec, Consumer<Location> onPicked)` (spec: sources' folders for the "same folder" check, title, count); results only via the in-process callback; unknown request id on create → finish. Screens per DESIGN §5.11.4 ②③④⑤ (no "すべて" in pick mode). Shell = `advanced_browsing_picker_fragment` via FilesLook (fallback: framework Toolbar + ListView + two buttons). New folder dialog = `new_folder_content` + app strings. "ここに移動" disabled per §5.11.4; cross-volume destination → callback receives the Location and the caller decides (U3).

- [ ] Steps: implement; build; apply; device checks §9 #16.

### U3: Preview Move takeover (after §10 S1–S3)

**Files:** create `move/MoveSheet.java`, `move/MovePicker.java`, `move/MoveHooks.java`, `ui/` dialog helpers moved from `DestinationSheet`; modify `preview/PreviewHooks.java` (drop `MOVE_ITEM`; `handles()` includes `move_to_action` when the patch is applied; keep `queueMove` pager rules; keep the options `Menu` weakly); delete `move/DestinationSheet.java`, `move/FolderBrowser.java`; replace `patches/.../preview/PreviewMoveQueuePatch.kt` with "Unified Move" (keep the pager bridges; patch name/description updated).

**Interfaces:** `MovePicker.start(Activity, MoveTarget)`: history non-empty → MoveSheet (look of `folder_picker_bottom_sheet_fragment` / `folder_picker_list_item`), else BrowseActivity. On pick: same-volume and within the pre-S6 limit (≤ 50 items and ≤ 100 MB) → `MoveQueue.enqueue`; otherwise, or on failure → `continueOriginal` (one-shot bypass flag, 2 s; preview: `menu.performIdentifierAction(move_to_action, 0)` on the latest menu from `onMenuCreated`). Exception: a preview **selection** (SelectionStore non-empty) never falls through — the original preview Move only moves the current file — show a message and move nothing. `DestinationHistory.recordSuccess` from the queue's completion.

- [ ] Steps: implement; build; apply; device checks §9 #17 and the old §5.4 checks.

### U4: List Move takeover

**Files:** modify `move/MoveHooks.java`; create `patches/.../move/UnifiedMovePatch.kt` additions (or extend the U3 patch) and `patches/.../shared/` helpers (resource name → id; tabbed owner + selection-changed fingerprints shared with `MoveToCurrentTabPatch.kt`).

**Hooks:** (1) launcher class = class whose field type contains the string "Could not get root containers."; at the start of its `(I)V`: `MoveHooks.onListMove(Object launcher, View root, int mode)Z` → `return-void` when true (mode 3 only; mode 2 = Copy is always declined). Root view via the launcher's fragment field (first constructor parameter type) and the fragment's no-arg `View` method. Add interface `OriginalMove` with bridge `modKitLaunchOriginal(I)V`. (2) after the tabbed owner's selection-changed method: `MoveHooks.onSelection(View root, List, List, Runnable clear)` (works without the Move-to-current-tab patch).

(3) at the start of the tabbed owner's `(MenuItem)Z` (the method comparing `move_to_action` / `copy_to_action`): if the item is `move_to_action`, `MoveHooks.noteSelectionMove(View root)` (one-shot, 2 s).

**Rules:** take over only when the one-shot "from selection menu" mark is set (the file ︙ popup, search and keyboard paths decline in v1); drop the captured selection when the action mode ends; decline when the id count differs from the entry count; read the selection when the destination is picked; empty → continue original; same pre-S6 size limit as U3; after enqueue run `clear`.

- [ ] Steps: implement; build; apply; device checks §9 #18 and #20; selection menu takes over; file ︙ popup, search screen and Copy stay original.

### U5: SMB browse mode (replaces SMB plan Task 4 stub + Task 5 UI)

**Files:** create `browse/SmbSource.java`; modify `BrowseActivity` (browse mode: connection list → shares → folders; filter chips `file_filter_container`/`file_filter_item`, current folder only, by extension; tap file → app preview with the provider `content://` URI); the Task 4 tap hook opens `BrowseActivity` (browse mode).

- [ ] Steps: implement; build; apply; device checks from SMB plan Task 4/5.

### U6 (later): SMB as destination (after S9), internal-storage list (`hul.ao(3)`) takeover, quick send top 3, optional SMB media index.
