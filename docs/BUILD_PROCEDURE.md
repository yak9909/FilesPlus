# パッチの作り方と当て方（Morphe 方式）

- 作成日: 2026-11-11（改訂 0.2: Morphe 方式に変更 / 0.3: GitHub リポジトリと署名鍵の手順を追加）
- リポジトリ: https://github.com/yak9909/ModdedFiles （公開。`main`＝安定版、`dev`＝開発版）
- 前提となるユーザー環境: **root なし、adb はなるべく使わない、Morphe Manager は導入済み**、Files は**システムアプリ**として入っている
- 個人で使うためのもの。パッチを当てた APK は配布しない（パッチ定義そのものには Google のコードを含めない）

---

## 0. 方式（決定）

**Morphe のパッチバンドル（`.mpp`）として作り、端末の Morphe Manager で当てる。**

```
［PC］ModdedFiles リポジトリ ──./gradlew buildAndroid──▶ patches-x.y.z.mpp
                                                         │（Google ドライブ / USB のファイル転送 / GitHub Releases）
［端末］Morphe Manager ─ パッチソースに .mpp を追加 ─ Files の APKM を選ぶ ─ パッチ ─ インストール
```

| 観点 | Morphe 方式でどうなるか |
|------|------------------------|
| root | 不要（通常のインストーラで入れる） |
| adb | 不要。.mpp と APKM をファイルとして端末に置くだけ |
| split APK（APKM） | Morphe Manager が**パッチ前に1つの APK にまとめる**（APKM / APKS / XAPK に対応） |
| 署名 | Morphe Manager の鍵で署名される。**鍵は「署名鍵のエクスポート」で必ずバックアップする**（なくすと上書き更新できなくなる） |
| 元アプリとの共存 | Files はシステムアプリなので、**パッケージ名を変えないと入らない** → バンドルに「パッケージ名の変更」パッチを入れる（§3.4） |
| 難読化名の変化 | Morphe の **fingerprint**（文字列・リソース・命令の並びで目的のメソッドを探す仕組み）で探すので、クラス名が変わっても当たりやすい |
| 更新 | GitHub に置けば Morphe Manager が更新を知らせてくれる。ローカルの .mpp ならファイルを選び直す |

> 旧版（0.1）の「apktool で手作業ビルド」は、**解析用**としてだけ残す（§7）。

---

## 1. 開発側（PC）の準備

| 道具 | 用途 | 状態 |
|------|------|------|
| JDK 17 または 21 | Gradle | 入っている（`C:\Program Files\Java\jdk-21.0.3` を使う。jdk-26 は jadx などで動かなかった） |
| Android SDK（compileSdk 用）と Android Studio | extension（Java/Kotlin のコード）を dex にするのに必要 | **要インストール** |
| git | リポジトリの管理 | 入っていれば使う（`git init` を推奨） |
| GitHub アカウントと **PAT（`read:packages` 権限）** | `morphe-patcher` が GitHub Packages で配布されていて、**公開パッケージでも認証が必要** | **要作成**（ユーザーの作業） |
| jadx 1.5.6 / apktool 3.0.3 | fingerprint を作るための解析 | 解析用に取得済み |

PAT の設定（`%USERPROFILE%\.gradle\gradle.properties`）:
```
gpr.user = <GitHub のユーザー名>
gpr.key = ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```
（環境変数の `GITHUB_ACTOR` / `GITHUB_TOKEN` でもよい）

## 2. リポジトリの構成

公式の **MorpheApp/morphe-patches-template** をもとに作る。

