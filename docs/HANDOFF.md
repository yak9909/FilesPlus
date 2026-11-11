# 引き継ぎメモ（最終更新: 2026-11-11 13:40）

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
- Codex の利用枠は 2026-11-11 12:44 に回復済み。
- `tabs` ワーカー（セッション `01a1c8a6-b6d8-72a3-b155-9b8db5ce9f57`、gpt-6.1-sol / high）: §5.7・§5.8 を実装済み（`dev` に統合済み）。**待機中**。§5.9・§5.10 も同じタブ画面のフックを使うので、このセッションを続けて使う（`codex exec resume`）。使う前に worktree `E:/Projects/ModdedFiles-wt/tabs` で `git merge dev` する。
- ワーカーは**同時に1つまで**（ユーザーの PC が重くなるため。必要なときだけ2つ）。

## 再開したらやること（順番）
1〜3. 完了（2026-11-11）: 巻き戻しオプション（70586c4）と tabs（フォルダごと削除、3db74df）を `dev` に統合して push。元 APK に全パッチをまとめて当てられることを `build/verify/` のハーネスで確認済み。**実機確認はまだ**（ユーザーに依頼中: 先頭付近のダブルタップ巻き戻し、カテゴリのタブ画面の︙ →「このフォルダを削除」）。
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
