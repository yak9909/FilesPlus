# 引き継ぎメモ（最終更新: 2026-11-11 18:55）

/clear のあとに作業を再開するためのメモ。まず `CLAUDE.md`（運用ルール）と `docs/WORKERS.md`（ワーカーのセッション）を読むこと。

## 現在の状況
- プロジェクト名は **FilesPlus**（2026-11-11 に ModdedFiles から改名）。GitHub: https://github.com/yak9909/FilesPlus 。ローカルのフォルダ名は `E:/Projects/ModdedFiles` のまま（変えない）。
- 設計は `docs/DESIGN.md` v0.8。Phase 0〜1 と Phase 2 の §5.7〜§5.9 が実装済みで `dev` に統合・push 済み。**作業はユーザーの指示でここで中断している（再開の予約はなし）。**
- 公開済みのプレリリース: v1.0.0-dev.1 〜 **dev.5**。
  - dev.4: 先頭付近でもダブルタップで巻き戻す（`skip.rewindToStart`、既定 ON）、フォルダタブ画面の︙ →「このフォルダを削除」。
  - dev.5: 空のフォルダもタブに表示（Mod 設定 → フォルダタブ、`tabs.showEmptyFolders` 既定 OFF、`tabs.showHidden`）。一度でもタブがあったフォルダをカテゴリごとに SharedPreferences（`tabs.knownFolders.<CATEGORY>`）に覚えて、ファイルがなくなってもタブを足す。
  - 表示されるパッチ: Change package name / Custom skip duration / High-resolution playback / Delete folder from tabs / Show empty folder tabs。隠しパッチ: ModKit 統合、フォルダタブの共通フック（`folderTabContextPatch`）。
- **実機確認はユーザーが最後にまとめて行う**（ユーザー指示）。dev.4・dev.5 の機能はどれも実機では未確認。PC では元 APK に全パッチをまとめて当てられることを確認済み（`build/verify/`、README あり。新しいパッチを足したら `VerifyRewind.java` の Set.of(...) に追加する）。
- 以前の実機確認（OPPO A54 5G）: パッチ適用・起動・診断画面 OK。2K は再生 OK、4K はカクつく → 4K は **libVLC エンジン**で対応（DESIGN §5.5 の 0.7、Phase 5）。
- ローカルビルド: `bash scripts/build.sh --no-daemon`（表示される mpp 名は最新のもの）。

## ワーカー（Codex）の状態
- `tabs` ワーカー（セッション `01a1c8a6-b6d8-72a3-b155-9b8db5ce9f57`、gpt-6.1-sol / high）: §5.7・§5.8・§5.9 を実装済み（`dev` に統合済み）。**待機中**。§5.10 も同じタブ画面のフックを使うので、このセッションを続けて使う。
  - worktree `E:/Projects/ModdedFiles-wt/tabs`（ブランチ `w/tabs`）。使う前に worktree で `git merge dev` する。
- ワーカーは**同時に1つまで**（ユーザーの PC が重くなるため。必要なときだけ2つ）。

## 再開したらやること（順番）
1. **§5.10 現在のタブへ移動**: 依頼文は用意済み `worker-logs/prompts/movetotab.md`（DESIGN §5.10 の 0.8 簡略版: キューなし、名前がぶつかったら「name (1).ext」、Toast、元に戻すはなし。タブ切り替えで選択が保てないならワーカーは止まって報告する）。
   `git -C E:/Projects/ModdedFiles-wt/tabs merge dev` のあと、`bash scripts/codex-worker.sh resume tabs 01a1c8a6-b6d8-72a3-b155-9b8db5ce9f57 worker-logs/prompts/movetotab.md`（background で実行）。
2. 終わったらレビュー → worktree でコミット（`feat:`）→ `dev` にマージ → `bash scripts/build.sh --no-daemon` → `build/verify/` で全パッチを当てて確認 → `git pull --no-rebase origin dev`（CI のリリースコミットがあるため）→ push → プレリリース。
3. Phase 2 が終わったら tabs セッションは不要になるので `codex delete --force <id>` して WORKERS.md から消す。
4. その後は Phase 3 以降（下の順）。実機確認はユーザーが最後にまとめて行うので、それまでは実装を進めてよい。

## このあとの実装順（DESIGN.md §7）
- Phase 2: 残りは現在のタブへ移動（§5.10）だけ。JobQueue は Phase 3 で導入（§5.8 の 0.7）。
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