```
ModdedFiles/
├─ patches/                                   ← パッチ定義（Kotlin）
│  ├─ build.gradle.kts                        （group と About を設定）
│  └─ src/main/kotlin/app/moddedfiles/patches/files/
│     ├─ shared/Compatibility.kt             （対象パッケージとバージョン）
│     ├─ shared/Fingerprints.kt              （フック位置の探し方。旧 hooks/*.yaml の代わり）
│     ├─ misc/ModKitIntegrationPatch.kt      （extension の初期化、設定画面の入口）
│     ├─ misc/PackageNamePatch.kt            （パッケージ名の変更 §3.4）
│     ├─ seek/SkipDurationPatch.kt
│     ├─ browse/DeleteFolderPatch.kt
│     ├─ browse/ShowEmptyFoldersPatch.kt
│     ├─ preview/PreviewSelectionPatch.kt
│     ├─ preview/MoveQueuePatch.kt
│     ├─ media/PlaybackEnginePatch.kt
│     ├─ media/GifSeekPatch.kt
│     └─ storage/SmbProviderPatch.kt
├─ extensions/extension/                      ← ModKit 本体（Java/Kotlin。dex になってアプリに合流する）
│  └─ src/main/java/app/moddedfiles/modkit/…   （DESIGN.md §4〜§6 の Core と Hook 入口）
├─ stubs/                                     ← 難読化クラスのスタブ（compileOnly）
├─ docs/
└─ original/                                  ← 元の APKM（git には入れない）
```

## 3. パッチの書き方（要点）

### 3.1 パッチの形
```kotlin
val skipDurationPatch = patch(
    name = "Custom skip duration",
    description = "ダブルタップとスキップボタンの秒数を設定できるようにする",
    default = true,
) {
    compatibleWith(COMPATIBILITY_FILES)
    dependsOn(modKitIntegrationPatch)       // extension の初期化と設定画面
    extendWith("extensions/extension.mpe")  // ModKit の dex を合流させる

    execute {
        QuickSeekFingerprint.method.apply { /* qhs.h(10L) の 10 を Hooks の戻り値に置き換える */ }
        SkipButtonFingerprint.method.apply { /* jpx.b / jpx.c の読み出しを置き換える */ }
    }
}
```
```kotlin
val COMPATIBILITY_FILES = Compatibility(
    name = "Files by Google",
    packageName = "com.google.android.apps.nbu.files",
    targets = listOf(AppTarget(version = "1.13157.1011540316.0-release")),
)
```
- 対象バージョンを明記しておくと、Morphe Manager が「推奨バージョン」として表示する。
- **パッチ時オプション**（`intOption` / `stringOption` など）で、既定値（例: スキップ秒数の初期値、パッケージ名）を当てる前に選べる。実行中の変更はアプリ内の「Mod 設定」で行う。

### 3.2 fingerprint（フック位置の探し方）
**難読化名（`kwd`、`jpx` など）は使わず**、変わりにくい手がかりで探す。
```kotlin
// ダブルタップのスキップ（解析: defpackage.kwd.b(MotionEvent)）
object QuickSeekFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/MotionEvent;"),
    filters = listOf(
        string("rewindSwipeTrianglesAnimation"),
    ),
)
```
（リソース ID で探すフィルタの正確な API 名は、実装時に morphe-patcher のドキュメント `2_2_1_fingerprinting.md` で確認する）
| フック | 探す手がかり（変わりにくいもの） |
|--------|------------------------------|
| ダブルタップのスキップ | 文字列 `"rewindSwipeTrianglesAnimation"`、`"fastForwardSwipeTrianglesAnimation"`、`R.string.quick_seek_duration` |
| スキップボタン | `R.id.rewind` / `R.id.fast_forward`、`VideoControlView`（難読化されていない） |
| ExoPlayer の生成 | `VideoPlayerView`（難読化されていない）の `getHolder()`、LoadControl の定数 `30000000` |
| 画像一覧のタブ画面 | `R.layout.tabbed_file_browser_fragment_v3`、`R.menu.tabbed_fragment_menu_v2` |
| 外部ストレージの列挙 | 文字列 `"android.content.action.DOCUMENTS_PROVIDER"` |
| プレビュー | `FilePreviewActivity`、`R.menu.image_preview_menu` / `video_preview_menu` |
| 設定画面 | `SettingsActivity`、設定の XML |

