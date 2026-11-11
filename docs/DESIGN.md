# Files by Google 改造（FilesPlus）設計書

- 文書バージョン: 0.8（Phase 2 の残りを簡略版で実装）
- 作成日: 2026-11-11

### 改訂履歴
| 版 | 内容 |
|----|------|
| 0.1 | 初版 |
| 0.8 | 空のフォルダのタブ（§5.9）と現在のタブへ移動（§5.10）の最初の実装範囲を決定（JobQueue などは Phase 3 で導入）。巻き戻しオプション（§5.1）の解析結果を追記 |
| 0.7 | 実機（OPPO A54 5G）の診断結果を反映。ハードウェアデコーダは縦横 1920 まで。2K は L1＋標準のソフトウェアデコーダで再生できた。4K は標準のソフトウェアデコーダではカクつくが、VLC はハードウェアアクセラレーション無効でも滑らか → **L2 のエンジンは libVLC**（FFmpeg＋独自 Media3 案は採らない）。順序は Phase 2 を先に行う（§5.5 の 0.7、§7） |
| 0.6 | SMB の接続先の保存、タップだけでの再接続、自動再接続、失敗時の扱いを追加（§5.3.2） |
| 0.5 | 解析で「その他のストレージ」の provider は Files 内で開けないと判明したため、SMB を「既存の入口＋元アプリと同じ見た目の SMB ブラウザ＋元アプリのプレビュー」に変更（§5.3）。SMB のサムネイルキャッシュと再生成（§5.3.1）を追加。P6（SMB を単独アプリにもする）は取り下げ |
| 0.4 | 方針 P7 に沿って全体を見直し: 実装が1つしかない interface（SeekPolicy / SelectionStore / MoveQueue）を具象クラスに、使わない `SafPath` を削除、ModKit 用 Media3 の同梱は必要になる段階（L3・FFmpeg）まで遅らせる。方針 P7「既存機能の組み合わせで済む操作は新設しない／共通化は効率を損なわない範囲で」を追加。フォルダ削除から「カテゴリのファイルだけを削除」を外し、「全選択 → 削除」（既存機能）に任せる。`FolderTabContext` を必要最小限に縮小 |
| 0.3 | フォルダタブ画面を全カテゴリ共通の部品として整理（§5.7）。「現在のフォルダタブへ移動」（§5.10）と空フォルダのタブ表示の設定（§5.9）を追加。端末が OPPO A54 5G と判明（§5.5）。パッチの置き場所を GitHub（`yak9909/FilesPlus`）に決定 |
| 0.2 | 配布・適用を **Morphe のパッチバンドル**に変更（root なし・adb なし）。フック位置の管理を yaml から fingerprint に変更。フォルダ削除の対象画面（画像一覧のフォルダタブ）を特定。2K/4K が再生できない件を §5.5 に反映 |

### ユーザー環境（0.2 で判明）
- root なし。adb はなるべく使わない。**Morphe Manager は導入済み**
- Files は**システムアプリ** → パッケージ名を変えて共存させる
- 端末: **OPPO A54 5G**（Snapdragon 480、64bit CPU で 32bit アプリも動く、ColorOS）
- パッチのビルドと配布: **GitHub**（https://github.com/yak9909/FilesPlus 、GitHub Actions でビルド・リリース）
- 2K・4K の動画が再生できなかった
- 対象 APK: `com.google.android.apps.nbu.files` 1.13157.1011540316.0-release（versionCode 2081362）
  - minSdk 31（Android 12+）、ABI は **armeabi-v7a のみ**、640dpi split、24言語 split
  - base.apk 内 dex 4本（classes.dex〜classes4.dex）、R8 で強く難読化（アプリ本体ロジックの大半は `defpackage.*` の3〜4文字クラス）
- 改変手順（ビルド・署名・インストール）は [BUILD_PROCEDURE.md](BUILD_PROCEDURE.md) を参照

---

## 1. 目的と方針

### 1.1 目的
Files by Google の見た目・操作感を保ったまま、閲覧・整理まわりの機能を足す。

### 1.2 設計方針
| # | 方針 | 理由 |
|---|------|------|
| P1 | **既存 UI のデザインは変えない**。足す UI は「既存メニューへの項目追加」「長押し」「必要なときだけ開くボトムシート」に限る | ユーザー要件 |
| P2 | **改造コードは独立したモジュール（ModKit）に分け、元アプリの smali への変更は「1か所＝1行の `invoke-static`」程度のフックに抑える** | 難読化名はバージョンごとに変わる。差分を最小にすれば追従しやすい |
| P3 | **フック位置はバージョンごとのマッピングファイルで管理し、ModKit 本体は難読化名を直接知らない** | 抽象化。アップデート時はマッピングとアダプタだけを直せばよい |
| P4 | ファイル操作・キュー・設定・履歴は**共通基盤**として一度だけ実装し、各機能から使う | 共通処理の抽出 |
| P5 | 元アプリの Media3（ExoPlayer）は難読化済みで公開 API として使えない。**設定の変更で済むものは元アプリのプレイヤーにフックして行い**、公開 API が必須の処理（L3 の変換、FFmpeg）が必要になった段階で ModKit 側に別の Media3 を同梱する | 難読化された内部 API への依存を最小にしつつ、APK サイズ（Media3 の二重持ち）を必要になるまで増やさない（P7） |
| P6 | ~~元アプリなしで成り立つ機能（SMB）は外部アプリとしても動く形で作る~~（0.5 で取り下げ: SMB は Files 内のブラウザが必要になったため、改造版の中だけで作る） | — |
| P7 | **既存機能の組み合わせで実現できる操作は新設しない**。共通化・抽象化は**実際に2か所以上で使うもの**に限り、使う予定のない拡張点（将来のための interface など）は作らない。速度が要る処理（一覧の生成、再生）では抽象化のための余計な層・コピー・変換を挟まない | 操作の重複は UI を汚す。過度な抽象化はコード量と実行コストを増やす（0.4、ユーザー指摘） |

---

## 2. 解析で判明した事実（フック候補）

jadx 1.5.6 / apktool 3.0.3 で base.apk を解析した結果。クラス名は**このバージョン固有**なので、マッピング（§6.3）でのみ参照する。

| 領域 | 場所（このバージョン） | 内容 |
|------|--------------------|------|
| 時間型 | `defpackage.qhs` | 時間を表すラッパー。`qhs.h(long)`＝秒、`qhs.f(long)`＝ミリ秒 |
| 動画プレビューの中心 | `defpackage.jpx`（`jkw` を実装） | 動画プレビューのロジック。`static final qhs b = qhs.h(10)`、`c = qhs.h(10)` が**ボタンでスキップする秒数**。`Z = qhs.h(5)`、`aa = qhs.h(1)` も存在（キー操作用と推定・要確認） |
| スキップボタン | `VideoControlView` → `fbg(17)`→`jji` イベント → `iaj.G()` が `jpy(jpx,4)` に配線 | `jpy` case 4 で `kxj.b().i(jpx.b)`（現在位置 − 10秒）。早送りはもう一方の case で `jpx.c` を使う |
| ダブルタップスキップ | `defpackage.kwd.b(MotionEvent)`（`QuickSeekOverlayView` の peer） | `qhs.h(10L)` を4か所に直接書いている。画面の左1/3＝戻る、右1/3＝進む |
| プレイヤー抽象 | `defpackage.kxj`（interface） | アプリ内部で使うプレイヤーのインターフェース |
| ExoPlayer 生成 | `defpackage.kxo` | `new eds(...)`（LoadControl）→ `new edz(ctx)`（ExoPlayer.Builder）→ `edz.a()` で生成し、`VideoPlayerView` の SurfaceHolder に接続する |
| Media3 | `androidx.media3.*` は**ほぼ難読化済み**（`eeq`＝ExoPlayerImpl、`eoy`＝MediaCodecVideoRenderer 相当） | 外からは公開 API として使えない → P5 |
| 外部ストレージ一覧 | `defpackage.gip` case 4、`imh.a()`、`imq` | `android.content.action.DOCUMENTS_PROVIDER` で**端末上の DocumentsProvider を列挙**して「その他のストレージ」に出す。**タップすると provider のアプリ（またはシステムのファイル画面）を起動するだけで、Files 内では開かない**（0.5 で判明）→ SMB はタップ時の分岐にフックして ModKit の SMB ブラウザを開く（§5.3） |
| フォルダ削除確認 | `defpackage.ijo` / `ijp` | 「フォルダ内のファイルも削除されます（100件超は"100+"）」の確認ダイアログが**すでにある** → 再利用する |
| プレビュー画面 | `FilePreviewActivity`、`fragment_file_preview_gallery.xml`、`video_preview_item_fragment.xml`、`ImagePreviewView`（davemorrissey SubsamplingScaleImageView） | 拡大表示・動画再生の画面 |
| 文言 | `quick_seek_discovery`「左右をダブルタップで10秒スキップ」、`media_controls_*_10_description`、`quick_seek_duration`「%1$d seconds」 | 秒数を変えたら文言も合わせる |
| 画像 | Glide（`com.bumptech`）同梱 | GIF の表示に使われている可能性が高い（要確認） |

