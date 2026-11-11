# 引き継ぎメモ（最終更新: 2026-11-11 13:10）

/clear のあとに作業を再開するためのメモ。まず `CLAUDE.md`（運用ルール）と `docs/WORKERS.md`（ワーカーのセッション）を読むこと。

## 現在の状況
- プロジェクト名は **FilesPlus**（2026-11-11 に ModdedFiles から改名）。GitHub: https://github.com/yak9909/FilesPlus 。ローカルのフォルダ名は `E:/Projects/ModdedFiles` のまま（変えない）。
- 設計は `docs/DESIGN.md` v0.7。Phase 0〜1 は実装済みで `dev` に統合済み。
- 公開済みのプレリリース: v1.0.0-dev.1 〜 dev.3（dev.3 = 診断画面にアプリの ABI 表示、音声トラックのログ表記の修正）。
  - 表示されるパッチ: Change package name（既定のアプリ名 FilesPlus）/ Custom skip duration / High-resolution playback。
  - 隠しパッチ: ModKit 統合（Files の設定の「概要」の上に「Mod 設定」。スキップ秒数と診断画面）。
- 実機確認の結果（OPPO A54 5G）: パッチ適用・起動・診断画面 OK。2K（1596x1996）は再生 OK。4K はカクつく。VLC はハードウェアアクセラレーション無効でも滑らか → 4K は **libVLC エンジン**で対応すると決定（DESIGN §5.5 の 0.7、Phase 5）。ただし **Phase 2 を先に**やる（ユーザー判断）。
- ローカルビルド: `bash scripts/build.sh --no-daemon`（ワーカーが残した Gradle デーモンのせいで AccessDenied になるため。WORKERS.md 参照）。

## ワーカー（Codex）の状態
- **Codex の利用枠は 0**。リセットは **2026-11-11 12:44**（Codex のエラー文 "try again at 12:44 PM" で確認）。それまでワーカーは使えない。
- `tabs` ワーカー（セッション `01a1c8a6-b6d8-72a3-b155-9b8db5ce9f57`、gpt-6.1-sol / high）: フォルダタブの共通部品＋フォルダごと削除（§5.7・§5.8）。**起動直後に利用枠で止まり、まだ何も実装していない**。
  - worktree `E:/Projects/ModdedFiles-wt/tabs`（ブランチ `w/tabs`、dev の 85bc962 から作成。dev より少し古いが、マージで問題ない）。
  - 依頼文 `worker-logs/prompts/tabs.md`。再開は**最初の依頼文をもう一度渡す**:
    `bash scripts/codex-worker.sh resume tabs 01a1c8a6-b6d8-72a3-b155-9b8db5ce9f57 worker-logs/prompts/tabs.md`（background で実行）
- ワーカーは**同時に1つまで**（ユーザーの PC が重くなるため。必要なときだけ2つ）。

## 再開の予約
- CronCreate で **2026-11-11 12:47 に1回だけ**「HANDOFF.md・CLAUDE.md・WORKERS.md を読んで作業を再開」する予約を入れてある（ジョブ ID `9a774ecc`）。
- この予約は **/clear では消えない**（ユーザー確認済み）。**Claude を終了すると消える**ので、その場合はユーザーに手動で再開を頼んでもらう。
- 確認・取り消し: CronList / CronDelete。

## 再開したらやること（順番）
1. ~~`tabs` ワーカーを再開~~ → 12:47 に再開済み（実行中なら完了通知を待つ。止まっていたら同じコマンドで再開）。
2. ~~巻き戻しオプション~~ → 実装済み・`dev` にコミット済み（70586c4、未 push）。元 APK に当てて `jpw.b` の差し替えを確認済み（ハーネス: `build/verify/`、README あり）。
3. `tabs` が終わったらレビュー → worktree でコミット → `dev` にマージ → `bash scripts/build.sh --no-daemon` → push（`feat:` ならプレリリースが出る）→ ユーザーに日本語で報告。
4. Phase 2 の残り（1つずつ）: 空のフォルダのタブ表示（§5.9）→ 現在のタブへ移動（§5.10）。
5. その後: 全パッチをまとめて元の APK に当てる検証ツール（worker が `build/` に残した `VerifyModKit.java` などが参考）、Phase 5 の libVLC エンジン（APK にネイティブライブラリを足せるかの調査、32bit 版 APK で間に合うか。診断の「アプリの ABI」を見る）。

## このあとの実装順（DESIGN.md §7）
- Phase 2: フォルダタブ共通部品（§5.7）、フォルダごと削除（§5.8）、空フォルダのタブ表示（§5.9）、現在のタブへ移動（§5.10）。JobQueue は Phase 3 で導入（§5.8 の 0.7）。
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
