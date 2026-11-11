# 引き継ぎメモ（最終更新: 2026-11-11 22:10）

/clear のあとに作業を再開するためのメモ。まず `CLAUDE.md`（運用ルール）と `docs/WORKERS.md`（ワーカーのセッション）を読むこと。

## 現在の状況
- プロジェクト名は **FilesPlus**（2026-11-11 に ModdedFiles から改名）。GitHub: https://github.com/yak9909/FilesPlus 。ローカルのフォルダ名は `E:/Projects/ModdedFiles` のまま（変えない）。
- 設計は `docs/DESIGN.md` v0.8。Phase 0〜2（§5.7〜§5.10）が実装済みで `dev` に統合・push 済み。次は Phase 3。
- 公開済みのプレリリース: v1.0.0-dev.1 〜 **dev.6**（dev.6 は CI が push 後に作る）。
  - dev.6: 選択モードの︙ →「"<タブ名>" へ移動」（Move to current tab、既定 ON）。選択中のファイルを今のフォルダタブへ移動。名前がぶつかったら「name (1).ext」、Toast、元に戻すはなし。タブを切り替えても選択が消えないことは jadx 上の確認だけ（実機で要確認）。キーボード操作の経路（options menu 側）はフックしていない。
  - dev.4: 先頭付近でもダブルタップで巻き戻す（`skip.rewindToStart`、既定 ON）、フォルダタブ画面の︙ →「このフォルダを削除」。
  - dev.5: 空のフォルダもタブに表示（Mod 設定 → フォルダタブ、`tabs.showEmptyFolders` 既定 OFF、`tabs.showHidden`）。一度でもタブがあったフォルダをカテゴリごとに SharedPreferences（`tabs.knownFolders.<CATEGORY>`）に覚えて、ファイルがなくなってもタブを足す。
  - 表示されるパッチ: Change package name / Custom skip duration / High-resolution playback / Delete folder from tabs / Show empty folder tabs / Move to current tab。隠しパッチ: ModKit 統合、フォルダタブの共通フック（`folderTabContextPatch`）。
- **実機確認はユーザーが最後にまとめて行う**（ユーザー指示）。dev.4・dev.5 の機能はどれも実機では未確認。PC では元 APK に全パッチをまとめて当てられることを確認済み（`build/verify/`、README あり。新しいパッチを足したら `VerifyRewind.java` の Set.of(...) に追加する）。
- 以前の実機確認（OPPO A54 5G）: パッチ適用・起動・診断画面 OK。2K は再生 OK、4K はカクつく → 4K は **libVLC エンジン**で対応（DESIGN §5.5 の 0.7、Phase 5）。
- ローカルビルド: `bash scripts/build.sh --no-daemon`（表示される mpp 名は最新のもの）。

## ワーカーの状態
- 2026-11-11 から実装は **Claude のサブエージェント**（Haiku 5.5 は変更内容を書き切れる単純作業だけ、それ以外は Sonnet 5.5）が優先。Codex は予備（CLAUDE.md「Workers」）。
- superpowers を導入（2026-11-11）。運用は CLAUDE.md の「Process」「Review」に書いた。要点: メインは今のセッションのモデル（Sonnet 5.5、助言 Opus 5.5）。レビューは別のサブエージェントで、既定は Opus 5.5（小さな差分と再確認だけ Sonnet）。複数タスクの機能は `docs/plans/` に計画書。多段の計画では `.superpowers/sdd/` に台帳（git 管理外）。planning-with-files と Codex の stop-time review gate は使わない。
- 動いているワーカーはなし（利用枠の閾値の手前で止めた）。Codex の tabs セッションは削除済み。worktree `E:/Projects/ModdedFiles-wt/tabs`（`w/tabs`）と `E:/Projects/ModdedFiles-wt/preview`（`w/preview`）が残っている。
- ワーカーは**同時に1つまで**。
- 利用枠の見張り: 5時間枠 80%・週間枠 95% で中断処理（強制終了ではない。CLAUDE.md 参照）。