> 「未確認」「推定」と書いたものは、実機でログを取って確かめる（§9）。

---

## 3. 全体アーキテクチャ

```
┌────────────────────────── 改造 APK ───────────────────────────┐
│                                                             │
│  元アプリ（smali, 難読化）                                     │
│   ├─ jpx / kwd / kxo / FilePreviewActivity / 設定画面 …        │
│   │     │  ← 1行の invoke-static フック（最小差分）              │
│   ▼     ▼                                                    │
│  ┌──────────── Hook 層（バージョン固有）─────────────┐         │
│  │ Hooks.java  … 静的入口（smali から呼ばれる）         │         │
│  │ adapters/v2081362/ … 難読化クラスとの橋渡し           │         │
│  │   （stub jar に対して compileOnly でビルド）          │         │
│  └──────────────────────┬─────────────────────────┘         │
│                         │ 抽象インターフェースのみ              │
│  ┌──────────── ModKit Core（バージョン非依存）────────────┐    │
│  │ feature/  seek, selection, movequeue, media, smb,     │    │
│  │           foldertabs, folderops, emptyfolder          │    │
│  │ platform/ vfs, fileops, jobqueue, settings, history,  │    │
│  │           ui-kit（元アプリのテーマを借りる小部品）        │    │
│  └───────────────────────────────────────────────────┘    │
│  別途同梱: Media3（ModKit 用・パッケージ名を変えたもの）、smbj、 │
│            （任意）FFmpeg デコーダ                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.1 レイヤーの責務
| レイヤー | 責務 | 元アプリのバージョンが変わったとき |
|---------|------|----------------------|
| smali フック | 元アプリの処理の途中で `Hooks.xxx()` を呼ぶだけ | 位置を探し直す（パッチスクリプトのアンカーを直す） |
| Hook 層 | 難読化クラスと ModKit の抽象型を相互に変換する | アダプタを直す |
| ModKit Core | 機能本体 | **変更なし** |

### 3.2 フックの方式（0.2 で決定: Morphe）
- **Morphe のパッチバンドル（`.mpp`）**として作る。手順は BUILD_PROCEDURE.md。
  - **smali フック** = Morphe の `patch { execute { … } }` で、fingerprint で見つけたメソッドに `invoke-static Hooks.xxx()` を差し込む。
  - **Hook 層＋ModKit Core** = Morphe の **extension**（`extensions/extension`、dex になってアプリに合流する）。
  - **リソースの追加**（メニュー項目、文字列、`<provider>`）= Morphe のリソース編集（`resourceXml` / `document`）。
- 機能ごとにパッチを分けるので、**Morphe Manager の画面でほしい機能だけを選んで当てられる**。
- Hook 層の入口は `Hooks` に統一しておく。将来 Xposed（LSPatch など）で動かす場合も Core はそのまま使える。

---

## 4. 共通基盤（platform/）

### 4.1 VFS（仮想ファイルシステム）— ローカル / SMB をまとめて扱う
```kotlin
sealed interface VPath { val scheme: String }      // file://, smb://
data class LocalPath(val file: java.io.File) : VPath    // 元アプリは全ファイルアクセス権を持つので SD カードもこれで扱える
data class SmbPath(val serverId: String, val share: String, val path: String) : VPath

