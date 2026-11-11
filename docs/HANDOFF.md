# 引き継ぎメモ（最終更新: 2026-11-11 08:30）

/clear のあとに作業を再開するためのメモ。まず `CLAUDE.md`（運用ルール）と `docs/WORKERS.md`（ワーカーのセッション）を読むこと。

## 現在の状況
- 設計は `docs/DESIGN.md` v0.6。**Phase 0〜1 の実装が終わり、`dev` に統合済み**。
- 最初のプレリリース **v1.0.0-dev.1** を公開済み（`patches-1.0.0-dev.1.mpp`）。
  - 表示されるパッチ: Change package name / Custom skip duration / High-resolution playback（どれも既定で有効）。
  - 隠しパッチ: ModKit 統合（`Files_Application.onCreate` で初期化し、Files の設定の「概要」の上に「Mod 設定」を追加。Mod 設定にはスキップ秒数と診断画面がある）。
- 動作中のワーカーはなし。worktree とブランチ `w/*` は削除済み。
- ローカルビルド: `bash scripts/build.sh`（失敗したら `--no-daemon` を付ける。理由は WORKERS.md）。
- まだ確かめていないこと: 実機での動作全般。全パッチをまとめて元の APK に当てる確認はしていない（各パッチは単独で確認済み）。

## 次にやること（2026-11-11 時点）
- 実機確認の結果: 2K（1596x1996）は再生 OK。4K はカクつく。VLC はハードウェアアクセラレーション無効でも滑らか → 4K は **libVLC エンジン**で対応すると決定（DESIGN §5.5 の 0.7）。ただし **Phase 2 を先に**やる（ユーザー判断）。
- Phase 2 はワーカー1つずつ順番に:
  1. `tabs`（実行中）: フォルダタブの共通部品＋フォルダごと削除（§5.7・§5.8）。依頼文 `worker-logs/prompts/tabs.md`、worktree `../ModdedFiles-wt/tabs`。
  2. 空フォルダのタブ表示（§5.9）
  3. 現在のタブへ移動（§5.10）
- 小さな修正（自分でやる）: 診断ログで音声の情報も「Video input」と出る件、診断画面にアプリのプロセスの ABI（32/64bit）を出す件。
- その後: 全パッチをまとめて元の APK に当てる検証ツール、Phase 5 の libVLC エンジン（ネイティブライブラリを APK に足せるかの調査から）。

## このあとの実装順（DESIGN.md §7）
- Phase 2: 共通基盤（VFS / FileOps / JobQueue / History）、フォルダタブ共通部品（§5.7）、フォルダごと削除（§5.8）、空フォルダのタブ表示（§5.9）、現在のタブへ移動（§5.10）
- Phase 3: プレビュー中の複数選択（§5.2）、移動キュー（§5.4）
- Phase 4: SMB（ブラウザ・provider・サムネイルキャッシュ・接続先保存、§5.3）
- Phase 5〜6: 再生エンジン抽象化・L4・GIF シーク・その他形式

## 注意
- 作業フォルダの git で `error: daemon terminated` が出ることがある（fsmonitor）。無視してよいが、`git worktree add` が固まったら git プロセスを止めて作り直す。
- 解析用の逆コンパイル結果は `analysis/`（git 管理外）。難読化名はこの版（versionCode 2081362）専用。
