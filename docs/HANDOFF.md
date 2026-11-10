# 引き継ぎメモ（最終更新: 2026-11-11 04:20）

/clear のあとに作業を再開するためのメモ。まず `CLAUDE.md`（運用ルール）と `docs/WORKERS.md`（ワーカーのセッション）を読むこと。

## 現在の状況
- 設計は完了（`docs/DESIGN.md` v0.6）。実装は **Phase 0〜1** の途中。
- ブランチ: `dev`（最新の土台）。GitHub: https://github.com/yak9909/ModdedFiles 。まだリリースなし（`chore:` のみのため）。
- ローカルビルド: `bash scripts/build.sh`（JDK 21、Android SDK は `%LOCALAPPDATA%\Android\Sdk`、`gh` トークンに `read:packages` 付与済み）。
- `dev` にあるもの: Files の Compatibility 定義（`patches/.../shared/Constants.kt`）、extension の核（`ModKit` / `ModSettings` / `DiagLog`）、ワーカー用の説明書 `docs/WORKER_BRIEF.md`、起動スクリプト `scripts/codex-worker.sh`。

## 実行中だったワーカー（Codex、各自の git worktree）
| 名前 | 内容 | worktree / ブランチ | セッション ID |
|------|------|------|------|
| pkg | パッケージ名変更パッチ | `E:/Projects/ModdedFiles-wt/pkg` / `w/pkg` | 01a1c6ce-7ec9-7ca3-974d-5d83535687af |
| core | ModKit 初期化・Mod 設定・診断画面・設定画面の入口 | `.../core` / `w/core` | 01a1c6ce-87dc-75a1-b5d4-808fb4b5182b |
| skip | スキップ秒数パッチ | `.../skip` / `w/skip` | 01a1c6d0-87e6-7a92-a72c-288fcc90899a |
| media | 2K/4K 再生（L1）と再生エラーの診断記録 | `.../media` / `w/media` | 01a1c6d0-905d-7682-9630-9e16e0025970 |

- 依頼文: `worker-logs/prompts/<名前>.md`、ログ: `worker-logs/<名前>.log`、最後の返答: `worker-logs/<名前>.last.md`。
- **Codex の5時間枠が 92% 使用の状態で起動した**ため、途中で止まっている可能性がある。リセットは **2026-11-11 07:41**。

## 再開手順
1. 各ワーカーの状態を確認: `worker-logs/<名前>.last.md` があれば完了。なければログ末尾を見て、止まっていれば
   `bash scripts/codex-worker.sh resume <名前> <セッションID> <プロンプトファイル>` で続きを依頼する
   （例: 英語で "You were interrupted by a usage limit. Continue the task from where you stopped; build must pass; then give the final report." を書いたファイルを渡す）。
2. 完了したワーカーの結果をレビュー（`git -C E:/Projects/ModdedFiles-wt/<名前> diff`、`bash scripts/build.sh` を worktree で実行）。
3. 問題があれば同じセッションに英語で修正を依頼（resume）。問題なければ worktree でコミットし、`dev` にマージ。
   - `skip` と `media` は `ModKitIntegrationPatch.kt` の仮スタブを作っている可能性がある → マージ時は `core` の実装を採用する。
   - 統合後に `dev` でビルドを確認。
4. 最初のリリース: 統合したら `feat:` コミットで push → GitHub Actions が `dev` のプレリリースを作る（`gh run list` / `gh release list`）。
5. 不要になったワーカーセッションは `codex delete <ID>`、worktree は `git worktree remove`、ブランチは削除し、`docs/WORKERS.md` を更新。
6. ユーザーへの案内: Morphe Manager の「パッチソース → リモート」に `https://github.com/yak9909/ModdedFiles` を追加し、`dev` のプレリリースを有効にして試してもらう。初回パッチ後に署名鍵をエクスポート（設定 → 署名鍵 → エクスポート）。診断画面の内容を共有してもらい、2K/4K の原因を確定する。

## このあとの実装順（DESIGN.md §7）
- Phase 2: 共通基盤（VFS / FileOps / JobQueue / History）、フォルダタブ共通部品（§5.7）、フォルダごと削除（§5.8）、空フォルダのタブ表示（§5.9）、現在のタブへ移動（§5.10）
- Phase 3: プレビュー中の複数選択（§5.2）、移動キュー（§5.4）
- Phase 4: SMB（ブラウザ・provider・サムネイルキャッシュ・接続先保存、§5.3）
- Phase 5〜6: 再生エンジン抽象化・L4・GIF シーク・その他形式

## 注意
- 作業フォルダの git で `error: daemon terminated` が出ることがある（fsmonitor）。無視してよいが、`git worktree add` が固まったら git プロセスを止めて作り直す。
- 解析用の逆コンパイル結果は `analysis/`（git 管理外）。難読化名はこの版（versionCode 2081362）専用。