interface VfsProvider {
    fun supports(p: VPath): Boolean
    suspend fun stat(p: VPath): VStat?                    // サイズ、更新日時、ディレクトリかどうか
    suspend fun list(dir: VPath): List<VEntry>            // 空ディレクトリも返す
    suspend fun openRead(p: VPath, offset: Long = 0): SeekableSource
    suspend fun openWrite(p: VPath, overwrite: Boolean): Sink
    suspend fun mkdirs(p: VPath)
    suspend fun delete(p: VPath, recursive: Boolean)
    suspend fun renameOrMove(src: VPath, dst: VPath): Boolean  // 同じ provider 内の高速移動。できなければ false
    // 実装はローカルと SMB の2つ（P7: 2つあるので interface にする）
}
object Vfs { fun of(p: VPath): VfsProvider }              // provider を登録して選ぶ
```
- `SeekableSource` は Media3 の `DataSource` に変換でき、SMB 上の動画もそのまま再生できる。

### 4.2 FileOps（移動・コピー・削除エンジン）
- 操作 `Op = Move | Copy | Delete(recursive)` を `Plan`（展開済みの作業一覧）にしてから実行する。
- 移動は「同じ provider 内なら `renameOrMove` → だめならコピー＋検証（サイズ）＋元を削除」。
- 名前がぶつかったとき（`ConflictPolicy`）: `ASK`（既定）/ `RENAME`（"name (1).ext"）/ `SKIP` / `OVERWRITE`。
- 実行後は `MediaScannerConnection` / MediaStore を更新し、元アプリの一覧に反映させる。
- 進捗は `Flow<OpProgress>` で流す。キャンセルとエラー一覧も扱う。

### 4.3 JobQueue（永続キュー）
- 移動キュー・フォルダ削除・SMB 転送を同じ仕組みで動かす。
- 保存先は ModKit 専用の SQLite（`modkit.db`。元アプリの DB には触らない）。
- 直列実行（宛先ごと）、WorkManager による再起動後の再開（WorkManager は元アプリに同梱されているが難読化済みなので、ModKit 用に別に持つか自前の Foreground Service にする → **自前サービス**に決める）。
- 状態: `PENDING → RUNNING → DONE / FAILED / CANCELLED`、取り消し（Undo）用に移動元・移動先を記録する。

### 4.4 Settings
- `SharedPreferences("modkit_prefs")` を型付きで包む（`ModSettings.skip.doubleTapSec` など）。
- 入口: 元アプリの設定画面（`SettingsActivity`）の末尾に **「Mod 設定」を1行だけ足す** → ModKit の PreferenceFragment を開く（元アプリのテーマを流用）。

### 4.5 History（移動先の履歴）
- `DestinationHistory`: MRU（最近使った順）＋ピン留め＋使用回数。上限50件、存在しない宛先は灰色表示。
- 移動キューとフォルダ選択画面で共通に使う。

### 4.6 UI Kit
- 新しい画面は作らず、**元アプリのテーマ（Material 3 / 元アプリの色）を継承した BottomSheet・Dialog・Snackbar** だけを用意する。
- ModKit のリソースは `mod_` 接頭辞を付けて APK に追加し、コードからは名前で引く（`ModRes.layout("mod_queue_sheet")`）。別ビルドのため R の ID を固定できないことへの対策。

---

## 5. 機能別設計

### 5.1 スキップ秒数の設定（要件: 3/5/10秒の設定、動画 n 秒スキップ）
**設定項目**
| キー | 既定 | 選択肢 |
|------|------|--------|
| `skip.doubleTapSec` | 10 | 3 / 5 / 10 / 任意（1〜600） |
| `skip.buttonSec` | 10 | 同上（ダブルタップと別に設定できる） |
| `skip.accumulate` | true | 連続ダブルタップで加算するか（元の動作は加算） |

**フック**
1. `kwd.b()` の `qhs.h(10L)` 4か所 → `Hooks.doubleTapSkip()`（`qhs` を返す）に置き換える。
2. `jpy` の rewind/ffwd の case で読んでいる `jpx.b` / `jpx.c` → `Hooks.buttonSkip()` に置き換える。（`jpx.<clinit>` の値だけを変える方法もあるが、それだと起動中に設定を変えても反映されない）
3. 文言: `quick_seek_duration` は `%d` を使っているのでそのまま使える。`quick_seek_discovery` と `media_controls_*_10_description` は ModKit が秒数入りの文言を返す（アクセシビリティ用の説明文も合わせる）。

**UI を変えずに、その場で n 秒を切り替える**
- 早送り/巻き戻しボタンを**長押し**すると、小さなポップアップ（3 / 5 / 10 / 30 / カスタム）が出る。ボタンの見た目は変えない。

- 実装: フックは `ModSettings.skip.doubleTapSec` / `buttonSec` を読んで返すだけ（専用のクラスや interface は作らない。P7）。

**0.7 追加（ユーザー要望）: 再生位置が n 秒未満でも巻き戻せるようにする**
- 設定 `skip.rewindToStart`（**既定: ON**。Mod 設定 → スキップ に切り替えを追加）: 再生位置が n 秒未満のときに巻き戻し（ボタン・ダブルタップの両方）をすると、**先頭（0秒）へ戻る**。
- **実装結果（jadx で確認）**: ボタンは元から `max(0, 位置 − n)` でシークする（`jpy` の case 4: `l()` なら `qhs.a`）。`VideoControlView` でボタンを無効にするのは再生エラー時だけ。止まっていたのは**ダブルタップだけ**で、`kvz` 実装の `jpw.b(qhs)`（文字列 "rewind is called when videoPlayer is null." で特定）が判定している: 動画は `位置.i(n).m()`（結果が正のときだけ）、音声プレビューは `位置ms − nms >= 0`。両方の直前で巻き戻し量を `SkipHooks.rewindSkip(位置, n)` に差し替える（足りないときは `位置 − 1単位` を返し、動画は 1ns・音声は 1ms の位置＝実質先頭へ）。位置がすでに 0 なら元の挙動のまま。
- （実装前の推定）元アプリは「現在位置 − n 秒」が負になるとき巻き戻しをしない、と推定していた → 上の「実装結果」のとおりダブルタップだけだった。
- フック: 「現在位置 < n 秒なら何もしない」判定の箇所で、設定が ON なら「0 秒へシーク」に置き換える（位置の計算を `max(0, 位置 − n)` にする）。

### 5.2 拡大表示・再生しながらの複数選択（要件）
**挙動**
- プレビュー画面（画像の拡大表示・動画再生）で「選択」を切り替えられる。
- 選んだものは `SelectionStore`（ModKit 内、`Set<VPath>`、画面回転や Activity の再生成をまたいで保持）に入る。
- 一覧画面に戻ったとき、元アプリの選択モードにそのまま引き継ぐ（Phase 2。元アプリの選択モデルへのアダプタが必要）。Phase 1 では ModKit の操作（移動キュー・削除・共有）から直接使う。

**UI（見た目を変えない）**
- 既存の上部バーのオーバーフローメニューに「選択」「選択を解除」「選択中 n 件…」を追加する。
- 選択中のファイルを表示しているときだけ、右上に**小さなチェック印**を重ねる（ImagePreviewView / 動画の Surface の上にオーバーレイ1つ）。
- 任意設定: 音量キーで選択を切り替える（既定はオフ）。

```kotlin
object SelectionStore {                         // プレビュー（§5.2）とタブ切り替え（§5.10）で共通
    val selected: StateFlow<Set<VPath>>
    fun toggle(p: VPath); fun clear(); fun addAll(ps: Collection<VPath>)
}
```
**フック**: `FilePreviewActivity` のメニュー生成と、ページ切り替え（今表示しているファイルの通知）。

**調査結果（2026-11-11、versionCode 2081362。難読化名は目印だけ。フィンガープリントには使わない）**
- メニュー: `FilePreviewActivity` は器だけ。ギャラリーの fragment（`jju`、layout `fragment_file_preview_gallery`）が `(Menu, MenuInflater)V` で `R.menu.file_preview_menu`（0x7f0f000e）を inflate する（フォルダなどでは inflate せずに早く return する）。クリックは `aI(MenuItem)` の if 連鎖。目印の文字列: `"Fragment:onOptionsItemSelected"`、`"FilePreviewFragmentViewPager"`。項目は固定の int id で足し、選択が変わったら `invalidateOptionsMenu()` で作り直す。
- 今のファイル: ページ切り替えは `jko`（ViewPager2 の OnPageChangeCallback、`G(int)` = onPageSelected）。fragment の peer（`jkt`）の `c()` が今のファイルの proto（`kqi`）を返す。`c()` は `d(I)` を呼び、`d` に文字列 `"File preview index is not valid."` がある。`kqi.r` = MediaStore id（`b & 131072`）、`kqi.k` = content URI（`b & 256`）。どちらもないファイルでは「選択」を出さない。**実装の判断（0.9）**: 当面は MediaStore id のあるファイルだけに「選択」を出す（content URI だけのファイルには出さない。SelectionStore を id で引くため）。
- チェック印: ページのビューは使い回されるので、gallery の root（CoordinatorLayout。`toolbar` の親）に重ねるビューを1つだけ足す。クリックもフォーカスも受けないようにし、z 順に注意する（`scrim`、toolbar のグラデーション）。
- 回転で Activity は作り直されない（configChanges あり）。選択は拡張の static（`LinkedHashMap<id, kqi>`、型は Object で持つ）に置く。プロセスが終われば消えてよい。
- 一覧への引き継ぎ: 一覧の選択モデル（`lmq`、文字列 `SELECTED_DATA_STATE_TAG` / `SELECTION_DATA_SOURCE` で見つかる。MoveToCurrentTabPatch と同じ持ち主）の `(List)V`（`r`、`List<kqi>` を受ける）に、覚えておいた `kqi` を smali のブリッジで渡す。持ち主の resume や戻りでフックする。id だけから作り直すのは脆いので、`kqi` を持っておく。難しさは中〜やや脆い。

**分割（0.8）**: ①調査（済）→ ②`SelectionStore` とプレビューのメニュー（選択／選択を解除／選択中 n 件）→ ③チェック印と一覧の選択モードへの引き継ぎ。

**実装の状態（0.9、dev.7 で公開、実機は未確認）**: ②③とも実装済み（パッチ名 "Multi-select in preview"）。実装で決めたこと:
- `SelectionStore` は MediaStore id → 元アプリのエントリ（proto）の LinkedHashMap（`Set<VPath>` ではない）。content URI だけのファイルは選べない。「選択中 n 件」を押すと確認なしで解除する。
- チェック印は1つの自前の View（右上、ツールバーの右端に合わせる。ツールバーを隠すと印も隠れる）。
- 一覧への引き継ぎ: 選択モデル（`SELECTED_DATA_STATE_TAG` を持つクラス）をコンストラクタで拡張側に弱参照で登録し、プレビューが閉じたあとに resume した画面が持つモデルへ `(List, Z)V`（`false`）で足す。既存の選択は消さない。引き継ぐ直前に MediaStore で存在を確かめ、なくなったファイルは外す。外部アプリから開いたプレビューは閉じるときに選択を捨てる。
- 実機で確かめること: HANDOFF.md の項目（回転、ファイル情報を開いて戻る、検索・カテゴリ・ホームから開く、ゴミ箱の挙動、SD カード）。音量キーでの切り替え（任意設定）と、移動キュー・共有などから SelectionStore を直接使う操作は未実装。

### 5.3 SMB 接続（フォルダと同じように扱う）

**0.5 の訂正（解析結果）**: 元アプリは「その他のストレージ」に端末上の DocumentsProvider を列挙する（`gip` case 4）が、**項目をタップしても Files の中では開かない**。`imh.a()` が、一部の既知の provider（`imq.b`）はシステムのファイル画面（DocumentsUI）で開き、それ以外は **provider を持つアプリのランチャー画面を起動するだけ**（`getLaunchIntentForPackage`）。`DocumentsProviderBrowserActivity` は provider の一覧を出す画面で、中身を閲覧する画面ではない。したがって「provider を足せば Files 内でフォルダのように使える」という 0.4 までの前提は誤り。

**方式（0.5）**: 次の3つを組み合わせる。
1. **入口は元アプリの「その他のストレージ」の一覧をそのまま使う**。改造版に入れた `SmbDocumentsProvider` がそこに「SMB」として表示される。タップ時の分岐（`imh.a()`）にフックし、**自分の authority のときは ModKit の SMB ブラウザを開く**（ランチャー起動の代わり）。
2. **SMB ブラウザは元アプリの一覧と同じ見た目で作る**。元アプリのレイアウト・リスト項目（ファイル名・サイズ・日付・サムネイル・グリッド/リスト切替）・テーマ・選択時のメニュー構成を流用し、新しいデザインは作らない（P1）。中身は ModKit の VFS（`SmbVfsProvider`）から読む。
   - 元アプリの一覧画面そのもの（`AdvancedBrowsingActivity` など）に SMB を流し込む案は、元アプリの移動・削除・プレビューが `java.io.File` 前提で SMB では動かないため、フックが深く広くなりすぎる（P7）ので採らない。
3. **ファイルを開く・操作する**:
   - 画像・動画は、provider の `content://` URI を元アプリのプレビュー（`FilePreviewActivity`。外部の VIEW インテントを受ける入口がある）に渡して開く。拡大表示・シーク・スキップ秒数・GIF のシークなどの改造機能もそのまま効く。
   - 移動・コピー・削除・名前の変更・フォルダの作成は ModKit の FileOps（VFS 経由）で行う。移動キュー（§5.4）の移動先として SMB のフォルダも選べる。
- provider は**システムのファイル選択（SAF）にも出る**ので、ほかのアプリからも SMB のファイルを開ける（副次的な効果）。

