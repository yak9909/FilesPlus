# 元の画面で動かす（DESIGN §5.12）実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 自前のブラウザ・設定画面をやめ、元の Files の画面（ストレージブラウザ・メディアブラウザ・設定・ゴミ箱）の中で、切り取り → 貼り付け、表示の切り替え、SMB の閲覧、フォルダの復元を動かす。

**Architecture:** 元の画面はそのまま使い、パッチ（Morphe、fingerprint）で項目・ボタン・データを差し込む。元の画面を開くときは Intent extra `activity_params`（`ProtoParsers$InternalDontUse`）に、実物から写した proto のバイト列を入れる。ファイル操作は自前のエンジン（§10、`TabMove` → S4/S6 で置き換え）と `MoveQueue`。

**Tech Stack:** Morphe patches（Kotlin）、拡張（Java、minSdk 31）、smali の差し込み。テストは JVM テストのモジュールが S4 でできるまで、ビルド＋`build/verify/`（全パッチを元 APK に当てる）＋診断のテスト項目。

**Spec:** `docs/DESIGN.md` §5.12（0.13）。§10（安全設計）と §5.2・§5.4・§5.7〜§5.10 も参照。

## Global Constraints
- 対象は versionCode 2081362。難読化名（`kqg`・`hut`・`lna` など）はコードに書かない。fingerprint は文字列・リソース名・難読化されていないクラスで探す（CLAUDE.md）。
- P8: 新しい見た目を作らない。元の部品（layout・View・文字列・ダイアログ）を使う。元にないものだけ jadx / apktool から違いなく写す。
- リソース ID はパッチの実行時に名前から引く。拡張側も `getIdentifier`（結果はキャッシュ）。
- どのフックも `Throwable` を捕まえ、失敗したら元の動きに戻す（§6.2）。診断ログ（`DiagLog`）に残す。
- 自前のファイル操作は §10 の不変条件を守る（上書きしない、`Android/`・`.FilesPlus` に書かない、名前の予約）。
- ボリュームをまたぐ貼り付けは S4 まで、50 件超か合計 100MB 超は S6 まで行わない。「コピー」は S4 まで出さない。SMB はクリップボードの元にも先にもならない（S9 まで）。
- 文言は元アプリの文字列を優先。自前の文言は日英。
- 拡張の dex のメソッド数は 65,536 未満（今 約 53K）。
- ワーカーはコミットしない。1 タスク = 1 コミット（`feat:` / `fix:` / `chore:`）。

## Review Focus
1. **元の画面の引数が合わない**（proto の変化、`kqg` の欠け）→ 元の画面は onCreate で落ちる（P0）。期待: 起動の前の検査で止めて知らせ、落ちない。→ Task 2・6・7 の確認項目に「わざと壊した引数を渡そうとして、起動せずに知らせること」を入れる。
2. **貼り付け中にクリップボードの項目が消えている／名前が変わっている**（ほかのアプリで消した、SD を抜いた）→ 期待: その項目だけ飛ばし、件数を知らせる。ほかは続ける。→ Task 4。
3. **貼り付け先が移動元のフォルダの中**（フォルダを自分の子へ）や同じフォルダ → 期待: ボタンが押せず理由が出る。→ Task 4。
4. **プロセスが殺されたあとに開く**（ColorOS）→ 期待: クリップボードは残り、24 時間を過ぎたものは捨てる。貼り付けの途中で殺されたものは §10 の回復に任せ、二重に動かさない。→ Task 4。
5. **SMB の接続が切れている・認証が失敗する状態で「すべてのストレージ」の行を押す** → 期待: 元の画面のエラー表示か Toast で知らせ、落ちない。→ Task 7。

---

> **Task 0・Task 1 は済み（2026-11-13）。** 結果は `docs/research/2026-11-12-original-screens-p0.md`（以下「P0 報告」）と DESIGN §5.12（0.14）。下のチェックリストは経緯として残す。

### Task 0: 調査（P0。メインの Opus が行う。コードは書かない）

**出力:** DESIGN §5.12.1 に追記（下の各項目の答え）。Task 1 以降のタスクの指示書に、fingerprint の目印と値を入れる。