### 3.3 extension（ModKit 本体）
- `extensions/extension` の Java/Kotlin コードがビルド時に dex（`.mpe`）になり、`.mpp` の中に入る。パッチを当てるときにアプリの dex へ合流する。
- パッチ側の smali の変更は「`invoke-static {…}, Lapp/moddedfiles/modkit/Hooks;->xxx(…)`」を差し込むだけにして、処理はすべて extension に書く（DESIGN.md の P2）。
- smbj、ModKit 用の Media3 などのライブラリも extension に入れる。**サイズとメソッド数**に注意する（FFmpeg のようなネイティブライブラリは Phase 6 で扱いを決める）。

### 3.4 パッケージ名の変更（システムアプリと共存させるため）
- Morphe には「Clone（別パッケージ名で作る）」の仕組みがあるが、**汎用のパッチがこのアプリの ContentProvider の authority まで正しく書き換えるかは未確認**。
- そのため、バンドルに専用のパッチ「Files Mod package」を入れる:
  1. `package` を `com.google.android.apps.nbu.files.mod` に変える（オプションで変えられる）
  2. すべての `<provider android:authorities>` と独自の `<permission>` を新しい名前に合わせる（重複していると `INSTALL_FAILED_CONFLICTING_PROVIDER` で入らない）
  3. コードに直接書かれた authority の文字列を置き換える（共有・「別のアプリで開く」が落ちないように）
  4. アプリ名を「Files Mod」にする（元の Files と見分けるため）
- 署名が変わるので、Firebase・Phenotype（実験フラグ）・Google ドライブへのバックアップなどの Google 連携は動かない。ファイル管理の基本機能には影響しない（DESIGN.md §8）。

## 4. ビルド（PC）
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.3"
.\gradlew buildAndroid
# 出力: patches\build\libs\patches-<version>.mpp
```

## 4.1 GitHub でのビルドとリリース（0.3 で決定）
- 公式テンプレートの `release.yml`（semantic-release）が、**`dev` / `main` へ push したときに GitHub Actions でビルドし、Releases に `.mpp` を置く**。
  - `feat:` / `fix:` のコミット → 新しいリリース（`dev` ならプレリリース）。`chore:` → リリースしない。
  - `patches-list.json`、`patches-bundle.json`、`CHANGELOG.md` は自動生成されるので手で編集しない。
  - 開発は `dev` ブランチで行い、安定したら `main` へマージ（squash しない）。
- リポジトリの設定（作成時に実施済み）: Actions のワークフロー権限を「読み書き」にし、PR の作成・承認を許可。
- 状態の確認: `gh run list -R yak9909/ModdedFiles`、`gh release list -R yak9909/ModdedFiles`
- 任意: `./gradlew generateSigningKey` でバンドルに署名すると、Morphe Manager が改ざんされたリリースを拒否する。秘密鍵は `MORPHE_SIGNING_KEY` シークレットに入れる（`gh secret set MORPHE_SIGNING_KEY < morphe-signing.key`）。
- ローカルでビルドするときだけ、PAT（`read:packages`）が必要。`gh` のトークンに権限を足して使える:
  ```
  gh auth refresh -h github.com -s read:packages
  $env:GITHUB_ACTOR = "yak9909"; $env:GITHUB_TOKEN = (gh auth token)
  .\gradlew buildAndroid
  ```

## 5. 端末でパッチを当てる（adb を使わない手順）
1. **元の APK を端末に置く**: APKMirror の APKM（端末に合う ABI・DPI のもの。§6）を端末にダウンロードする。
2. **パッチソースを追加する**: Morphe Manager → パッチソース → **リモート** に `https://github.com/yak9909/ModdedFiles` を入れる（または端末のブラウザで `https://morphe.software/add-source?github=yak9909/ModdedFiles` を開く）。
   - 開発版（`dev` ブランチのプレリリース）を試すときは、そのパッチソースで「プレリリース」を有効にする。
   - 新しいリリースが出ると Morphe Manager が知らせてくれる。
