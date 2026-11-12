# 引き継ぎメモ（最終更新: 2026-11-12）

/clear のあとに作業を再開するためのメモ。まず `CLAUDE.md`（運用ルール）と `docs/WORKERS.md`（ワーカーのセッション）を読むこと。

## 最新の中断（2026-11-12 11 時ごろ、5時間枠 83%。リセット 13:50）— まずここを読む
- **再開の予約**: 13:53 に1回だけ自動で再開する予約を立てた（`CronCreate`、セッション内だけ。Claude Code を閉じると消える。`CronList` で確認）。消えていたら、ユーザーが「再開」と言ったときに下の「次の1手」から進める。
- **体制の変更（ユーザー指示 2026-11-12）**: メイン（司令塔）とレビューは **Opus 5.5**。ワーカーは Sonnet のまま（CLAUDE.md 更新済み）。
- **最重要: UI の作り方のやり直し（DESIGN 方針 P8、§5.11 冒頭の 0.12 の注記）**。ユーザーが dev.18 を実機で見て、Mod 設定のタイトル部分と移動先ブラウザのメディア表示が元の Files と違う、「推測でデザインし、見た目を寄せて作り直している。絶対にやめて。Files の部品を完全に流用するか、リバースエンジニアリングで一切の違いなく写して」と指摘。原因: 元の View（`RowItemView` など）は Hilt の Fragment 文脈がないと例外になる（`RowItemView.h()` の `rvu` チェック）ので、自前の Activity では作れず、`FilesLook` で標準 View に差し替えて寄せていた。
  - **次の1手（リセット後、Opus のメインが自分で調査・設計）**: 元アプリ自身の画面の中で動かす方法を jadx / apktool で調べ、§5.11 の UI 部分を設計し直して DESIGN に書く。候補: (a) 移動先の指定は、元の「移動」が開くフォルダ選択画面（`advanced_browsing_picker_fragment`、§5.11.1）とフォルダタブ付きのカテゴリ画面（§5.7 のフック）をそのまま使い、データ・ボタンをフックで差し込む。(b) Mod 設定は、元の設定画面（`SettingsActivity` の Fragment）に項目として差し込む。(c) SMB の閲覧も元の一覧画面で動かせるか（§5.3 の 0.5 では provider は Files 内で開けないと判明済み → 別の入口が要る）。調査結果をもとに計画書 `docs/plans/2026-11-12-unified-move-browser.md` を改訂してから実装者（Sonnet）へ。
  - 凍結: U3（worktree `ModdedFiles-wt/move`、未コミット・未レビュー。`MoveSheet` など似せた UI に乗っている）はマージしない。ロジック部分（`MovePicker` の判定、`continueOriginal` のバイパス、`MoveQueue` の変更）は作り直しで再利用できるかもしれないので worktree は消さない。
  - dev に入っている U0〜U2（`FilesLook`、`UiSample`、`BrowseActivity`、診断のテスト項目）と Mod 設定の見た目（`SettingsLook`）は P8 に反する。設計のやり直しの中で、置き換えるか削除するかを決める。U1 の `browse/*`（`Location`、`LocalSource`、`ThumbLoader`）と `DestinationHistory` は UI と独立なので残せる見込み。
- **リリースの件（解決済み）**: dev.18 のリリースが失敗し続けていた（ノート ref の push が拒否 → タグだけ残り、以後「タグがすでにある」で失敗）。リモートのタグを消し、空の `fix:` コミットで再実行 → **v1.0.0-dev.18 公開済み**。Morphe Manager がずっと dev.15 のままだった件は未解決（GitHub には dev.16〜18 がある。Manager 側のキャッシュを疑い、強制更新／ソースの再追加を案内済み。結果は未確認）。**push のあとは `gh run list --workflow Release` でリリースの成否を必ず確かめる。**
- 以下の「再開したらやること」の項目 1〜5（Download タブの件、SMB のカテゴリ、安全設計 S4〜S9、ツール許可ダイアログ）は引き続き有効。旧項目1（Mod 設定の見た目）は上のとおり P8 でやり直し。