- [ ] `kqg` の `f`、oneof、`g`/`i`/`j` の意味（`kqg.a.n()` を使う dkc/gip/icb/ikr/iop/jdu/opz/pyx）。`hut` の 5 番目。ブラウザ（`AdvancedBrowsingActivity`）の `activity_params` の型（`hut` か包みか）。
- [ ] ストレージブラウザの fragment（`advanced_browsing_browser_fragment_v2`）とメディアブラウザ（カテゴリのタブ画面）に、下端のボタン欄（`framed_single_button_view` / `button_section`）を置けるか。置けないときの差し込み先。
- [ ] メディアブラウザをカテゴリとタブを指定して開く方法（ホームのカテゴリの行のクリック → Intent／引数）。
- [ ] 選択モードのメニュー（メディア: `tabbed_fragment_menu_v2` の `ixh`、ストレージ: `advanced_browsing_action_menu_v2`）、ファイルの︙（`advanced_browsing_file_dropdown_menu` ほか）、プレビューの︙に項目を足す継ぎ目と、選択（ファイルの URI / パス）の取り方。
- [ ] 設定 `lna.ew()` への差し込み: Preference のメソッドの参照を命令から読む方法、Preference の Intent でのクリックが R8 後も残るか、`SettingsActivity` のタイトル。文字入力に使える元のダイアログ。
- [ ] SMB: `qgc.l` → UNKNOWN の後の経路（`iaj.O` ほか）、`qei` の子の問い合わせ、読み取り専用での操作メニューの出方、プレビューで開けるか。PC で確かめられる範囲。
- [ ] 「すべてのストレージ」の一覧（`root_container_list_v2_fragment`）のデータの型と、行を足す継ぎ目。
- [ ] ゴミ箱（`TrashActivity`）の一覧のデータと行の型、元の期限の日数。
- [ ] ダウンロードのカテゴリ画面のチップ（`file_filter_*`）の有無と継ぎ目。動画のサムネイルの作り方。プレビューの長押しの処理。
- [ ] 元アプリの文字列に「切り取り」「貼り付け」「元に戻す」があるか。

### Task 1: 元の画面の引数の型紙（静的解析。P0 の続き。メインの Opus）
**方針（ユーザー指示 2026-11-12）**: 実機での確認は使わない。静的解析だけで解明する（dex は jadx の通常＋fallback 出力、ネイティブのライブラリがあれば idalib-mcp）。
- [ ] 元のコードで `hut` / `huv` / `kqg` を組み立てている箇所（内部ストレージ・SD を開くとき、「移動」で picker を開くとき、ゴミ箱を開くとき）を読み、各フィールドに入る値を決める。`vmi` の記述子（フィールド番号と型）と突き合わせる。
- [ ] 結果を DESIGN §5.12.1 に「型紙」（フィールド番号・型・値の出どころ）として書く。Task 2 の指示書にそのまま入れる。

### Task 1b: proto の読み書きと `activity_params` の包み（sonnet / medium）

**Files:**
- Create: `extensions/.../ui/ScreenArgs.java`

**Interfaces:**
- Produces: `ScreenArgs.wrap(byte[] proto): Bundle`（`Bundle{"protoparsers": ProtoParsers$InternalDontUse(bytes, null)}`。Parcelable はリフレクションで作る。P0 報告 §1.1）、`ScreenArgs.unwrap(Parcelable): byte[]`（Bundle でも Parcelable 単体でも受ける。picker の結果の読み取りに使う）、`ProtoNode.decode(byte[])`／`ProtoNode.encode(): byte[]`（フィールド番号で読み書きする最小の proto の木。未知のフィールドもそのまま保つ）、`ScreenArgs.dump(byte[]): String`（診断用。16 進と木）。
- [ ] `ProtoNode` の往復（読む → 書く）でバイト列が変わらないこと、Task 1 の型紙から作ったバイト列を読み直して同じ値になることを、JVM で動く小さな自己テスト（診断の「自己テスト」から呼べる形）で確かめる。
- [ ] ビルド `bash scripts/build.sh --no-daemon`、`build/verify/` で全パッチを当てられること。

### Task 2: 元の画面を開くランチャー（sonnet / high）

**Files:**
- Create: `extensions/.../ui/OriginalScreens.java`

**Interfaces:**
- Consumes: `ScreenArgs.wrap`・`ProtoNode`（Task 1b）、Task 1 の型紙
- Produces:
  - `OriginalScreens.openStorage(Activity from, List<FolderRef> stack, boolean replace)`（ストレージブラウザ。`stack` はルート → 開くフォルダ。`replace` なら `from` を閉じる）
  - `OriginalScreens.openMedia(Activity from, Category c, String folderPathOrNull, boolean replace)`（Task 0 の方法で）
  - `OriginalScreens.pickFolder(Activity from, int requestCode)`／`OriginalScreens.readPicked(Intent result): String`（元の「移動」画面。結果の移動先のパス）
  - `record FolderRef(String uri, String title, int storageKind)`（`kqg` の e・表示名・h）
  - `OriginalScreens.stackFor(File folder): List<FolderRef>`（ボリュームのルートから）