## 再開したらやること（順番）
1. **§5.2 の修正ラウンド（利用枠 76% で中断した続き）**。計画書 `docs/plans/2026-11-11-preview-multiselect.md`（タスク1〜3）。タスク1〜3は worktree `E:/Projects/ModdedFiles-wt/preview`（`w/preview`、コミット `c3ebb16` `30527c7` `040ad54`）に実装済み。ビルドと全パッチの適用は私の手元で成功済み。タスク3のレビュー（Opus）で Critical なし、Important 4件が残っている。**`dev` にはまだマージしていない**（この4件で選択が失われる経路があるため。直してからマージ）。
   - Important 1: 回転して戻ったとき、一覧のモデルが resume より後に作られると選択が失われる（`ListHandoff.java:97-115`）。直し方: `registerModel` で、ストアが空でなくプレビューが終了済みなら `handOver(current)` を post する。
   - Important 2: `isPreview` がプレビューのインスタンスへの弱参照に頼っている（`:84-87`, `:128-129`）。低メモリや「アクティビティを保持しない」で、戻ったときにプレビュー自身の（使われない）モデルに渡してしまう。直し方: プレビューの `Class<?>` を強参照で持ち、終了判定だけ弱参照のインスタンスで行う。
   - Important 3: プレビュー内で削除／ゴミ箱にしたファイルが、選択に残ったまま渡される。直し方: 削除・ゴミ箱の箇所で今のエントリを外す（`DELETE_DIALOG` / `MOVE_TO_TRASH_DIALOG_TAG` のページコールバック付近）。難しければ実機確認項目に回す。
   - Important 4: 新しいプレビューを開いた後で、古いプレビューの destroy が選択を消す（`:110-113`）。直し方: クリアの前に `preview.get() == activity` を確かめる。
   - Minor: `invoke-static {p0}` を `/range` に（`PreviewMultiSelectPatch.kt:260`）、マージ確認のスキャンを軽くする、`installed` の同期、使われなくなった `takePendingForList()` の削除、パッチの description に一覧への引き継ぎを書く。
   - 手順: 実装者（Sonnet）に上の Important を直させる → 直した差分だけを Sonnet か Opus で再確認 → ビルドと `build/verify/` 相当で全パッチ適用 → `dev` にマージ → `git pull --no-rebase origin dev` → push → プレリリース → DESIGN §5.2 の状態を更新（`SelectionStore` は id→エントリで、content URI だけのファイルは選べない／ブリッジは `s(List,false)`／所有する Activity にだけ渡す、という実装の判断を書く）。
   - 実機で確かめること: プレビューで2〜3件選んで戻ると一覧で選択されている／回転して戻っても同じ／2回目のプレビューに古い印が出ない／ファイル情報を開いて戻っても選択が保たれる／検索・カテゴリ・ホームから開いたプレビュー／外部アプリから開いたプレビュー／一覧での選択が従来どおり動く。
2. その後は Phase 3 の移動キュー（§5.4）。
3. 実機確認はユーザーが最後にまとめて行うので、それまでは実装を進めてよい。

## このあとの実装順（DESIGN.md §7）
- Phase 2: 完了（dev.6）。JobQueue は Phase 3 で導入（§5.8 の 0.7）。
- Phase 3: プレビュー中の複数選択（§5.2）、移動キュー（§5.4）
- Phase 4: SMB（ブラウザ・provider・サムネイルキャッシュ・接続先保存、§5.3）
- Phase 5: 再生エンジン抽象化・**libVLC エンジン（4K）**・GIF シーク
- Phase 6: libVLC でのその他の形式、必要なら L4・L3

## 注意
- 作業フォルダの git で `error: daemon terminated` が出ることがある（fsmonitor）。無視してよい。
- `codex exec resume` には `-C` がないので、launcher が worktree に `cd` してから実行する。**ワーカー実行中に `scripts/codex-worker.sh` を編集しない**（bash が読み直して壊れる）。
- `codex delete` は `--force` が必要（非対話のため）。
- Python の heredoc で日本語を含む文字列を置換しようとして、一致せず失敗したことがある（原因は未確定）。日本語を含む短い編集は Edit ツールで行う。
- 解析用の逆コンパイル結果は `analysis/`（git 管理外）。難読化名はこの版（versionCode 2081362）専用。
