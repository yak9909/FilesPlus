# 引き継ぎメモ（最終更新: 2026-11-11 19:55）

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
- 2026-11-11 から実装は **Claude のサブエージェント**（単純作業は Haiku 5.5、それ以外は Sonnet 5.5）が優先。Codex は予備（CLAUDE.md「Workers」）。
- 動いているワーカーはなし。Codex の tabs セッションは削除済み。worktree `E:/Projects/ModdedFiles-wt/tabs`（`w/tabs`）は残っている。
- ワーカーは**同時に1つまで**。
- 利用枠の見張り: 5時間枠 80%・週間枠 95% で中断処理（強制終了ではない。CLAUDE.md 参照）。

## 再開したらやること（順番）
1. Phase 3 に入る: §5.2（プレビュー中の複数選択）から。DESIGN を読み、ワーカーへの依頼文を作って Sonnet のサブエージェントに任せる（新しい worktree `ModdedFiles-wt/<name>`、ブランチ `w/<name>` を `dev` から作る）。
2. 終わったらレビュー → worktree でコミット（`feat:`）→ `dev` にマージ → `bash scripts/build.sh --no-daemon` → `build/verify/` で全パッチを当てて確認（base.apk は `original/*.apkm` から取り出す）→ `git pull --no-rebase origin dev` → push → プレリリース。
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
