# 引き継ぎメモ（最終更新: 2026-11-12）

/clear のあとに作業を再開するためのメモ。まず `CLAUDE.md`（運用ルール）と `docs/WORKERS.md`（ワーカーのセッション）を読むこと。

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
0. **Phase 4（SMB）は進行中**（ユーザー指示 2026-11-12）。計画書 `docs/plans/2026-11-12-smb.md`、調査と決定は DESIGN §5.3。**タスク1（smbj 同梱＋診断の「SMB テスト」）は `dev` にマージ済み（dev.9 としてリリース予定）**。次はタスク2（`SmbVfs` コアと接続先の保存＝Keystore AES-GCM、Mod 設定の接続先 UI）。タスク2〜7 の詳しい手順は、前のタスクのレビューが終わってから計画書に書き足す（まだ概要だけ）。実機で確かめること: 診断画面の「SMB テスト」で、正しいパスワード（一覧と 1 KB の読み出しが出る）・間違ったパスワード（`STATUS_LOGON_FAILURE`）・間違った共有名（`STATUS_BAD_NETWORK_NAME`）・間違ったホスト（接続エラー）を試し、結果を共有してもらう（slf4j の警告や `NoClassDefFoundError` / `VerifyError` が出たら貼ってもらう）。拡張の dex は 52,785 メソッド（上限 65,536）。R8 の準備を、大きなものを足す前に入れる。worktree `ModdedFiles-wt/smb`（`w/smb`）。
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