- [ ] P0 報告 §1.5 の型紙どおりにバイト列を作る（ストレージブラウザ `hus{1: hut}`、picker `huv{1: hut}`、メディアブラウザ `jcp{1: タブ名, 3: カテゴリ, 4: 1}`）。**起動の前に検査**し（読み直し、ルートの `kqg` の e・f・表示名、パスの実在）、だめなら起動せず `false` を返す（元の画面は引数が悪いと onCreate で落ちるため）。
- [ ] 積みが 2 段以上のとき: パッチ "Open folder stack"（新規、`patches/.../screens/OpenStackPatch.kt`）で `AdvancedBrowsingActivity.onCreate` の「最初の fragment を置く」呼び出しを拡張 `OriginalScreens.placeInitialFragments(...)` に差し替え、1 段ごとに fragment を足す（P0 報告 §1.7 の案 A）。目印は難読化されていない `AdvancedBrowsingActivity` と `R.layout.advanced_browsing_activity`。差し替えが失敗したら元の 1 個だけ置く。
- [ ] 診断に「ストレージブラウザで DCIM を開く（積み 2 段）」「メディアブラウザで画像の Camera タブを開く」「壊した引数を渡そうとする（起動せずに知らせること）」のテスト。
- [ ] ビルドと `build/verify/`。

### Task 3: Mod 設定を元の設定画面へ・自前の UI の削除（P1。sonnet / high）

**Files:**
- Create: `patches/.../settings/SettingsInjectionPatch.kt`（`ew()` の `ABOUT_FILES_PREF_KEY` を目印に、最後の `p(screen)` の前で `SettingsPages.build(Object screen, Context ctx, Intent intent)` を呼ぶ。Preference のメソッドの参照は命令から読み、ブリッジのクラスを生成する）
- Create: `extensions/.../settings/SettingsPages.java`（ページの定義。今の `ModSettingsActivity` の項目をそのまま移す）、`extensions/.../settings/PrefBridge.java`（パッチが生成したブリッジを呼ぶ口）
- Delete: `settings/ModSettingsActivity.java`・`settings/SettingsLook.java`・`ui/FilesLook.java`・`ui/UiSample.java`・`browse/BrowseActivity.java`・`browse/{CategoryScreen,FolderScreen,RootScreen,Row,Screen,PickRequests}.java`・`move/DestinationSheet.java`・`move/FolderBrowser.java`・`ui/ThumbLoader.java`（使い道がなければ）、manifest の該当 activity、パッチ "Move queue in preview"（`PreviewMoveQueuePatch.kt`）と "Move to current tab"（`MoveToCurrentTabPatch.kt`）と関連の拡張コード。**ただし "Move to current tab" が持つメディアブラウザの選択を取るフックと、今のタブのフォルダの解決は Task 5 で使う**（P0 報告 §4.1）ので、メニュー項目だけを消し、フックの部分は残すか Task 5 のパッチへ移す
- Modify: `settings/SettingsHooks.java`（入口を元の設定画面へ）

**Interfaces:**
- Produces: `SettingsPages.open(Context, String pageId)`（`SettingsActivity` を目印 extra `filesplus_page` で開く）、ページ id: `root`・`tabs`・`skip`・`preview`・`clipboard`・`switch`・`smb`・`diag`
- [ ] 元の設定画面の最後にカテゴリ「FilesPlus」と項目が出る。サブページのタイトルが替わる。
- [ ] 文字入力・診断ログは Task 0 で決めた元のダイアログ（なければ写したもの）で。
- [ ] 消した機能の設定キーは読まないだけにする（データは消さない）。
- [ ] ビルド、`build/verify/`（`VerifyRewind.java` の Set からも消したパッチを外す）、dex のメソッド数。
- [ ] リリース（P1 単独で可）。CHANGELOG はコミットのメッセージで（手で書かない）。

### Task 4: クリップボードと貼り付けのコア（P2 前半。sonnet / high）

**Files:**
- Create: `extensions/.../clip/Clipboard.java`、`extensions/.../clip/PasteTarget.java`、`extensions/.../clip/PasteRules.java`
- Modify: `move/MoveQueue.java`（貼り付けのジョブを受ける）、`move/DestinationHistory.java`（最後に貼り付けたフォルダだけに縮める）