**構成**
| 部品 | 内容 |
|------|------|
| ライブラリ | **smbj**（SMB2/3、Apache-2.0）。SMB1 は使わない |
| 接続先の保存 | §5.3.2 |
| 接続管理 | `SmbSessionPool`: サーバーごとにセッションを使い回す、アイドル時に切断、再接続。SMB ブラウザ・provider・FileOps・サムネイル生成で共有する |
| 一覧 | ディレクトリ一覧はメモリにキャッシュ（30秒、更新操作のあとは破棄）。引っ張って更新に対応 |
| 読み出し | provider の `openDocument` → `StorageManager.openProxyFileDescriptor`（ランダムアクセス可能）で、元アプリのプレビューでも動画のシークができる |
| 書き込み | 直接ストリームで書く（失敗したら途中のファイルを消す） |
| provider の操作 | SAF 用に `FLAG_SUPPORTS_DELETE / RENAME / WRITE / THUMBNAIL / DIR_SUPPORTS_CREATE` を立てる |
| サーバー探索 | 手入力が基本。任意で mDNS（`_smb._tcp`）で候補を出す |

**調査結果（2026-11-12、versionCode 2081362。難読化名は目印だけ）と決定（0.9）**
- 拡張の `AndroidManifest.xml` は空（`<manifest/>`）で、マージされない。`modKitManifestPatch`（`ModKitIntegrationPatch.kt`、resourcePatch）が DOM で `<application>` に要素を足している。**provider・activity・service も同じ方法で足す**（クラス名は完全修飾）。
- provider の条件（元アプリの一覧 `hyn` case 5）: `exported`、`grantUriPermissions`、`android:permission="android.permission.MANAGE_DOCUMENTS"`、intent-filter の action `android.content.action.DOCUMENTS_PROVIDER`。一覧は `queryIntentContentProviders` で作られる（`QUERY_ALL_PACKAGES` あり）。
- **authority**: manifest パッチでは `<manifest の package 属性> + ".smb.documents"` と書き、拡張のコードでは実行時に `context.getPackageName() + ".smb.documents"` で作る（完全な authority を文字列定数にしない。パッケージ名変更パッチとの順序に依存しないため）。provider の `onCreate` は `Application.onCreate` より前に動くので、自分で `ModKit.init(getContext())` を呼ぶ。
- ライブラリ: **smbj 0.15.0**（Apache-2.0）。依存は bcprov-jdk18on、slf4j-api、mbassador、asn-one。拡張は**単一の dex**（`classes2.dex` は上書きされる）なので 65,536 メソッド以内にする。今は約 13,780 メソッド、smbj 一式で 45〜50K の見込み。溢れたら R8（`-dontobfuscate`、`app.filesplus.extension.**` を keep、`org.bouncycastle.jcajce.provider.**` を keep）。暗号は smbj の標準の `BCSecurityProvider`（BC の lightweight API。プロバイダ名で登録しないので Android の組み込み BC と衝突せず、R8 を使うときに bcprov の大半を削れる）を使う（0.9 で `JceSecurityProvider` から変更。PC 上の SMB サーバーで SMB 3.1.1 のネゴシエーションと NTLM の計算が動くことを確認済み）。メソッド数は拡張全体で 52,785（上限 65,536）。認証は NTLM のみ（Kerberos/GSS は使わない）。ライセンスは `NOTICE` に追記する。代替は jcifs-ng（LGPL）、自作は採らない。
- **共有一覧（srvsvc）は最初の版では作らない**: 接続先に共有名を必須にする（dcerpc ライブラリは guava などを引いてメソッド数が危ない）。あとで足す。
- 「その他のストレージ」のタップ（`imh.a()`、文字列 "Unable to create intent from package: %s" / "Unable to start document provider activity"）: `getLaunchIntentForPackage(String)` の結果を `SmbHooks.launchIntent(Intent, String)` に通し、パッケージ名が自分のときは `SmbBrowserActivity` の明示 Intent を返す。行の表示名はアプリ名（"FilesPlus"）になる。「SMB」と出すには行のバインダ `imm.b(View, imo)`（文字列 "com.android.shell.documents" など）にフックが要る → **後回し**（表示名は当面そのまま）。
- 画像・動画を開く: `Intent(ACTION_VIEW).setDataAndType(uri, mime).setPackage(自分).addFlags(FLAG_GRANT_READ_URI_PERMISSION)` を `gateway.preview.PreviewActivity` エイリアスに渡せる（パッチ不要、単独ファイルのみで隣のファイルへはスワイプできない）。provider が `openProxyFileDescriptor` を返せば動画のシークも効く見込み（§9 #10 は実機で要確認）。
- 分割（0.9）: ①依存と `SmbClientFactory`＋診断画面の「SMB テスト」→ ②`SmbVfs` コアと接続先の保存（Keystore）→ ③manifest パッチと最小の `SmbDocumentsProvider` → ④タップのフックと `SmbBrowserActivity` のひな形 → ⑤ブラウザ UI → ⑥サムネイル → ⑦FileOps／移動キュー連携。

### 5.3.1 SMB のサムネイルキャッシュ（0.5 で追加）
**対象**: 一覧（リスト・グリッド）に出る**サムネイル**だけ。拡大表示や再生のときのデータはキャッシュしない。

**生成**（ネットワークの転送量を抑える順に試す）
| 種類 | 方法 |
|------|------|
| JPEG | まず EXIF に埋め込まれた小さなサムネイルを使う（先頭の数十 KB を読むだけ）。なければ全体を読んで `inSampleSize` で縮小デコード |
| その他の画像（PNG・WebP・HEIC・AVIF） | 全体を読み、`ImageDecoder` で目標サイズに縮小デコード |
| GIF・アニメーション画像 | 最初のフレーム |
| 動画 | `MediaMetadataRetriever` に SMB のランダムアクセス読み出し（`MediaDataSource`）を渡す。ファイル全体ではなく、ヘッダーと目的のフレーム付近だけを読む。位置は長さの 10%。**ほぼ真っ黒なフレームは避けて次の候補（30% → 50%）を使う** |
- サイズは長辺 512px の WebP（品質 80）。グリッドとリストの両方でこの1種類を使う。
- 同時に生成する数はサーバーごとに2件まで。表示中の項目を優先し、画面外にスクロールしたものは取り消す。

**キャッシュ**
- 置き場所: アプリの内部ストレージ `files/modkit/smb_thumbs/`（`cacheDir` だとシステムに勝手に消されるため使わない）。
- キー: `サーバーID + 共有名 + パス + ファイルサイズ + 更新日時` のハッシュ。**ファイルが変わればキーも変わるので、古いサムネイルは自動で使われなくなる**。
- 索引: `modkit.db` の `smb_thumb` テーブル（キー、ファイル名、バイト数、最終使用日時、動画の場合はフレーム位置）。
- 上限: 既定 500MB（設定で変更可）。超えたら最終使用日時の古い順に消す（LRU）。メモリ上にも小さな LRU（表示中の画面ぶん）を持つ。
- 使い道は2つ（P7 の「2か所以上」に当たるので共通部品にする）: (1) SMB ブラウザの一覧、(2) provider の `openDocumentThumbnail`（SAF 経由のほかのアプリ）。

**再生成**
| 入口（どれも既存の形のメニューに項目を足すだけ） | 動作 |
|------|------|
| ファイルの長押し / 選択時のメニュー →「サムネイルを再生成」 | 選んだファイルのキャッシュを捨てて作り直す。**動画は前回と違う位置（10% → 30% → 50% → 70% の順に次へ）で作る**ので、気に入らないフレームのときに押せば変わる |
| フォルダの︙メニュー →「このフォルダのサムネイルを再生成」 | フォルダ内のサムネイルを作り直す（サブフォルダは含めない） |
| Mod 設定 → SMB →「サムネイルのキャッシュを消去」 | すべて消す。キャッシュの使用量もここに表示する |

**事前作成（任意）**
- 設定 `smb.thumbs.prefetch`（既定 ON、**Wi-Fi 接続時のみ**）: フォルダを開いたとき、そのフォルダの残りのサムネイルを裏で作っておく（画面外の分は優先度を下げる）。

### 5.3.2 接続先の保存とワンタップ再接続（0.6 で追加）
**挙動**
- SMB ブラウザの最上位は**保存した接続先の一覧**。最後に「＋ 接続先を追加」。
- 追加時に一度だけ入力すれば、**以後は一覧の接続先をタップするだけで接続して開く**（入力・確認の画面は出さない）。
- 接続が切れていても（アプリの再起動、スリープ、Wi-Fi の切り替え）、タップした時点・ファイルを開く時点で**自動で再接続**する。ユーザーが「接続」操作をする場面はない。
- 接続先が1つだけのときは、「その他のストレージ」の SMB をタップしたら一覧を飛ばして直接開く（設定 `smb.openSingleDirectly`、既定 ON）。