3. （ローカルでビルドした .mpp を試す場合のみ）パッチソース → **ローカル** → `.mpp` を選ぶ。
4. アプリの一覧から Files を選ぶ（出ない場合はエキスパートモードで「APK を持っている」→ APKM を選ぶ）。
5. パッチを選ぶ（初回は「Files Mod package」と「ModKit integration」が必須。その他は好きなものを選ぶ）→ オプションを確認 → パッチ。
6. 通常のインストーラでインストールする（「提供元不明のアプリ」の許可を Morphe Manager に1回だけ与える）。
7. **Morphe Manager の署名鍵をエクスポートしてバックアップする**（初回のみ。§5.1）。
8. 更新時: 新しい .mpp（または新しい APKM）で 3〜6 をやり直すだけ。同じ鍵なので上書き更新でき、Mod 設定・移動先の履歴は残る。

## 5.1 Morphe Manager の署名鍵のエクスポート・インポート
Morphe Manager の資料（`docs/backup-and-keystore.md`）による手順:
- **エクスポート**: **設定 → 署名鍵（Signing key）**（「バックアップ」の項目）→ 鍵の別名・作成日・SHA-256 が表示される → **エクスポート** → 保存先を選ぶ → `Morphe.keystore` が保存される。
  - **一度もパッチを当てていないと鍵がまだないので、インポートしか出ない**。最初のパッチのあとにエクスポートする。
  - 保存先は端末の中だけにせず、Google ドライブや PC にもコピーしておく（端末の故障や初期化に備える）。
- **インポート**（機種変更・再インストール時）: 同じ画面で **インポート** → ファイルを選ぶ。Morphe が出力した鍵は通常そのまま読み込める。読み込めないときは別名・パスワード・形式を聞かれる（Morphe が作る鍵は別名・パスワードともに `Morphe`）。
- 「Morphe の設定」のエクスポート（`morphe_manager_settings.json`）には**鍵は含まれない**ので、別々に保存する。

## 6. 端末に合う APKM を選ぶ
- **端末: OPPO A54 5G（Snapdragon 480、64bit）** → APKMirror で **arm64-v8a**、Android 12+、DPI は端末に合うもの（迷ったら nodpi）の APKM を選ぶ。
- 今の APKM は **armeabi-v7a（32bit）・640dpi・Android 12+** 用。
- 端末が **64bit 専用**（最近の Pixel など）だと、32bit 版は入らない。**64bit 対応端末なら arm64-v8a 版のほうが速い**。
- adb を使わずに確認する方法: 「設定 → デバイス情報」で**機種名**を見る（機種名が分かれば調べられる）。または Play ストアの「Device Info HW」などで「ABI / 命令セット」を見る。
- Morphe Manager の「デバイスのアーキテクチャに最適化」を有効にすると、合わない split は自動で外れる。

## 7. 解析の手順（fingerprint を作る・新しいバージョンに追従するとき。PC のみ）
```bash
unzip files.apkm -d apkm/                                       # APKM は普通の zip
java -jar apktool_3.0.3.jar d -f -s -o apktool_out apkm/base.apk  # リソースの確認用（-s: dex はそのまま）
JAVA_HOME="C:/Program Files/Java/jdk-21.0.3" JAVA_OPTS=-Xmx6g \
  jadx -d jadx_out --no-res --show-bad-code -j 4 apkm/base.apk    # 11,252 クラス（203件のエラーは読むだけなら問題ない）
```
新しいバージョンが出たら:
1. 新しい APKM で上を実行し、`Compatibility` の `targets` にバージョンを足す。
2. `./gradlew buildAndroid` → 端末でパッチを当てて、**fingerprint が見つからないパッチ**（Morphe Manager がエラーとして表示する）だけを直す。
3. 直すときは §3.2 の手がかりで jadx を検索し、新しいメソッドを探す。

---

## 参考
- Morphe パッチのテンプレート: https://github.com/MorpheApp/morphe-patches-template
- Morphe Patcher（ドキュメント: `docs/1_patcher_intro.md`、`2_1_setup.md`、`2_2_1_fingerprinting.md`）: https://github.com/MorpheApp/morphe-patcher
- Morphe Manager（パッチソース: `docs/patch-sources.md`、エキスパートモード: `docs/patching-expert-mode.md`）: https://github.com/MorpheApp/morphe-manager
- smbj: https://github.com/hierynomus/smbj
- Apktool: https://github.com/iBotPeaches/Apktool / jadx: https://github.com/skylot/jadx