**Interfaces:**
- Produces:
  - `Clipboard.get(Context): Clipboard.State`（`mode` = CUT、`items: List<ClipItem>`、`createdAt`）。`Clipboard.cut(Context, List<ClipItem>)`（入れ替え）、`Clipboard.add(Context, List<ClipItem>)`、`Clipboard.clear(Context)`、`Clipboard.addListener(Runnable)`。保存は SharedPreferences `filesplus_clipboard`。24 時間を過ぎたら `get` が空を返して消す。
  - `record ClipItem(String path, long mediaId, String name, long size, boolean isDir)`
  - `PasteRules.check(Clipboard.State s, PasteTarget t): PasteRules.Result`（`ok` か理由: `SAME_FOLDER`・`INTO_SELF`・`NOT_WRITABLE`・`PROTECTED`・`CROSS_VOLUME`・`TOO_LARGE`（50 件超か 100MB 超）・`SMB`・`ALL_TAB_DISABLED`・`EMPTY`）
  - `PasteTarget.ofFolder(File)`、`PasteTarget.ofAllTab(Category, String setting)`（`paste.allTab` の値: `off`（既定）・`default`・`last`・`ask`）
  - `MoveQueue.paste(Context, Clipboard.State, File dest, Callback)`（なくなった項目は飛ばして数える。切り取りは成功したら `Clipboard.clear`。`DestinationHistory.recordLast(dest)`）
- [ ] `PasteRules` の各理由を、診断の「貼り付けの判定テスト」で確かめる（一時フォルダを作って: 同じフォルダ、子への貼り付け、`Android/`、`.FilesPlus`、SD のパス、51 件、消えた項目）。JVM テストのモジュールがあればそちらに書く。
- [ ] Review Focus 2・3・4 を上のテストに含める。
- [ ] ビルドと `build/verify/`。

### Task 5: 入口と下端のボタン（P2 後半。sonnet / high）

**Files:**
- Create: `patches/.../clip/ClipboardPatch.kt`（パッチ "Clipboard"。選択メニュー・ファイルの︙・プレビューの︙に「切り取り」「クリップボードに追加」、両ブラウザの下端のボタン欄、上の︙の予備の項目）
- Create: `extensions/.../clip/ClipHooks.java`（smali からの入口）
- Modify: `preview/PreviewHooks.java`（「移動先へ送る…」の残りを消し「切り取り」を足す）、`foldertabs/FolderTabContext.java`（今のタブのフォルダを `PasteTarget` にする）

**Interfaces:**
- Consumes: Task 4 のすべて、Task 2 の `OriginalScreens.pickFolder/readPicked`（`paste.allTab` = `ask`）
- Produces: `ClipHooks.onMenuCreated(Object host, Menu m)`、`ClipHooks.onMenuItem(Object host, MenuItem i): boolean`、`ClipHooks.attachBar(View root, Object host)`（Task 0 で決めた継ぎ目に合わせて名前を調整してよい）
- [ ] 下端のボタンは元の部品。押せないときは `PasteRules.Result` の理由を短く出す。
- [ ] 設定ページ `clipboard` に `paste.allTab`。
- [ ] ビルド、`build/verify/`、リリース。

### Task 6: 表示の切り替え（P3。sonnet / medium）

**Files:**
- Create: `patches/.../browse/ViewSwitchPatch.kt`、`extensions/.../browse/ViewSwitch.java`
- Modify: `browse/LocalSource.java`（`dominantCategory(File dir): Category` を足す）

**Interfaces:**
- Consumes: Task 2 の `openStorage/openMedia/stackFor`
- Produces: `ViewSwitch.toMedia(Activity, File folder)`（`switch.toMediaCategory`: `auto`（既定）/ `ask`。メディアがなければダウンロード）、`ViewSwitch.toStorage(Activity, Category, String folderOrNull)`（`switch.allTabTarget`: `default`（既定）/ `last` / `latest` / `root`）
- [ ] `dominantCategory` の同数の順は 画像 ＞ 動画 ＞ 音声 ＞ ドキュメント。
- [ ] 切り替えは `replace = true`。
- [ ] ビルド、`build/verify/`。

### Task 7: SMB をストレージブラウザで（P4。SMB 計画のタスク 4・5 を置き換え。sonnet / high）