**保存する項目**
| 項目 | 必須 | 備考 |
|------|------|------|
| 表示名 | 任意 | 空ならホスト名（＋共有名） |
| ホスト | 必須 | IP アドレスまたはホスト名。名前で引けないときは mDNS（`.local`）でも探す |
| ポート | 任意 | 既定 445 |
| 共有名・開始フォルダ | 任意 | 指定すればタップでそのフォルダを直接開く。空ならサーバーの共有一覧から開く（共有一覧の取得には srvsvc 呼び出しのライブラリ（smbj-rpc など）が必要。ライセンスと動作は実装時に確認し、使えない場合は共有名を必須にする） |
| ユーザー名・パスワード・ドメイン | 任意 | 空ならゲスト接続 |
| 開く場所 | — | 「開始フォルダ」/「前回開いていたフォルダ」を選べる（既定: 前回のフォルダ） |

- パスワードは **Android Keystore の鍵で AES-GCM 暗号化**して `modkit.db` に保存する（平文では保存しない。端末のロック解除なしで使えるように、生体認証は必須にしない）。
- 接続先の一覧の長押しメニュー: 編集 / 削除 / 名前の変更 / 並べ替え。元アプリの長押しメニューと同じ形（P1）。

**再接続と失敗時の扱い**
- `SmbSessionPool` が接続先ごとにセッションを持つ。使う直前に生きているか確かめ、切れていれば保存済みの情報で張り直す（ユーザーには見せない）。失敗したら1回だけ再試行する。
- **パスワード誤り・アカウント無効**のときだけ、入力済みの値が入った編集画面を開く（ほかの原因で入力を求めない）。
- **サーバーに届かない**（電源オフ・別のネットワーク）ときは、Snackbar で「接続できません［再試行］」を出す。キャッシュ済みのサムネイルと、直前のフォルダ一覧（読み取り専用の表示）は見られる。
- 接続中は一覧の上部に細い進捗バー（元アプリのものと同じ部品）を出すだけで、画面は切り替えない。

**SAF（ほかのアプリ）との関係**: provider では、**保存した接続先1つを1つのルート**として出す。ほかのアプリのファイル選択でも、接続先をタップするだけで開ける（同じ保存情報と `SmbSessionPool` を使う）。

### 5.4 プレビュー中に指定フォルダへ移動（キュー）＋移動先の履歴
**挙動**
1. プレビュー中に「移動先へ送る」を実行する（メニュー、または任意設定で上スワイプ）。
2. 開くボトムシートには、**移動先の履歴（ピン留め → 最近使った順）**と「フォルダを選ぶ…」（元アプリのフォルダ選択 `AdvancedBrowsingPickerActivity` / storagepicker を再利用）が並ぶ。
3. 移動先を選ぶと、**今のファイル（または選択中の全件）がキューに入り**、プレビューはすぐ次のファイルへ進む（設定でオフにできる）。
4. キューは裏で順に実行される。**再生中のファイルは、そのページから離れたときに実行**する（ファイルを開いたまま移動しないため）。
5. Snackbar に「n 件を ○○ に移動しました ［元に戻す］」と出す。
6. 一度選んだ移動先は `DestinationHistory` に記録される。履歴の上位3件をワンタップで選べる「クイック送り」も用意する（シートの先頭に並べるだけなので、新しい画面は増えない）。

```kotlin
data class MoveRequest(val items: List<VPath>, val dest: VPath, val policy: ConflictPolicy)
class MoveQueue(jobs: JobQueue, ops: FileOps) { // §5.4・§5.10 で共通
    fun enqueue(r: MoveRequest): JobId
    val pending: StateFlow<List<JobSummary>>
    suspend fun undo(id: JobId)
}
```
- プレビューのページ一覧からは、移動したファイルを外す（元アプリのギャラリーのアダプタに通知するフックが必要。§9）。

**調査結果（2026-11-12、versionCode 2081362。難読化名は目印だけ）**
- 元アプリの「移動／コピー」: `move_to_action` / `copy_to_action` は ギャラリー fragment（`jju.aI`）で処理される。移動先の選択は `AdvancedBrowsingPickerActivity`（manifest で exported=false、リクエストは protobuf の Parcelable）なので、**拡張側から開けない／再利用しない**。移動先は自前の簡易フォルダブラウザで選ぶ。結果は Intent extra `"ADVANCED_BROWSING_PICKER_RESULT"`（proto `jce`、移動先 `kqg`、操作 1=コピー・2=移動）。実行は `jkt.s(jmq, Z)`（文字列 "Index for selected file is invalid" で見つかる）。
- 履歴の記録: `jkt.s` をフックして `jmq.c`（`kqg`）と操作（移動のみ）を拡張へ渡せる。`kqg` の文字列フィールド（URI か絶対パス）を反射で読む。SD カードの形式は未確認なので、読めなければ記録しない（best-effort）。
- ページ一覧から外す: `jkt.l()`（文字列 "removeCurrentAndMoveToNextFile with only 1 file." / "...from an invalid position item."）が、今のエントリを外して次へ進む（`D()` がアダプタ更新と `setCurrentItem`、`m()` がメニュー再生成）。呼ぶ前に `jkt.C = jkt.B` が必要（`B`/`C` は `c()`/`d(I)` が読む int フィールド）。1件しかないときは何もしない → Activity を閉じる。20件以上で読み込み済みが20件未満だと `B(...)` が MediaStore を再読み込みするので、移動前に外すだけでは消えたファイルが再び現れる場合がある（移動と MediaStore 同期が済むまで）。複数選択の移動後は `jkt.B(max(0,B-50), true)` で再読み込みする（元アプリも移動後に同じことをする）。
- 再生中の判定: 元アプリのプレイヤー状態（`jpx.R`）には触れない。**今のページの MediaStore id と同じで MIME が video/・audio/ のジョブは待たせ**、`onPageSelected` が別の id を報告したとき、またはプレビューが閉じたときに実行する。
- UI: `BottomSheetDialog` は R8 で消えている。**素の `Dialog`（下寄せ、角丸の GradientDrawable、一覧は拡張側で組む）**で作る。結果は Toast（元に戻すは後回し）。
- キューのスレッドは Activity より長生きさせる（ModKit 側に持つ）。`TabMove` は `app.filesplus.extension` の共有クラスにするか public にして再利用する。
- 分割: ①`DestinationHistory` と `MoveQueue`（UI なし）→ ②下寄せシートと簡易フォルダブラウザ＋メニュー項目 → ③ページ一覧から外す → ④（任意）元アプリの移動からの履歴記録とクイック送り。

**実装の状態（0.9、実機は未確認）**: ①〜③を実装済み（パッチ名 "Move queue in preview"。計画書 `docs/plans/2026-11-12-preview-move-queue.md`）。実装で決めたこと／既知の穴:
- 自動で次へ進めるのは「1件だけ送った、再生中ではない、ページ一覧に2件以上ある」とき（元アプリの `removeCurrentAndMoveToNextFile` を呼ぶ）。最後の1件ならプレビューを閉じる。移動先が今のフォルダと同じなら進めない。再生中の動画・音声は、そのページを離れてから移動し、ページ一覧は変えない。複数件の送信もページ一覧は変えない。
- **既知の穴（保留）**: 動画を再生中に送って離れた場合、複数件を送った場合、移動に失敗した場合は、移動後のファイルがプレビューを開き直すまでページ一覧に残り、そのページに切り替えると読み込みエラーになる。直し方の案: 送るときに絶対インデックス `B` を覚えておき、`modKitRemoveAt(idx)`（`C=idx`、idx<B なら `B--` してから `l()` を呼び、`C` を戻す）で外す。`B--` でページ選択コールバックが同じファイルに再度走るので、実機で動画が再始動しないか確かめる。
- **保留**: 移動キューはフォアグラウンドサービスではなく永続化もしない（ColorOS がプロセスを止めると、待たせていたジョブは失われる。§4.3 の方針とは異なる）。自動で次へ進める挙動を設定で切る項目、上スワイプでの送信、元に戻す、クイック送り（履歴の上位3件。タスク4）は未実装。
- 元アプリの選択画面は使わず、自前の簡易フォルダブラウザで移動先を選ぶ（設計どおり）。

### 5.5 解像度が大きい動画の再生（解像度を落として再生）
**前提（技術的な制約）**: 動画は**元の解像度でデコードしてから**縮小して表示するもので、デコード前に解像度を落とすことはできない。「再生できない」原因は次のどれかで、それぞれ対策が違う。

| 原因 | 症状 | 対策（段階的に試す） |
|------|------|------------------|
| R1. ExoPlayer の性能チェックで弾かれる | 「再生できません」とすぐ失敗する | **L1**: 性能チェックを超えても再生を試させる（ModKit 側の Media3 で `setExceedRendererCapabilitiesIfNecessary` と decoder fallback を有効にする）。多くの 4K/高ビットレート動画はこれで再生できる |
| R2. ハードウェアデコーダが解像度に対応していない（例: 8K） | デコーダの初期化に失敗する | **L2**: 別のデコーダ（ソフトウェアデコーダ `c2.android.*`、FFmpeg）に切り替える。コマ落ちしても再生する |
| R3. デコードが遅くて追いつかない | カクカク・音ズレ | **L3: 低解像度のプロキシを作る** — Media3 Transformer で 720p/1080p の一時ファイルを作って再生する（キャッシュは LRU で最大 2GB）。作っている間はキーフレームだけの「プレビュー再生」を出す |
| — | — | **L4**: 間引き再生。キーフレーム以外を落として、シーク・内容確認だけできるモード |