## 現在の状況
- プロジェクト名は **FilesPlus**（2026-11-11 に ModdedFiles から改名）。GitHub: https://github.com/yak9909/FilesPlus 。ローカルのフォルダ名は `E:/Projects/ModdedFiles` のまま（変えない）。
- 設計は `docs/DESIGN.md`。Phase 0〜2 と、Phase 3 の §5.2（プレビュー中の複数選択）・§5.4 の①〜③（移動キュー）が実装済みで `dev` に統合済み。
- 公開済みのプレリリース: v1.0.0-dev.1 〜 **dev.7**（§5.2）。**dev.8（§5.4 移動キュー）は 2026-11-12 に push 後 CI が作る**。
  - dev.4: 先頭付近でもダブルタップで巻き戻す（`skip.rewindToStart`）、フォルダタブ画面の︙ →「このフォルダを削除」。
  - dev.5: 空のフォルダもタブに表示（Mod 設定 → フォルダタブ、`tabs.showEmptyFolders`、`tabs.showHidden`）。
  - dev.6: 選択モードの︙ →「"<タブ名>" へ移動」（Move to current tab）。
  - dev.7: プレビューの︙ →「選択」「選択を解除」「選択中 n 件」、チェック印、戻ったときに一覧の選択モードへ引き継ぎ（Multi-select in preview。DESIGN §5.2 の「実装の状態」）。
  - dev.8: プレビューの︙ →「移動先へ送る…」（Move queue in preview）。下寄せシートに移動先の履歴（ピン留め→最近）と自前のフォルダブラウザ、裏の移動キュー、再生中の動画・音声はページを離れてから移動、単独ファイルは移動後に次へ進む。DESIGN §5.4 の「実装の状態」に既知の穴を書いた。
  - 表示されるパッチ: Change package name / Custom skip duration / High-resolution playback / Delete folder from tabs / Show empty folder tabs / Move to current tab / Multi-select in preview / Move queue in preview。隠しパッチ: ModKit 統合、フォルダタブの共通フック。
- **実機確認はユーザーが最後にまとめて行う**（ユーザー指示）。dev.4〜dev.8 の機能はどれも実機では未確認。PC では元 APK に全パッチをまとめて当てられることを確認済み（スクラッチパッドのハーネスは使い捨て。`build/verify/` に README あり。base.apk は `original/*.apkm` から取り出す。新しいパッチを足したら `VerifyRewind.java` の Set.of(...) に追加する）。
- 以前の実機確認（OPPO A54 5G）: パッチ適用・起動・診断画面 OK。2K は再生 OK、4K はカクつく → 4K は **libVLC エンジン**で対応（DESIGN §5.5 の 0.7、Phase 5）。
- ローカルビルド: `bash scripts/build.sh --no-daemon`（表示される mpp 名は最新のもの）。

## ワーカーの状態
- 実装は **Claude のサブエージェント**（Haiku 5.5 は変更内容を書き切れる単純作業だけ、それ以外は Sonnet 5.5）。Codex は予備（CLAUDE.md「Workers」）。
- superpowers を導入済み。運用は CLAUDE.md の「Process」「Review」に書いた。メインは今のセッションのモデル（助言 Opus 5.5）。レビューは別のサブエージェントで、既定は Opus 5.5（小さな差分と再確認だけ Sonnet）。複数タスクの機能は `docs/plans/` に計画書。planning-with-files と Codex の stop-time review gate は使わない。
- 動いているワーカーはなし。worktree `E:/Projects/ModdedFiles-wt/{tabs,preview,queue}` は不要になったら消してよい。
- ワーカーは**同時に1つまで**。
- 利用枠の見張り: 5時間枠 80%・週間枠 95% で中断処理（強制終了ではない。CLAUDE.md 参照）。