**Files:**
- Create: `patches/.../smb/SmbStorageRootsPatch.kt`（「すべてのストレージ」に接続先の行）、`extensions/.../smb/SmbRoots.java`
- Modify: `smb/SmbDocumentsProvider.java`（tree の子の問い合わせ、flags は読み取り専用）、manifest の provider（`DOCUMENTS_PROVIDER` の intent-filter があることを確かめる）

**Interfaces:**
- Consumes: Task 2 の `openStorage`、`SmbConnectionStore`
- Produces: `SmbRoots.rows(Context): List<SmbRoots.Row>`、`SmbRoots.open(Activity, String connectionId)`（tree URI の `FolderRef`、`storageKind` = UNKNOWN）
- [ ] 読み取り以外のメニュー（削除・名前の変更・移動・コピー・圧縮・新しいフォルダ・FAB）を隠すフックは**必須**（P0 報告 §6.3: 元アプリは document の flags を見ない）。
- [ ] provider はルートの document に 1 行で答える（`kqg.f` が解決できないとブラウザが閉じる）。
- [ ] 診断「SMB をストレージブラウザで開く」。Review Focus 5（切断・認証失敗）。
- [ ] 接続先の編集は設定ページ `smb`（Task 3）。
- [ ] ビルド、`build/verify/`。

### Task 8: 空のフォルダ・圧縮ファイル・プレビューの長押し（P5。sonnet / medium。3 つは別コミット）

**Files:**
- Modify: `patches/.../foldertabs/ShowEmptyFolderTabsPatch.kt`・`foldertabs/FolderTabContext.java`（ダウンロードのカテゴリだけ。空かは実際の中身で。`.` と `.nomedia` は数えない）
- Create: `patches/.../browse/ArchiveFilterPatch.kt`（ダウンロードのチップ「圧縮ファイル」: zip, rar, 7z, tar, gz, tgz, bz2, xz, zst, lz4）
- Modify: `patches/.../preview/PreviewMultiSelectPatch.kt`・`preview/PreviewHooks.java`（長押し → 選択の切り替え。設定 `preview.longPressSelect`、既定 ON）
- [ ] それぞれビルド、`build/verify/`。

### Task 9: 動画のサムネイル（P6。調査の結果で指示書を書く。sonnet / medium）
- P0 報告 §9.2: 一覧は Glide（cacheDir、250MB）。Glide のリクエストを組む所で、保存済みのサムネイルがあればその `file://` に替える。キーはパス・サイズ・更新日時、上限と消し方は §5.3.1 と同じ。

### Task 10: ゴミ箱（P7。§10 の S5 のあと。sonnet / high）

**Files:**
- Create: `patches/.../trash/FolderTrashRowsPatch.kt`、`extensions/.../foldertabs/TrashRows.java`
- Modify: `foldertabs/FolderTrash.java`（復元・完全削除・期限）

**Interfaces:**
- Consumes: Task 2 の `openStorage`（ゴミ箱のフォルダのモード）、§10.6 のマニフェスト
- Produces: `FolderTrash.list(Context): List<TrashedFolder>`、`FolderTrash.restore(Context, TrashedFolder, List<String> relPathsOrAll)`、`FolderTrash.purge(Context, TrashedFolder)`
- [ ] 先に調査: ゴミ箱のタブ（`List<lwr>`）に「FilesPlus」タブを足す方法と、タブの中身（フォルダを並べる行の部品、タブのクエリ）（P0 報告 §8。0.14 でユーザーが「タブを 1 つ足す」に決定）。ファイル名・クラス名は調査の結果で決める。
- [ ] 元のゴミ箱の画面に「FilesPlus」タブ。中のフォルダを押すとストレージブラウザ（「元に戻す」だけのメニュー）。フォルダの︙か選択メニューに「すべて元に戻す」「完全に削除」。期限は `trash.days`（7/30/60、既定 30）。
- [ ] ビルド、`build/verify/`。

## 順序とリリース
- Task 0 → 1 → 1b → 2 → 3（リリース可）→ 4 → 5（リリース）→ 6 → 7 → 8 → 9。Task 10 は §10 の S5 のあと。
- §10 の S4（コピー、ボリュームをまたぐ貼り付け）・S6（大きな貼り付け）は、Task 5 のあとに優先して行う（別の計画: §10.12）。
- 各タスク: 実装者（Sonnet、worktree `E:/Projects/ModdedFiles-wt/<name>`）→ メインでビルドと `build/verify/` → Opus のレビュー → 修正 → コミット・dev にマージ。