**0.4: L1 の実現方法と順序の見直し（P5・P7）**
- L1 は**元アプリの ExoPlayer の生成（`kxo`）にフックして設定を変える**だけで試せる。別の Media3 は入れない。
- なお ExoPlayer は既定でも「性能を超えるトラックしかない場合はそれを選ぶ」動作をするので、失敗の原因はデコーダの初期化（R2）である可能性もある。**Phase 0 の「診断」画面で実際のエラー（例外の種類・デコーダ名・解像度）を記録し、L1 で直るかどうかを先に確かめる**。
- 直らない場合は L4（キーフレームだけ）を元アプリのプレイヤーへのフックで作る。L3（変換）と FFmpeg を入れる段階で初めて ModKit 用の Media3 を同梱する。

**0.7: 実機の診断結果とエンジンの決定**（詳細: `docs/HIGH_RESOLUTION_PLAYBACK.md`）
- ハードウェアデコーダ（AVC / HEVC / VP9）はすべて**縦横とも 1920 まで**。超える動画は `MediaCodec.configure` で失敗する（R2）。
- 1596×1996 の AVC: L1＋decoder fallback で標準のソフトウェアデコーダ `c2.android.avc.decoder` に切り替わり、**滑らかに再生できた**。
- 3840×2160 / 30fps / 28Mbps の AVC: 同じ経路で再生は始まるが**カクつく**（R3）。**VLC はハードウェアアクセラレーションを無効にしても滑らか** → この端末の CPU でも、マルチスレッドの FFmpeg（libavcodec）なら 4K30 を実時間でデコードできる。
- 決定: **L2 のエンジンは libVLC**（ユーザー確認済み）。この端末で滑らかなことを確認済みで、MKV・AVI などの「その他の形式」（§5.6）も同じエンジンで扱える。APK は 10〜15MB ほど増える（LGPL。動的リンクのまま同梱し、ライセンス表記を入れる）。
  - `VlcEngine`: 元アプリのプレイヤーのインターフェース（`kxj` 相当）を Hook 層のアダプタで実装し、中身を libVLC の `MediaPlayer` にする。元アプリの UI（シークバー、ダブルタップ、スキップ秒数）はそのまま使う。
  - 選び方: 元アプリの ExoPlayer で再生できる（解像度がハードウェアの上限以内、または標準のソフトウェアデコーダで間に合う）ものは元のまま。超えるもの（目安: 縦横どちらかが 2048 超、または 1440p 以上かつハードウェア非対応）は `VlcEngine`。設定で「常に元のプレイヤー / 自動 / 常に VLC」を選べるようにする。
  - 調べること（着手時）: Morphe のパッチで APK にネイティブライブラリ（`lib/<abi>/*.so`）を追加できるか。今の APK は armeabi-v7a（32bit）なので、32bit の libVLC で 4K が間に合うか（だめなら arm64-v8a 版の APK に切り替える）。診断画面にアプリのプロセスの ABI を表示する。
- これにより、L3（変換）と L4（間引き）の優先度は下がる。libVLC でも間に合わない動画（8K など）のための後回しの手段とする。

**エンジンの抽象化**（エンジンが2つ以上（元アプリ / GIF / L3）になる時点で導入する）
```kotlin
interface PlaybackEngine { fun canPlay(m: MediaProbe): Score; fun create(ctx: PlayCtx): ModPlayer }
// 実装: AppDefaultEngine（元アプリの ExoPlayer のまま）, ModExoEngine（L1/L2）,
//       ProxyEngine（L3）, GifEngine（§5.6）
class EngineSelector(engines: List<PlaybackEngine>, probe: MediaProber)
```
- `MediaProber`: `MediaExtractor` / `MediaCodecList` で解像度・コーデック・プロファイルを調べ、端末のデコーダが対応するかを判定する。
- **フック**: `kxo` の `edz.a()` の直前で `Hooks.selectEngine(uri)` を呼ぶ。`AppDefaultEngine` 以外が選ばれたら、`kxj` を実装したアダプタ（Hook 層）で ModKit の Player を包み、元アプリの UI（`VideoControlView`、シークバー、ダブルタップ）がそのまま使えるようにする。
- 元アプリの再生が失敗したときも、エラー時にフックして一度だけ別のエンジンで再試行する。

**注意**: この APK は **armeabi-v7a（32bit）版**。端末が 64bit なら arm64-v8a 版の APK から作り直したほうが、デコード性能とネイティブライブラリの面で有利（§9）。

**0.3: 端末 OPPO A54 5G（Snapdragon 480）での見立て**
- Snapdragon 480 の動画再生は、**公式資料では明記がなく、第三者の仕様表では 1080p60 まで**。2K・4K が再生できないのは、デコーダの申告上限（1080p）を超えるため ExoPlayer が拒否している、という見立てと合う。
- CPU は 64bit（Kryo 460）。**arm64-v8a 版の APKM を使うことを推奨**（今の APKM は 32bit 版。32bit 版のままでも動く）。
- ColorOS はバックグラウンドの処理を止めやすい。移動キューやフォルダ削除の JobQueue は**前面サービス（通知を出す）で動かす**（§4.3 の決定どおり）。

**0.2: 「2K・4K が再生できなかった」への見立て**
- 2K（1440p）まで再生できないのは、端末のハードウェアデコーダが**公式には 1080p までしか対応していない**（低価格帯や Android Go 端末に多い）可能性が高い。
- ExoPlayer は、端末が申告する性能を超える動画を**試さずに拒否する**ことがある。実際にはハードウェアデコーダが 1440p〜4K をデコードできる場合も多いので、**L1（性能チェックを超えても再生を試させる）が最も効果が大きく、最初に実装する**。
- 性能の低い端末では、L2（ソフトウェアデコード）や L3（プロキシ作成）は**実時間より遅くなる**可能性が高い。そのため、L1 で再生できない場合の既定は **L4（キーフレームだけの間引き再生）**とし、L3 は「あとで見る用に変換しておく」機能（裏で変換して、終わったら通知する）として位置付ける。
- 優先順位: **L1 → L4 → L3（裏で変換）→ L2**。機種名が分かったら、デコーダの上限を調べて見直す（§9）。

### 5.6 いろいろなファイル形式に対応・GIF を動画のようにシーク
**GIF（および Animated WebP / APNG）**
- `GifEngine` で `AnimatedImagePlayer` を作り、**元アプリのプレイヤーのインターフェース（`kxj`）を Hook 層のアダプタで実装する**。動画と同じ扱いになるので、**元アプリの動画 UI（シークバー、再生/一時停止、ダブルタップスキップ、速度変更）がそのまま使える**。Media3 の `SimpleBasePlayer` を使う案もあるが、そのためだけに Media3 を同梱するのは重いので採らない（P5・P7）。
- デコード: Glide の `StandardGifDecoder` 相当を ModKit で持つ（Animated WebP は `ImageDecoder` / libwebp の demux）。
- シーク: フレームごとの delay から「時刻 → フレーム番号」の表を作る。GIF は前のフレームに重ねて描く形式なので、**N フレームごと（例: 30）に全体のビットマップを保存しておき**、そこから目的のフレームまで進める。小さい GIF（全フレーム × 幅 × 高さ × 4 が 64MB 未満）は全フレームを保存する。
- 出力: Player の video surface（`SurfaceView`）に `lockHardwareCanvas` で描く。
- 振り分け: プレビューで MIME が `image/gif` のとき、設定に応じて「画像として表示（従来）/ 動画として表示」を選ぶ（既定は動画として表示）。

**その他の形式**
| 種類 | 対応方針 |
|------|---------|
| 動画コンテナ | 元アプリの Media3 の extractor（MP4/MKV/WebM/TS/FLV/AVI/OGG。元アプリに含まれていれば）。WMV/ASF・RMVB は FFmpeg extractor が必要（Phase 3） |
| 動画/音声コーデック | 端末のデコーダ＋**FFmpeg デコーダ拡張**（nextlib または jellyfin media3-ffmpeg-decoder。**どちらも GPL-3.0** なので、配布するならソースの公開義務がある。自分だけで使うなら問題ない）。AC3/DTS/TrueHD 音声などで効果がある |
| 画像 | HEIC/AVIF は OS（API 31+）でデコードできる。JPEG XL・RAW（DNG 以外）は Phase 3 以降に検討する |
| テキスト系 | 元アプリにテキスト/ZIP/PDF プレビューがある。拡張子と MIME の対応表を ModKit で広げる（例: `.log` `.md` `.json` `.srt` をテキストプレビューに回す） |