## 再開したらやること（順番）
0. **Phase 4（SMB）は進行中**（ユーザー指示 2026-11-12）。計画書 `docs/plans/2026-11-12-smb.md`、調査と決定は DESIGN §5.3。**タスク1（smbj 同梱＋診断の「SMB テスト」）は `dev` にマージ済み（dev.9）**。
   - **（2026-11-12 利用枠 81% で中断。リセット 09:40）止めた作業と次の1手**:
     1. **Mod 設定の見た目を元の Files に揃え、診断ログを畳む**（実装済み・未レビュー・未コミット。worktree `ModdedFiles-wt/ui`、ブランチ `w/ui`、新規 `settings/SettingsLook.java` ＋ `ModSettingsActivity.java`）。次: 差分（`git -C ../ModdedFiles-wt/ui add -N . && git diff -U8 > <ファイル>`）を Opus でレビュー（特に、`Activity` 上でアプリのレイアウトを inflate できるか、リソース名がパッケージ名変更後も引けるか、フォールバックが例外を握りつぶすか）→ 指摘を直す → コミット（`feat:`）→ dev にマージ → push。実機で確かめる: 見た目（ライト／ダーク）、設定が開くこと、ログの開閉・コピー・共有・消去。
     2. **Download タブに「"Downloads" へ移動」が出ない件**: 診断に `Tab folder: key=… -> <フォルダ|null> (filters: name/path/prefix/invalid)` を出すようにした（dev.15、469062e）。ユーザーに診断のその行を貼ってもらい、`null` の理由で直す。「すべてのファイルへのアクセス」の状態表示と設定を開くボタンも未実装。
     3. **SMB をホームの「カテゴリ」グループに足す**（計画書のタスク4・5に組み込む。ホームの各グループの実際の呼び名をユーザーに聞く。元の文字列: category_title=Categories, collection_title=Collections, other_storage_title=Other storage）。タスク4の手順を計画書に書いてから実装者へ。
     4. 安全設計 S4〜S9（§10.12）。
     5. ユーザー依頼: ツール許可のダイアログが出る（bypass のはずが）。公式ドキュメントでは、サブエージェントは親のモードを引き継ぐ／`ask` ルールや危険な `rm`／対話が必要なツールは bypass でも出る。ダイアログの文言を聞いて設定を直す。
   - **（2026-11-12 夜）ファイル操作の安全設計 DESIGN §10 の実装: S1・S2・S3 は dev にマージ済み（worktree `ModdedFiles-wt/safety`、ブランチ `w/safety`）。次は S4（純 Java のコア＋SHA-256 検証つきコピー。ユーザーは「全て進めて」と指示済みで、JVM テストの別モジュールも承認扱い）→ S5〜S9（§10.12 の表）。SMB タスク 2b（共有名を任意に・共有の列挙）も dev にマージ済み。タスク4（Other storage タップのフック＋`SmbBrowserActivity`）は計画書に手順がまだない。実機未確認の項目: 共有名なしの接続／共有の一覧、`.FilesPlus` の作成とゴミ箱への rename、フォルダの移動（空のフォルダへの rename を拒否する端末かどうか、§9 #11）、写真が MediaStore から消えるか（§9 #14）、Download タブで「へ移動」が出ない件（同名バケットが複数あると推測せず null。診断にタブ→フォルダの解決結果を出す案と、「すべてのファイルへのアクセス」の状態表示＋設定画面を開くボタンの案。どちらも未実装）。レビューの Minor: S2＝マニフェストのフォルダの fsync なし／MediaStore の読み取り上限 20,000 件／成功トーストが切れる／`manifest.json` という名前のフォルダは入れられない／段階2の確認（1,000 件超・5GB 超）が未実装。S3＝移動先が `Android/`・`.FilesPlus/` かの確認なし／`.pending-*`・`.trashed-*` の拒否なし／lstat 失敗時に予約が残る（S5 で回復）。利用枠は 5 時間枠 73% で中断前。ユーザーはツール許可のダイアログが出ると言っている（bypass のはずが。サブエージェントの権限を調査中）。**
   - **（2026-11-12 最優先）実機でフォルダタブ系パッチ（Delete folder from tabs / Show empty folder tabs / Move to current tab）を入れるとカテゴリを開いたときにクラッシュした。ハイレゾ・スキップ・Multi-select・Move queue は無事。原因: 共有フック（`folderTabContextPatch`）が「各 return-void の前」に差し込んでいて、R8 が引数レジスタを再利用するため p0/p1 の型が違い ART の検証で落ちる（＋分岐先の return-void ではフックが飛ばされる）。修正: `afterOriginal`（元の本体を private に移し、ラッパーでフックを呼ぶ）とコンストラクタの引数をフィールドに退避。`fix:` で dev に push 済み（CI がプレリリースを作る）。Opus レビューは指摘なし。dexlib2 の解析では問題なしだが ART の検証ではない → 実機で再確認が必要（フォルダタブ系3つをオンにして、カテゴリを開く／フォルダ削除メニュー／空フォルダタブ）。ユーザーは Morphe の Expert mode でパッチを個別にオン・オフできる。**
   - **（2026-11-12 追記）タスク3（読み取り専用 `SmbDocumentsProvider` と manifest の provider）も Opus レビューで指摘なし → `dev` にマージ・push 済み。provider は実機未確認。次はタスク4（Other storage タップのフック＋`SmbBrowserActivity` の骨組み）。計画書に手順を書き足してから実装者へ。タスク3の Minor: 子の名前が `SmbPath.join` に弾かれると一覧全体が失敗する（その項目だけ飛ばす方がよい）／`CancellationSignal` 未対応／`notifyRootsChanged()` をストアのロック内で呼んでいる／`stat` のキャッシュでサイズが古いことがある。**
   - **（2026-11-12 更新）タスク2は Opus の再確認で指摘なし → コミット・`dev` にマージ・push 済み（`01b7bdc`。プレリリース dev.10 は CI が作る）。DESIGN §5.3.2 の保存先記述も直した。タスク3（manifest の provider ＋最小 `SmbDocumentsProvider`）は計画書に手順を書き、Sonnet の実装者に出した（worktree `smb`、未コミット）。戻ったら: ビルド・全パッチ適用の確認 → Opus レビュー（差分はファイルで）→ 指摘修正 → コミット・マージ・push。レビューの Minor（HANDOFF 用）: Samba の `follow symlinks` ではリンクが普通のフォルダに見え、再帰削除でリンク先が空になりうる（SMB で再帰削除をする UI を作るときは確認ダイアログを付ける）／壊れた接続先リストはリセット手段がない／`acquire` が接続中（最大1分）エントリのロックを持つ。**
   - （以下は古い記述）タスク2は worktree に実装済みで未コミットだった（利用枠 83% で中断した時点）。実装者（サブエージェント afb468b20444485ff、Sonnet）の実装 → Opus のレビュー（Critical なし、Important 4件）→ 実装者が Important 4件と Minor を修正、まで済み。私の手元でビルド（拡張 dex は 53,067 メソッド）と全パッチ適用は成功。**修正後の再確認（スコープを絞ったレビュー）はまだ**。
   - 再開したらやること: ①修正後の差分の再確認（特に `SmbVfs.deleteTree` が `FILE_OPEN_REPARSE_POINT` でリンクを辿らないこと／`SmbSessionPool.invalidate(id, share)`／`users` のリセット／`readSmbForm()` のパスワード必須チェック／壊れた JSON を上書きしないこと）。差分は `git -C ../ModdedFiles-wt/smb add -N . && git -C ../ModdedFiles-wt/smb diff -U10 > <ファイル>` で作る。Sonnet か、削除に関わる部分は Opus。②指摘がなければ worktree でコミット（`feat: Save SMB connections and add the SMB file layer`）→ `dev` にマージ → `bash scripts/build.sh --no-daemon` → 全パッチを元 APK に当てて確認 → `git pull --no-rebase origin dev` → push → プレリリース。③DESIGN §5.3.2 の保存先の記述（`modkit.db`）を実装（SharedPreferences `filesplus_smb`）に合わせて直す。④計画書にタスク3（manifest パッチと最小の `SmbDocumentsProvider`）の詳しい手順を書き足して進める。
   - 既知の制約（実装者の報告）: パスワードが空のアカウントは使えない（空文字は「削除」の意味）。ユーザー名があって読めるパスワードがないときは、空のパスワードを試さずに「入力し直してください」と出す。Samba の `follow symlinks` はリンクを検出できない。
   - PC 上のテストで、Windows のセキュリティログに失敗したログイン（ユーザー `filesplus_smoketest`）が数件残っている。害はない。実機で確かめること: 診断画面の「SMB テスト」で、正しいパスワード（一覧と 1 KB の読み出しが出る）・間違ったパスワード（`STATUS_LOGON_FAILURE`）・間違った共有名（`STATUS_BAD_NETWORK_NAME`）・間違ったホスト（接続エラー）を試し、結果を共有してもらう（slf4j の警告や `NoClassDefFoundError` / `VerifyError` が出たら貼ってもらう）。拡張の dex は 52,785 メソッド（上限 65,536）。R8 の準備を、大きなものを足す前に入れる。worktree `ModdedFiles-wt/smb`（`w/smb`）。