- 共通: `FormatRegistry`（拡張子・MIME・マジックバイト → 表示方法 `Viewer`）を1か所にまとめ、プレビューの振り分けで1回だけ参照する。

### 5.7 フォルダタブ画面の共通化（0.3 で追加）
ユーザーが使いたい3機能（フォルダごと削除・空フォルダの表示・現在のタブへ移動）は、どれも「**カテゴリを開いたときにフォルダがタブとして並ぶ画面**」が舞台になる。この画面は画像だけでなく**動画・音声・ダウンロードなどのカテゴリでも同じ部品**（`tabbed_file_browser_fragment_v3.xml`＝`TabLayout @id/tabs`＋`ViewPager2`、右上メニュー `res/menu/tabbed_fragment_menu_v2.xml`、選択時メニュー `tabbed_fragment_regular_selection_mode_menu_v2.xml`）で作られている。そこで、**カテゴリごとに作らず、この画面に対して1回だけフックする**。

**共通部品 `FolderTabContext`**（`feature/foldertabs/`。3機能すべてが使うので共通化する。P7）
```kotlin
class FolderTabContext {
    val currentFolder: StateFlow<LocalPath?>   // 今のタブのフォルダ。「すべて」などフォルダでないタブは null
    fun refresh()                              // 操作後に一覧とタブを読み直す（元アプリの再読み込みを呼ぶ）
}
```
- 3機能はどれも「今のタブ → フォルダ」が分かれば足りるので、interface やカテゴリ種別は設けない（P7）。カテゴリによって振る舞いを変える必要が出たときに足す。
- タブ → フォルダの解決は、タブが持つ bucket（`BUCKET_ID` → MediaStore の `RELATIVE_PATH`）から求める。ダウンロードなど bucket でないタブはタブのパス情報から求める。**解決はタブ切り替え時に1回だけ行い、結果をタブごとにキャッシュする**（メニューを開くたびに MediaStore を問い合わせない）。
- `currentFolder` が null のタブでは、下の3機能のメニューを出さない。
- フック: (1) タブ画面の生成（カテゴリの判定）、(2) ViewPager2 のページ切り替え、(3) 右上メニューと選択時メニューの生成・クリック、(4) タブ一覧を作る処理の最後。各カテゴリのタブがどの部品から作られているか（特にダウンロード）は実装時に jadx で確認する（§9）。

### 5.8 フォルダごと削除
- 元アプリには**フォルダ削除の確認ダイアログ（`ijo`/`ijp`）がすでにある**が、フォルダタブ画面からは呼べない（タブはフォルダそのものではないため）。
- 対応:
  1. 右上の︙メニュー（`tabbed_fragment_menu_v2.xml`）に「**このフォルダを削除**」を1行足す。見た目は変わらない。`currentFolder` があるタブのときだけ表示する。全カテゴリ共通。
  2. 削除は**フォルダごと**だけにする。「そのカテゴリのファイルだけを消したい」場合は、元アプリの「すべて選択 → 削除」で足りるので新しい項目は作らない（P7）。
  3. 確認は既存のフォルダ削除ダイアログ（`ijo`/`ijp`）をそのまま使う（件数の表示、100件を超えたら「100+」）。タブには見えていないほかの種類のファイルも消えるので、件数は**フォルダ内のすべてのファイル**で数える。
  4. 実行は `FileOps.Delete(recursive=true)` を JobQueue で行う。進捗の通知を出し、途中でキャンセルできる。
     - 0.7: 最初の実装では JobQueue（§4.3）を作らず、ModKit のバックグラウンドスレッドで削除し、終わったら Toast と診断ログに結果を出す（P7）。JobQueue は移動キュー（Phase 3）を作るときに導入し、そのときフォルダ削除も載せ替える。
  5. 安全策: ストレージのルート、`Android/`、`DCIM/`・`Download/`・`Movies/`・`Pictures/` などの標準フォルダそのものは削除できない（項目を出さない）。中身を消したいときは「すべて選択 → 削除」を使う（`ProtectedPaths`）。
  6. ゴミ箱: 元アプリのゴミ箱はファイル単位でフォルダを扱えないため、フォルダ削除は完全削除とする（既存ダイアログの文言もそうなっている）。独自のゴミ箱は作らない（P7。必要になったら検討する）。
  7. 削除後は `FolderTabContext.refresh()` でタブを消し、隣のタブへ移る。
- 内部ストレージから入るフォルダ一覧の「フォルダの操作」メニューにも同じ処理を出す（Phase 2 の後半）。

### 5.9 空のフォルダも表示
- フォルダタブは **MediaStore の bucket から作られる**ため、そのカテゴリのファイルがないフォルダ（空のフォルダ、中身を全部移動したフォルダ）はタブに出ない。
- 設定（Mod 設定 → フォルダタブ）:
  | キー | 既定 | 内容 |
  |------|------|------|
  | `tabs.showEmptyFolders` | OFF | カテゴリのファイルがないフォルダもタブとして出す |
  | `tabs.emptyFolderScope` | 「そのカテゴリのファイルが一度でもあったフォルダ＋標準フォルダ」 | どのフォルダを足すか。選択肢: 上記 / 指定したフォルダ配下のすべて / ストレージ全体（遅い） |
  | `tabs.showHidden` | OFF | `.` から始まるフォルダも出す |
  | `browse.showEmptyFolders` | ON | 内部ストレージの一覧にも空フォルダを出す（出ない場合のみ有効） |
- 実装: タブ一覧を作る処理の最後で `EmptyFolderTabAugmenter` がフォルダのタブを足す。中身が空のタブを開いたときは、元アプリの空表示（`NoFilesBlobView`「There's nothing here.」）がそのまま出るので UI の追加は不要。
- 「一度でもあったフォルダ」は、ModKit がタブを読み込むたびにフォルダを `KnownFolders`（`modkit.db`）に記録して覚える。これによって「中身を全部移動したらタブが消えて、そこへ戻せない」問題を防ぐ。
- 性能: ディレクトリの走査は変更日時でキャッシュし、タブ一覧の作成に足す時間を 50ms 以内に抑える。
- **0.8（最初の実装、P7）**: 足すのは「一度でもタブがあったフォルダ（KnownFolders）」だけ。KnownFolders は `modkit.db` ではなく SharedPreferences にカテゴリごとの文字列集合で持つ。設定は `tabs.showEmptyFolders`（既定 OFF）と `tabs.showHidden` だけ。`tabs.emptyFolderScope` のほかの選択肢と `browse.showEmptyFolders`（§9 #2 の確認待ち）は、必要になったら足す。

### 5.10 選択したファイルを現在のフォルダタブへ移動（0.3 で追加）
**挙動**
1. どのタブ（「すべて」も含む）でもファイルを長押しして選択する。
2. **選択したまま、移動先のフォルダのタブへスワイプ/タップで切り替える**。
3. 選択時メニュー（`tabbed_fragment_regular_selection_mode_menu_v2.xml`）の「**このフォルダへ移動**」を押す（表示中のタブ名を項目名に入れる。例: 「"Camera" へ移動」）。
4. 移動は JobQueue で実行し、Snackbar に「n 件を Camera に移動しました［元に戻す］」を出す。移動先は `DestinationHistory` に記録する（§5.4 の移動キューと共通）。
5. 移動後、両方のタブを `refresh()` する。

**設計上のポイント**
- **タブを切り替えても選択を保つ**: 元アプリがページ切り替えで選択を解除する場合に備えて、選択の変化を `SelectionStore`（§5.2 と同じもの）に写しておき、切り替え後のタブで選択モードを復元する。選択数の表示は元アプリの選択バーをそのまま使う。
- 項目を出す条件: `currentFolder` が null でない、かつ選択中に「現在のタブ以外のフォルダのファイル」が1件以上ある。すでにそのフォルダにあるファイルは対象から外す。
- 名前がぶつかったときは `ConflictPolicy`（既定は「確認する」）。
- 元アプリの「移動」（フォルダを選ぶ画面に移る）は残す。こちらは「今見ているタブへ1タップで移動する」近道。
- 空のフォルダのタブ（§5.9）にも移動できるので、「新しいフォルダを作る → タブに出す → 選択して移動」がこの画面だけで完結する。

**0.8（最初の実装、P7）**: JobQueue・MoveQueue・SelectionStore・DestinationHistory はまだ作らない（Phase 3 で導入して載せ替える）。
- 移動は ModKit のバックグラウンドスレッドで行う（同じボリュームなら rename、だめならコピー → 削除）。名前がぶつかったら確認せず「name (1).ext」で両方残す（データを失わない）。
- 結果は Toast と診断ログ。「元に戻す」は Phase 3 の移動キューと一緒に入れる。
- 選択の保持: 元アプリがタブ切り替えで選択を解除するなら、解除の前に選択を覚えて切り替え後に戻す。無理なら「別のタブで選択 → 移動先のタブへ」の流れは諦め、選択時メニューに「移動先のタブを選ぶ」一覧を出す方式に変える（実装時に判断）。

```kotlin
class MoveToCurrentTabAction(
    private val tabs: FolderTabContext,
    private val selection: SelectionStore,
    private val queue: MoveQueue,                 // §5.4 と共通
) {
    fun isAvailable(): Boolean
    fun execute(policy: ConflictPolicy = ConflictPolicy.ASK): JobId
}
```

---

## 6. ModKit の内部構成

### 6.1 モジュール
0.2 では、下の `core/` と `hooks/` が Morphe テンプレートの `extensions/extension/` に、パッチ定義が `patches/` に入る（BUILD_PROCEDURE.md §2）。SMB は §5.3 のとおり改造 APK の中だけに入れる。
```
modkit/                         (Gradle, Kotlin, minSdk 31, targetSdk は元アプリに合わせる)
├─ core/
│  ├─ platform/   vfs/ fileops/ jobqueue/ settings/ history/ uikit/ log/
│  └─ feature/    seek/ selection/ movequeue/ media/ smb/（browser, provider, thumbs）foldertabs/ folderops/ emptyfolder/ formats/
├─ hooks/
│  ├─ Hooks.kt                  ← smali から呼ぶ static 入口（@JvmStatic）
│  └─ adapters/v2081362/        ← 難読化クラスとの橋渡し（バージョンごとに作る）
├─ stubs/v2081362/              ← defpackage.kxj, qhs などの空のスタブ（compileOnly、出力に含めない）
└─ third_party/                 ← Media3（パッケージ名を変えて衝突を避ける）、smbj、（任意）ffmpeg
```
- 元アプリにも難読化された Media3/AndroidX が入っているが、**名前が違うので衝突はしない**。ただし、こちらの Media3 も念のため `jarjar`/R8 で `modkit.shaded.media3` に名前を変える。

### 6.2 Hooks の入口（例）
```kotlin
object Hooks {
    @JvmStatic fun doubleTapSkip(): Any           // qhs を返す（adapter が変換する）
    @JvmStatic fun buttonSkip(forward: Boolean): Any
    @JvmStatic fun onPreviewPageShown(activity: Activity, uri: Uri)
    @JvmStatic fun onCreatePreviewMenu(activity: Activity, menu: Menu)
    @JvmStatic fun onPreviewMenuItem(activity: Activity, item: MenuItem): Boolean
    @JvmStatic fun selectEngine(ctx: Context, uri: Uri): Any?   // null なら元のまま
    @JvmStatic fun augmentFolderList(list: List<Any>): List<Any>
    @JvmStatic fun onCreateSettings(fragment: Any)
    @JvmStatic fun onAppCreate(app: Application)   // ModKit の初期化
}
```
- すべての入口を `try/catch(Throwable)` で包み、**例外が出たら元の動作に戻す**（改造部分が原因で元アプリが落ちないようにする）。

### 6.3 フックのマッピング（0.2: Morphe の fingerprint に置き換え）
0.2 からは、下の yaml の代わりに **Morphe の fingerprint**（`patches/.../shared/Fingerprints.kt`）でフック位置を表す。難読化名ではなく、文字列・リソース・難読化されないクラスを手がかりにする（BUILD_PROCEDURE.md §3.2 の表）。以下は 0.1 の考え方として残す。
```yaml
version_code: 2081362
patches:
  - id: seek.doubleTap
    class: Ldefpackage/kwd;
    method: b(Landroid/view/MotionEvent;)V
    anchor: "const-wide/16 v?, 0xa"      # 10L → qhs.h の直前
    action: replace_call_result_with: Lmodkit/hooks/Hooks;->doubleTapSkip()Ljava/lang/Object;
  - id: seek.button
    class: Ldefpackage/jpy;
    anchor: "sget-object v?, Ldefpackage/jpx;->b:Ldefpackage/qhs;"
    ...
  - id: media.engine
    class: Ldefpackage/kxo;
    anchor: "invoke-virtual {v?}, Ldefpackage/edz;->a()Landroidx/media3/exoplayer/ExoPlayer;"
```
- 次のバージョンでは、**文字列定数・リソース ID（`R.id.rewind_container`、`R.layout.quick_seek_overlay_view` など）・`"rewindSwipeTrianglesAnimation"` のようなログ文字列**を目印に、新しいクラス名を探す（難読化されても残る手がかり）。手順は BUILD_PROCEDURE.md §8。

---

## 7. 実装フェーズ

| Phase | 内容 | 主なリスク |
|-------|------|-----------|
| 0 | Morphe テンプレートからリポジトリを作る、パッケージ名の変更パッチと ModKit の初期化パッチだけで Morphe Manager から当てて起動できることを確認、「Mod 設定 → 診断」画面 | 署名を変えたことで Google 系の機能（Firebase、Phenotype の実験フラグなど）が動かない。provider の authority の書き換え漏れ |
| 1 | スキップ秒数の設定（§5.1）、**再生エンジン L1（§5.5、2K/4K 対策）** | L1 は ExoPlayer の生成にフックするだけなので早く試せる。効果の確認を優先する |
| 2 | 共通基盤（VFS/FileOps/JobQueue/History）、**フォルダタブの共通部品（§5.7）**、フォルダごと削除（§5.8）、空のフォルダの表示（§5.9）、**現在のタブへ移動（§5.10）** | タブ一覧を作る処理とタブ切り替え時の選択の扱い |
| 3 | プレビュー中の複数選択（§5.2）、移動キュー（§5.4） | ギャラリーのページ一覧の更新 |
| 4 | SMB（provider・SMB ブラウザ・サムネイルキャッシュ）（§5.3） | 元アプリの一覧の見た目をどこまで流用できるか、プレビューへ `content://` を渡したときの動作 |
| 5 | 再生エンジンの抽象化、**libVLC エンジン（L2、4K 対策）**（§5.5 の 0.7）、GIF のシーク（§5.6） | `kxj` アダプタの実装量、ネイティブライブラリの追加、32bit 版での性能 |
| 6 | libVLC でのその他の形式、L4・L3（必要なら） | APK サイズ |

---

## 8. 非機能要件
- **安全性**: 改造部分の例外は握りつぶして元の動作に戻す。ファイルの削除・移動はすべてログ（`modkit.db` の `op_log`）に残す。
- **性能**: プレビューの UI スレッドで I/O をしない。JobQueue は `Dispatchers.IO` で、SMB は接続ごとに並列数を制限する。
- **プライバシー**: 元アプリの分析・テレメトリ（Firebase Analytics、Primes など）は、改造版では止めることを推奨（署名が違うので送信は失敗するが、通信そのものも止める）。
- **テスト**: ModKit Core は JVM の単体テスト（VFS はメモリ上の偽物、FileOps の衝突処理、GIF の時刻→フレーム対応）。フックは実機での手動チェックリストで確認する。

## 9. 実機で確認すること（設計を確定させるため）
adb は使わない前提。確認は、パッチを当てたアプリの「Mod 設定 → 診断」画面（ModKit が端末情報・デコーダの上限・直近のエラーを表示し、共有ボタンでテキストにして送れる）で行う。

| # | 確認すること | 状態 |
|---|------------|------|
| 1 | `jpx.Z`（5秒）・`jpx.aa`（1秒）の用途（キーボードの ←/→ やシークバーの操作か） | 未 |
| 2 | 「内部ストレージ」から入る一覧に空のフォルダが出るか | 未 |
| 3 | `DocumentsProviderBrowserActivity` で使える操作 | **済**: provider の中身は Files 内では開けない（§5.3 で方式を変更） |
| 10 | `FilePreviewActivity` に外部の `content://`（SMB の provider）を渡して、画像の拡大・動画の再生・シークができるか | 未 |
| 4 | フォルダの削除ができない画面 | **済**: 画像・動画・ダウンロードなどのフォルダタブ画面（§5.7） |
| 5 | GIF が今どの部品で表示されているか（Glide か ImageDecoder か） | 未 |
| 6 | 再生できない高解像度の動画の具体例（解像度・コーデック）とエラー | 一部: 2K・4K が再生できない（§5.5） |
| 7 | 端末の機種名と ABI | **済**: OPPO A54 5G（Snapdragon 480、arm64-v8a・32bit も可） |
| 9 | 動画・音声・ダウンロードなど各カテゴリのタブが何から作られているか（特にダウンロード）、タブ切り替えで選択が解除されるか | 未（実装時に jadx で確認） |
| 8 | Files がシステムアプリかどうか | **済**: システムアプリ → パッケージ名を変える |