1. **§5.4 の続き（任意）**: ④元アプリ自身の移動からの履歴記録（`jkt.s(jmq, Z)` をフック）とクイック送り（上位3件）。**既知の穴**（動画を再生中に送って離れた場合・複数件の送信・失敗したときに、移動後のファイルがページ一覧に残る）の直し方は DESIGN §5.4 の「実装の状態」。移動キューの永続化／フォアグラウンドサービスは保留。計画書: `docs/plans/2026-11-12-preview-move-queue.md`。
2. **JobQueue の導入と、フォルダ削除（§5.8）の載せ替え**（§5.8 の 0.7）。
3. その後は Phase 4 以降（下の順）。実機確認はユーザーが最後にまとめて行うので、それまでは実装を進めてよい。
4. 実機で確かめる項目（まとめて）:
   - §5.2: プレビューで2〜3件選んで戻ると一覧で選択されている／回転して戻っても同じ／2回目のプレビューに古い印が出ない／ファイル情報を開いて戻っても選択が保たれる／検索・カテゴリ・ホームから開いたプレビュー／外部アプリから開いたプレビュー／一覧での選択が従来どおり動く／ゴミ箱にしたファイルが選択に紛れ込まない／SD カードのファイルも選べる。
   - §5.4: シートに履歴が出て長押しでピン留めできる／フォルダブラウザで SD カードを含む保存先を辿れる・新しいフォルダを作れる／画像を送ると次へ進み、移動したファイルが戻ってこない／1件だけのプレビューは閉じる／動画を再生中に送ると、スワイプで離れるか閉じてから移動する／回転や Home で勝手に移動しない／名前がぶつかると「name (1).ext」／ダーク・ライトで見た目が崩れない／「移動先へ送る…」が Move queue in preview を外すと出ない。

## このあとの実装順（DESIGN.md §7）
- Phase 3: プレビュー中の複数選択（§5.2、済）、移動キュー（§5.4、①〜③済）、JobQueue
- Phase 4: SMB（ブラウザ・provider・サムネイルキャッシュ・接続先保存、§5.3）
- Phase 5: 再生エンジン抽象化・**libVLC エンジン（4K）**・GIF シーク
- Phase 6: libVLC でのその他の形式、必要なら L4・L3

## 注意
- 作業フォルダの git で `error: daemon terminated` が出ることがある（fsmonitor）。無視してよい。
- `codex exec resume` には `-C` がないので、launcher が worktree に `cd` してから実行する。**ワーカー実行中に `scripts/codex-worker.sh` を編集しない**（bash が読み直して壊れる）。
- `codex delete` は `--force` が必要（非対話のため）。
- 日本語を含む短い編集は Edit ツールで行う。
- 解析用の逆コンパイル結果は `analysis/`（git 管理外）。難読化名はこの版（versionCode 2081362）専用。
