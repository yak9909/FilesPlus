# 引き継ぎメモ（最終更新: 2026-11-24）

/clear のあとに作業を再開するためのメモ。まず `CLAUDE.md`（運用ルール）と `docs/WORKERS.md`（ワーカーのセッション）を読むこと。

**再開したら、作業に入る前に必ず superpowers のスキルを Skill ツールで読み込むこと（ユーザー指示 2026-11-12。「superpowers に従っている感じがしない」との指摘）。** 最低限 `superpowers:using-superpowers` を読み、そのうえで作業に合うものを読み込んで従う（調査・設計のやり直し → `superpowers:brainstorming`／計画書 → `superpowers:writing-plans`／実装の配分 → `superpowers:subagent-driven-development`／レビュー → `superpowers:requesting-code-review`・`superpowers:receiving-code-review`／完了の判断 → `superpowers:verification-before-completion`）。「覚えているから読まなくてよい」とはしない。CLAUDE.md のユーザールールはスキルより優先。

## 進行中（2026-11-24）— まずここ
- **Task 7 済み（dev d6c32db feat、merge 73e1834、push 済み）**。直し 2 回目を自分で検証: `task-7-verify.ps1` 通過（dex 53,848）、エミュレーターの ART 検証 `Rejected methods: 0`、TestSMB を押すとトーストのあと元の「There's nothing here.」のまま閉じない、︙ は Select all / Sort by だけ、FAB なし（内部ストレージは FAB・Add new folder・Show as media あり）。Opus 再レビュー承認（Critical・Important なし）。
- Minor（Task 7）: 削除済みの接続の行でルートの問い合わせが失敗したときにトーストが出ない／`ClipboardPatch.kt` `folderPopupEntry` の `PRIVATE` 判定が `one` の中で毎回評価される（見た目だけ）／`isFolderProto` が全ストレージブラウザで音量一覧の更新ごとに proto を直列化（負荷は小さい）。
- 実機で見る点（Task 7）: 本物の SMB 共有（空の共有・中身のある共有）を All storage から開く、書き込みの項目が出ないこと、接続を消したあとの行。
- **ユーザー指示（2026-11-24 深夜、就寝中）: 進められるところまで全部進める。使用量の止め・ユーザーの判断が要るなどで中断するときは、必ずこのメモを更新して /clear してよい状態にしてから止める。**
- **Task 8a 済み（dev dcd46b9 feat、merge d18eba7、push 済み）**: 空のフォルダのタブはダウンロードのカテゴリだけ、空かは実際の中身（`.` で始まる名前は数えない）。メインが自分で実装（小さいため）。エミュレーターで確認（FoldA・FoldB が出る、サブフォルダを作ると消える、画像に PicE が出ない）。Opus 承認。Minor: (1) ダウンロードに出るのは一度ダウンロードのタブになったフォルダだけ（新しいフォルダ・画像だけのフォルダは出ない → DESIGN 815・483 行の流れより狭い。以前から同じ）(2) `DOWNLOAD_CATEGORY` の文字列の重複 (3) `LocalSource.mergeKnownFolders` が他カテゴリの既知フォルダを件数 0 で足す（呼び出し元なし？要整理）(4) `reallyEmpty` は全件を list する。設定の「隠しフォルダも表示」に説明を足した。
- **Task 8b 済み（dev e3d05e6 feat、merge 1f50775、push 済み）**: ダウンロードに元のチップ「圧縮ファイル」。タブのクエリに条件（名前の末尾）を AND する方式。taiyo（t20261124-012058-23）が実装し、検証の仕上げの途中で taiyo の使用量の上限（05:47 まで）で中断 → 残りはメインが検証: `task-8b-verify.ps1` 全部通過（12 Applied、Archives の fingerprint 16 本すべて 1 match、dex 53,878）、ART 0 件、エミュレーターで絞り込み・解除・FoldB タブ・画像に出ないこと・ダークモード切り替えとプロセス終了後もチップが出ることを確認。Opus「直しを入れて承認」: Important 1（作り直しでチップが消える）はエミュレーターで再現せず。Minor: (2) フォルダタブのパッチとの包みの順序 → 8c の指示書に追加 (3) カテゴリ番号 2 と OR のモード 1 が固定値 (4) `notifyAdapter` の fingerprint が弱い (5) prepare 失敗後にチップの見た目と状態がずれる (6) ラベルが手書きの言語判定 → 8c の指示書に追加 (7) 診断の行は「Installed」だけ。メモ: エミュレーターで adb shell から作ったファイルは is_download=0 で、ダウンロードに出ない（メディアスキャンで付く）。
- **Task 8c 済み（dev bab10a2 feat、merge 924ae12、push 済み）**: プレビューの長押しで選択の切り替え（設定 Preview →「Long-press to select」既定 ON）＋ 8b の Minor 2 件（Archives の包みの順序、ラベルの言語判定と content description）。taiyo t20261124-055225-25 → 直し 1 回 t20261124-062924-26。直し後: verify 通過（12 Applied、長押しの fingerprint 5 本 1 match、dex 53,881）、ART 0 件、エミュレーターで通常の画像は選べる・ゴミ箱の画像は選ばれない、Opus 再レビュー承認。Minor: 生成する smali の字下げ（見た目だけ）。実機で見る点: 長押しの触覚、動画・音声のページ、設定オフで元のドラッグが出ること（エミュレーターではドラッグの影が見えず未確認）、SMB・金庫・MediaStore id の無いファイルでは元の動き、一覧のドラッグが変わらないこと、ダウンロードでフォルダタブを開いたまま Archives を切り替え。
- （経緯 8c）**06:10 返ってきた（DONE）。自分で検証済み**: `task-8c-verify.ps1` 通過（12 Applied、長押しの fingerprint 3 本 1 match、dex 53,880）、ART 0 件、エミュレーターで長押し→印が付く・めくった直後のページにも効く・2 回目で外れる・設定ページと診断の行あり。設定オフでは選択されない（ただし元のドラッグの影はエミュレーターで見えず未確認 → 実機で見る）。エミュレーターの設定は今オフのまま。Opus レビュー「直しを入れて承認」: Important 1（元のメニューに Select が出ないファイル＝ゴミ箱の `.trashed-` など・proto bit 256 なし、でも長押しで選べる）＋ Minor（触覚の反応なし、registerCount - 2 の固定値）。smali の差し込みは DEX で確かめて正しい。→ **直し 1 回目を taiyo に送信: t20261124-062924-26**（続き、gpt-6.2-sol/high、指示書 `task-8c-fix1-brief.md`）。返ったら: verify → ART → エミュレーターでゴミ箱のプレビューの長押しが選ばないこと＋通常の画像は選べること → 小さい差分の再レビュー → `feat:` → マージ・push。
- **Task 12 済み（dev 4f44616 fix、merge 576eee7、push 済み）**: 直し 1 回は mycodex（ユーザーの今回限りの許可、t20261124-141911-31）。直し後: verify 通過（SMB 診断 23 件、dex 54,186）、ART 0 件、Opus 再レビュー承認。**次の 1 手: Task 14 を taiyo に新しい依頼で送る（`task-14-brief.md`、gpt-6.2-sol/xhigh）。taiyo は 18:04 まで上限 → 18:07 に 1 回だけ再開の予約。mycodex の許可は Task 12 の直しだけだったので、Task 14 は taiyo を待つ。** → 10 → 11。
- （経緯）Task 12（SMB の診断＋H2 の直し）: taiyo が実装（t20261124-130659-29、DONE_WITH_CONCERNS）、自分で検証済み: `task-12-verify.ps1` 通過（13 Applied、87 fingerprint、dex 54,183）、ART 0 件、エミュレーターで診断 → SMB「Live SMB activity」に段階ごとの時間、「Copy thread dump」で Copied。Opus「直しを入れて承認」: Important 2（コピーの上限 40 万文字が binder の 1MB に近い／接続が 60 秒を超えると直後のスイープが新しい entry を捨てる）＋ Minor。**直し 1 回目（`task-12-fix1-brief.md`）は、taiyo が上限のためユーザーの許可（今回限り）で mycodex に新しい依頼として送信済み: t20261124-141911-31（gpt-6.2-sol/high）。** 返ったら → verify → ART → 小さい差分の再レビュー → `fix:` → マージ・push → 14 → 10 → 11。
  - Minor（Task 12）: 接続中の entry を retire しても待っているスレッドは connect の終わりまで待つ（最大約 70 秒）／接続失敗時に待っていた N 本が順に接続し直す（以前から）／close の延期で放置された ReadStream があると壊れた接続が残る（診断の LIVE で見える）／pool の 1 本のスレッドで close が並ぶ（以前から）／設定のいちばん下の行が 3 ボタンのナビゲーションバーに隠れて押せない（以前から。FilesPlus の「Diagnostics」の行も同じ。ジェスチャー操作なら押せる）。
  - 実機で見る点（Task 12）: 長い読み込みが起きたら、すぐ 設定 → FilesPlus → 診断 → SMB の「Copy thread dump」を押して貼ってもらう。
- **Task 9 済み（dev 53b0e82 feat、merge は下のコミット、push 済み）**: 直し 1 回（t20261124-085231-28）。verify 通過（13 Applied、87 fingerprint、dex 54,113）、ART 0 件、エミュレーターで再起動後「4 hits served / 0 misses」＝保存分から表示。Opus 再レビュー承認。Minor: (1) 一時的な失敗（デコーダーが忙しい等）もプロセスが終わるまで失敗扱い (2) キャッシュの消去で失敗の記憶が消えない (3) 確認の作業が 24 枠の待ち行列で作成待ちを押し出しうる。実機で見る点: 初回と 2 回目の開く速さ、再起動後の再利用、設定のオン・オフ・上限・消去、回転・暗い動画。
- （経緯）**Task 9 は taiyo に送信済み（2026-11-24 08:02、t20261124-080209-27、gpt-6.2-sol/xhigh。taiyo の週間枠がリセットされたためユーザー指示で送った）**。返ったら: `task-9-verify.ps1` → ART → エミュレーターで動画のサムネイル → Opus レビュー → `feat:` → マージ・push。**08:40 返ってきた（DONE_WITH_CONCERNS）。自分で検証済み**: verify 通過（13 Applied、86 fingerprint 1 match、dex 54,097）、ART 0 件、エミュレーターで動画のサムネイルが出て、設定 Thumbnails に「1 thumbnails, 4,722 bytes」。長さ 0 の動画（静止画面の録画）は作らずにログ（正常）。保存したものからの表示（ヒット）は画面で見分けられず未確認 → 実機。Opus「直しを入れて承認」（Critical なし）: Important 4（失敗した動画を 30 秒ごとに作り直す、`/file/` の URI を全部動画候補にする、`OPTION_CLOSEST` が重い、表示のたびに UI スレッドでハッシュと stat）＋ Minor。→ **直し 1 回目を taiyo に送信: t20261124-085231-28**（続き、gpt-6.2-sol/high、`task-9-fix1-brief.md`。ヒット数などの数を診断に出すのも含む）。返ったら verify → ART → エミュレーターで診断のヒット数 → 再レビュー → `feat:` → マージ・push → Task 12。
- **ユーザーの追加要望（2026-11-24）→ DESIGN §5.12.3（切り替えを戻る履歴に入れる）と §5.12.7（ストレージブラウザのグリッドの列数、フォルダとファイルで別々に 1〜5）に書いた。指示書は書き済み: `task-10-brief.md`（切り替え）・`task-11-brief.md`（列数）**。Task 9 のあと、同じ worktree で 1 つずつ taiyo に新しい依頼で送る（10 は gpt-6.2-sol/high、11 は gpt-6.2-sol/xhigh）。どちらも `feat:`。
- **さらに追加（2026-11-24）**: (a) SMB の無限ロード（ユーザー報告、未再現。フォルダの出入りを繰り返すとたまに読み込みが終わらない、再起動で直る）→ DESIGN §5.12.7。メインの静的な確認では SMB の呼び出しにタイムアウトあり・プールのロックに止まる組み合わせなし。**推測で直さず、まず診断（呼び出しの時刻・所要時間、開いている proxy fd の数、診断画面の「スレッドの状態をコピー」）を足す**: `task-12-brief.md`（gpt-6.2-sol/xhigh、`fix:`）。出たらユーザーに実機で再現時の診断をコピーしてもらい、原因を決めて直す。(b) SMB のサムネイルも保存して使い回す: `task-13-brief.md` → **ユーザーの判断で見送り**（Glide のディスクキャッシュで既に効いているらしい）。
- (a) の訂正（ユーザーが調べ直し）: 無限ではなく、**ルートに戻るときにたまにとても長い読み込み**。`task-12-brief.md` は書き直し済み。ユーザーの追加情報: 共有名なし、長いときは約 30 秒、ルートをしばらく見ていないと起きる気がする。→ 仮説 H1 再接続の名前解決などが遅い（10 秒の接続の制限の外）／H2 使わない接続を閉じるとき `synchronized(entry)` のまま通信し、`acquire` が待つ（コードで証明できるので直す）／H3 切れた接続で最初の要求が 30 秒待つ。段階ごとの時間を記録して見分ける。接続先は IP（ユーザー）→ H1 の名前解決は外れ、H2・H3 が本命。
- **SMB の設定の使い勝手（2026-11-24 ユーザー要望）→ DESIGN §5.12.4、指示書 `task-14-brief.md`**: 名前の最初の一文字が大文字になる（原因は要調査）、「保存」をなくして変えた時点で保存・反映、「名前の変更」をなくす、保存しても再起動まで反映されない（All storage の SMB の行に通知が届いていないと推定）、削除は一覧で左スワイプ → 確認、FilesPlus の設定の「診断」をなくす。gpt-6.2-sol/xhigh、`feat:`。
- **送る順: 9（実行中）→ 12（診断）→ 14（SMB の設定）→ 10 → 11**。1 つずつ、同じ worktree。
- （経緯）その次: **Task 9（動画のサムネイルを保存して使い回す）**。指示書 `task-9-brief.md` は書き済み（ローカルの動画だけ、外れたら元のまま＋裏で作る、`files/modkit/video_thumbs/`、WebP 512px、LRU 500MB、設定 `thumbs.video.cache` 既定 ON とキャッシュの消去、新しいパッチ "Video thumbnail cache"）。8c が終わってから taiyo に新しい依頼で送る（gpt-6.2-sol/xhigh）。**→ 未送信で止めた（2026-11-24 06:55）**: taiyo の週間枠が 98%（上限 100%、リセット 11-29 0:10）で、重い Task 9 は途中で止まる見込みが高いため。ユーザーに選んでもらう: (a) mycodex（週間 67%、リセット 11-25 12:18）に回す (b) taiyo に送って止まったら 11-29 に続き (c) taiyo のリセットまで待つ。worktree `../ModdedFiles-wt/screens` は dev（4aecabd）にそろえ済みで、変更なし。エミュレーターは起動したまま（設定「Long-press to select」はオン、試しで b.png をゴミ箱へ移したまま。戻すときはゴミ箱から Restore）。

## （経緯）2026-11-23 夜
- **Task 7 の直し 1 回目は済み**（t20261123-194526-15。taiyo の同じセッションの続き）。結果: `task-7-verify.ps1` は通り、エミュレーターの ART 検証は `Rejected methods: 0`。Opus レビューは「直しを入れて承認」（Critical なし）。
- エミュレーターに仮の SMB 接続（TestSMB、10.0.2.250、つながらない）を保存して確かめた。SMB の行は出て、押すと元のストレージ画面が開き、トーストも出る。ただし**一覧の失敗の約 1 秒後に画面が勝手に閉じる**（診断から開いても同じ）。→ 直しの 2 回目を **taiyo に送信中: t20261123-204102-19**（指示書 `task-7-fix2-brief.md`、ログ `task-7-emu-close.log`。どちらも ledger フォルダ）。Minor（favorites、`one(...)`、FAB のコメント）も同じ回に入れた。
- 本物の SMB サーバー（impacket）での確かめは、Defender が読み込みを止めたので諦めた。中身が空の共有を開いたときの動きは、コードを読んで確かめてもらう＋実機で確かめる。
- **直しの 2 回目は返ってきた（未検証）**: taiyo の報告は DONE_WITH_CONCERNS。原因は、元アプリのストレージの見張り（`hug.java:36`・`hsz.java:43`）が、一覧に無い SMB のルートを「取り外された」と見て画面を閉じていたこと。SMB のルートではこの確かめを飛ばし、元の「空のフォルダ」表示のまま残すようにした。変更: `SmbStorageRootsPatch.kt`・`SmbRoots.java`・`ClipboardPatch.kt`・`VerifySmbRoots.java`・`task-7-verify.ps1`・`task-7-report.md`（Fix round 2）。taiyo の自己検証: 11 Applied、fingerprint 51 本すべて 1 match、JVM 170 件 PASS、dex 53,848。**ユーザーの指示で、ここで中断（2026-11-23 夜、/clear のため。予約なし）**。
- エミュレーターには仮の接続 TestSMB（10.0.2.250、共有名 shrae）が保存されたまま。押して閉じないか確かめるのに使う。
- 次の1手: 自分で `task-7-verify.ps1` → `scripts/emu-verify.sh`（0 件）→ TestSMB の行を押して閉じないか確かめ、上の︙・FAB も確かめる → 小さい差分の再レビュー → `feat:` → dev にマージ・push。

## （経緯）2026-11-23 夕方
- **dev.19・dev.20 はカテゴリ（メディアブラウザ）を開くと落ちていた** → 原因と修正は **dev.21（0ab7f89 fix、merge 1def02c）**。Clipboard のフック（`afterBrowserMethod`）で、`addInstructions(index>0, smali)` がラベルを付け替えず分岐先がずれ、ART が `ixh`・`hul.U`・`htc.onCreateContextMenu`・`htp.a/b` を拒否していた。`addInstructionsWithLabels` に変えた。あわせて**クラッシュの記録**（`CrashLog`、診断の先頭に「前回のクラッシュ」）を足した。Opus レビュー承認、Minor は反映済み。
- **エミュレーター**（ユーザー許可 2026-11-23）: AVD `fp31`（Android 12 x86_64、arm64 翻訳のみ → armeabi-v7a のライブラリは外して入れる）。`bash scripts/emu-verify.sh <mpp の絶対パス>` で全クラスの ART 検証（`Rejected methods: 0` 必須）。手動の操作用の補助は `build/emu/`（git 管理外: `t.sh` タップ＋画面、`find.sh` 文字の位置）。CLAUDE.md に手順を追記。
- **Task 7（SMB をストレージブラウザで）は taiyo の実装が返ってきた（t20261123-150627-9）。dev を w/screens に取り込み済み（52ad3c9 へ早送り、Task 7 の未コミットの変更はそのまま）。`task-7-verify.ps1` は通る（11 Applied、dex 53,848）が、`scripts/emu-verify.sh` で ART が 2 つ拒否**: `hul.w`（末尾に足したコードが R8 に再利用された p0 を使う）と `SmbRoots.validRow`（生成したコードで v0 を int と object に使い回し）。直しの指示書 `task-7-fix1-brief.md` は書き済み。**taiyo は使用量の上限で 19:44 まで受付不可 → ユーザーの選択で待つ。19:44 に 1 回だけ再開の予約（CronCreate、e3928e3d）。taiyo がまだ受付不可なら過ぎるまで待ち、ほかの貸し手には回さない**。エミュレーターは起動したまま（止まっていたら `emulator -avd fp31 -no-snapshot -no-audio -gpu swiftshader_indirect` を裏で起動）。次の1手: `send_task(continue_task_id=t20261123-150627-9, model gpt-6.2-sol, effort high)` で指示書を送る → `task-7-verify.ps1` → `scripts/emu-verify.sh`（0 件）→ Opus レビュー → `feat:` → dev にマージ・push。
- ユーザーには dev.21 で直ったか実機で確かめてもらう（画像・ダウンロード・動画のカテゴリ、フォルダの長押し・︙）。

## 進行中（2026-11-20）
- **Task 4 済み**（dev ca5a204 chore、merge 72a059a。未 push）。taiyo（codex-bridge）が直し 2 回、Opus の再レビューで承認。Minor は ledger（M1〜M16、R1〜R9）。
- **Task 5 は 5a（切り取りの入口）と 5b（下端の貼り付けボタン・貼り付け先・設定ページ、feat）に分けた。**
- **Task 5a 済み（2026-11-23、dev d3861ea chore、merge 42ed5b9。未 push）**。パッチ "Clipboard"（9 パッチ全部 Applied）。Opus レビュー → 直し 1 回 → 再レビューで承認。Minor は ledger。
- **Task 6 済み（2026-11-23、dev 5cd589d feat、merge ca202f1、push 済み）**。taiyo の直し 1 回（t20261123-144424-7）→ 自分で task-6-verify.ps1（BUILD OK、10 Applied、全 fingerprint 1 match、自己テスト全 PASS、dex 53,767）→ Opus 再レビュー承認。Minor: `ViewSwitch.java:82` 生パスに `%XX` を含む名前を誤ってデコード／`SettingsInjectionPatch.kt:243` の fingerprint の行名が "Switch view"。実機で見る点: 切り替えのメニュー、ボリュームの一番上からの切り替え（「すべて」タブ）、戻るの動き。**Task 7（SMB をストレージブラウザで、P4）を taiyo に送った（2026-11-23 15:06、t20261123-150627-9、gpt-6.2-sol/xhigh、指示書 `task-7-brief.md`）**。終わったら: 自分で `task-7-verify.ps1` → Opus レビュー（差分はファイルで）→ 直し → `feat:` でコミット・マージ・push。
- （経緯）**（2026-11-23 11:20 利用枠 5h 81% で中断。リセット 14:20）Task 6（表示の切り替え）は実装済み・未コミット**（worktree `../ModdedFiles-wt/screens`）。taiyo（t20261123-105427-5、gpt-6.2-sol/high）が実装、自分で検証済み（BUILD OK、10 Applied、fingerprint 37 本すべて 1 match、自己テスト全部 PASS、dex 53,753）。Opus レビューは承認だが Important 1 件（I1: 画面の持ち主の Activity を許可リストで確かめていない → 置き換えで別の画面を閉じるおそれ）と Minor 5 件。
  **次の1手: 直しの指示書 `task-6-fix1-brief.md`（書き済み）を taiyo に `continue_task_id` t20261123-105427-5（model gpt-6.2-sol、effort high）で送る → 自分で `task-6-verify.ps1` → 小さい差分の再レビュー → `feat:` でコミット（`git -c core.autocrlf=true add`）→ dev にマージ → push（確認不要）。** 詳細は ledger。
- dev は 2329214 まで push 済み（2026-11-23、ユーザー: push は確認不要）。main は未更新（安定版のマージはユーザーが決める）。
- **Task 5b 済み（2026-11-23、dev b013584 feat、merge 2329214）**。taiyo（gpt-6.2-sol/xhigh）が実装、Opus レビューで承認（Critical・Important なし、Minor N1〜N9 は ledger）。**Task 5（P2）完了。push するとリリースが出るので、push の前にユーザーに確認する。** 実機で見る点: 下端のボタン欄の 2 通りの見た目（N3: メディアブラウザで 2 個ボタン版が出ないかもしれない）、上の︙の項目、「毎回選ぶ」の移動画面から戻ったとき、貼り付け後の一覧の更新。次は計画の Task 6（表示の切り替え、P3）。
- コミットの注意: worktree の一部のファイルは作業ツリーが CRLF（以前からの改行の差）。add は `git -c core.autocrlf=true add <files>` で LF にして載せる。PowerShell の `git diff --cached --stat` は大きく見えるが、Bash の git で確かめると正しい。
- （済み）Task 5b を taiyo に送った（t20261123-092622-4）。以下は送る前のメモ。
- （済み）Task 5b の指示書を書いて taiyo に送る（新しい依頼。lender taiyo、**model gpt-6.2-sol**、effort xhigh。続きの依頼では model 指定が効かず前のモデルのままだったので、終わったら実際のモデルを確かめる）。指示書 `task-5b-brief.md` はまだ無い。中身の要点（メインが決めたこと）:
  - 下端のボタン欄: クリップボードが空でないあいだ両ブラウザの `main_coordinator` に足す。FAB が `create_new_folder_expressive` なら picker の `button_section`（「クリア」＋「ここに貼り付け（n 件）」）、でなければ `framed_single_button_view`（1 個、「クリア」は上の︙）。一覧の下の余白を足し FAB を上げる（P0 §2、DESIGN §5.12.2「切り取り中の見せ方」）。`Clipboard.addListener` で出し入れ。
  - 貼り付け先: ストレージブラウザ＝今のフォルダ（今のフォルダを取るフックが要る。proto をバイト列で渡し `ProtoNode` で読む。P0 §1.3）、メディアブラウザのフォルダタブ＝`FolderTabContext` の解決、「すべて」タブ＝`paste.allTab`（`PasteTarget.ofAllTab`）。SMB は貼り付け先にしない。
  - 押せないとき: ボタンを押せない状態にし、ラベルを `PasteRules.message` の短い理由にする（Ruling。元の部品のまま）。上の︙の予備: 「貼り付け（n 件）」「クリップボードをクリア」。
  - `paste.allTab = ask`（ledger M2）: 元の picker を開く。結果を受けるため拡張に画面を持たない透明の中継 Activity（`startActivityForResult` → `OriginalScreens.readPicked`）を置く（Ruling。見た目は増えない）。
  - 実行は `MoveQueue.paste(app, state, dest, createIfMissing, callback)`。終わったら完全なパスと件数を知らせる（元に戻すは無し）。
  - 設定ページ `clipboard` に `paste.allTab`（できない（既定）／カテゴリの既定フォルダ／最後に貼り付けたフォルダ／毎回選ぶ）を既存の単一選択ダイアログで。
  - 診断にフックの入り具合の行。検証: ビルド、`build/verify-out/verify.ps1`（9 Applied、rules ALL PASS）。コミットは `feat:`（push するとリリース。push の前にユーザーに確認）。

## （経緯）2026-11-19 — 計画 `docs/plans/2026-11-12-original-screens.md` を実行中
- **今の決まり（2026-11-19 ユーザー）: Codex は使わず Claude のモデルだけ**（実装 Sonnet、レビュー Opus 5.5）。進み具合の正本は worktree の ledger `../ModdedFiles-wt/screens/.superpowers/sdd/2026-11-12-original-screens/progress.md`（指示書・報告・Minor の一覧もそこ）。
- **Task 3 済み（dev cc51db4 feat、merge ce7e3fc。未 push）**: Mod 設定は元の設定画面へ（パッチ "FilesPlus settings"、`SettingsActivity` の singleTask を外す）。自前の画面と "Move queue in preview"・"Move to current tab" は削除。選択を取るフックは隠しパッチ `foldertabs/TabSelectionHookPatch.kt`（Task 5 は直接 dependsOn すること）。Minor 11 件は ledger。**push は未確認**（feat なので push するとリリースが出る。P1 は単独で出してよいとユーザー決定済みだが、push の前にユーザーに確認する）。
- **（2026-11-19 利用枠の見張りで中断）Task 4（クリップボードと貼り付けのコア）は実装済み・未コミット**（worktree `../ModdedFiles-wt/screens`）。Opus のレビュー: Critical なし、Important 3（I1 診断の試験が「最後に貼り付けたフォルダ」を上書き、I2 途中で止まったときの 0 バイトの予約ファイルに気づけない、I3 フォルダが 1 件・0 バイトと数えられ 50 件/100MB の止めが効かない → 決定: フォルダは中身で数える）。直しは途中（I2・I3 はコードあり・未試験、I1 の配線・試験の追加・診断の行が残り）。**次の1手（2026-11-20 更新: 実装は codex-bridge の貸し手 taiyo、レビューは Opus 5.5）**: taiyo に指示書 `../ModdedFiles-wt/screens/.superpowers/sdd/2026-11-12-original-screens/task-4-fix1-finish-brief.md` で直しの残りを頼む（Sonnet の実装者 a2bffda1c68b8b5d7 は使わない） → ビルド＋ harness ＋ JVM の自己テスト → Opus の再レビュー → コミット（Task 4 は内部の部品なので `chore:`）・マージ → Task 5。残りの手順は ledger と `task-4-report.md` の「Fix round 1 (PARTIAL)」。

## （経緯）2026-11-13 の進行中メモ
- Task 0・1（P0 調査）済み: 報告 `docs/research/2026-11-12-original-screens-p0.md`、DESIGN 0.14 に反映。ユーザー決定: ゴミ箱は「FilesPlus」タブを足す形。
- Task 1b 済み: `ui/ProtoNode`・`ui/ScreenArgs`（dev 628aeb2、chore）。レビューの Minor で残したもの: `selfTest` の dump の確認が弱い（中身の一部しか見ない）。`deviceSelfTest` は端末でしか動かない（未実行）。
- **Task 2 済み（2026-11-18、dev 5fc32fd、chore）**。直しは Opus が再レビューして承認。**Task 3 は codex-bridge の Codex に依頼したが、ユーザーの指示で中断した（2026-11-18 12:40 ごろ、タスク t20261118-122233-2、state cancelled、モデルは貸す側の既定で不明）。別セッションで codex-bridge を直している（依頼ごとのモデル指定、完了の合図など）。worktree `../ModdedFiles-wt/screens` に途中までの変更が残っている（新規: `settings/PrefBridge.java`・`settings/SettingsPages.java`・`patches/.../foldertabs/TabSelectionHookPatch.kt`。変更: `ModKitIntegrationPatch.kt`・`FolderTabContext.java`・`PreviewHooks.java`・`SettingsHooks.java`。未ビルド。worktree の多くのファイルが改行コードの違いだけで「M」に見えるので、差分は `git diff --ignore-space-at-eol` で見る）。再開の選択肢: `send_task(continue_task_id=t20261118-122233-2)` で続きを頼む、または途中の変更を捨てて新しく頼む（どちらにするかはユーザーに聞く）。終わったら: Task 3 の検証（ビルド、`build/verify/work` で全パッチ、`git diff --ignore-space-at-eol`）→ Opus 5.5 のレビュー。モデルの使い分け（ユーザー指定）: 単純作業は gpt-6-luna、それ以外は gpt-6.1-sol、effort はメインが決める**（CLAUDE.md の新しい決まり: 実装は codex-bridge、レビューは Opus 5.5）。harness の作業場所は `build/verify/work`（base.apk もそこ）。
- （経緯）2026-11-13 利用枠 5 時間 80% で中断したときのメモ: Task 2 は実装済み・未コミット（worktree `../ModdedFiles-wt/screens`、`ui/OriginalScreens`、パッチ "Open folder stack"、`ModSettingsActivity` の診断 5 行）。ビルド OK、9 パッチすべて Applied（メインが確認）。Opus のレビュー: Critical なし、Important 1（パッチが外されたときの検査）＋ Minor 4 → 実装者が直した（未確認）。**次の1手**: 直しのビルド＋ harness を確認 → Sonnet で直した箇所だけ再レビュー（項目: `openStorage` のフックの検査、`truncatedCopies` のログ、`validateBrowser` のつながりの検査、`stackFor` の USB の扱い、`validateMedia` の 2..6）→ `chore:` でコミットして dev にマージ → Task 3。Minor で残すもの: `hsy.a` の繰り返しの途中で例外が出ると、積みが途中まで残る（元の 1 回の呼び出しが例外を出すのと同じ結果）。`truncatedCopies` は 1 段のときも毎回バイト列を読み直す（軽い）。USB OTG と SD カードを API 31 で確実に見分ける方法が無く、「USB」を含まない取り外し可能なボリュームは SD として扱う。
- `build/verify/VerifyRewind.java` の `Set.of(...)` は古く、5 パッチしか当てていなかった（Change package name・Multi-select in preview・Move queue in preview が抜けていた）。Task 2 の実装者に全パッチを入れるよう頼んだ。harness は patches の jar ではなく `patches/build/classes/kotlin/main` をクラスパスに入れる（`-cp` は `E:/...` の形で）。

## （経緯）2026-11-12 14 時ごろ — P8 の調査結果
- 元アプリの画面は Intent extra `activity_params`（`com.google.protobuf.contrib.android.ProtoParsers$InternalDontUse`、難読化されていない Parcelable。中身は proto のバイト列）で引数を受ける。picker は `huv{ hut{ c: kqg ルート, d: kqg の積み（ルート→今のフォルダ）, e: 表示, f: モード } }`。拡張から作れる見込み（`kqg` の各フィールドの意味は `kqg.a.n()` を使う箇所 dkc/gip/icb/ikr/iop/jdu/opz/pyx で確認中）。
- フォルダ一覧の層（`qgc.b(Uri)` → `qhg.l`）は、file:// に加えて**端末に登録された DocumentsProvider の tree URI を受け付ける**（jadx の通常出力は `return true` が欠けて見える。fallback モードで確認）。→ SMB を元の `AdvancedBrowsingActivity` で閲覧できる可能性。未確認点: `kql` が STORAGE_LOCATION_UNKNOWN になる経路、子の問い合わせ、読み取り専用での操作メニュー。
- 設定画面は `lna.ew()` がコードで Preference を組む（キー "ABOUT_FILES_PREF_KEY" など）。末尾に項目を差し込める見込み（Preference の API は R8 で改名 → パッチ側でバイトコードを出す）。
- ユーザーから追加の要望 6 件（空フォルダは Download だけ／.FilesPlus のゴミ箱を元のゴミ箱で確認・復元／圧縮ファイルのカテゴリ／ローカルでもサムネイルキャッシュ／プレビューの長押しを複数選択に／SMB の入口を「すべてのストレージ」へ）。現設計との照合を提示済み。
- ユーザーの回答（2026-11-12）: 移動先のブラウザは「元の Files の1列のファイル一覧にフォルダの考えを足したもの」にし、キューも使う（どの元画面を土台にするか確認中）。ゴミ箱は元の画面にするが、フォルダごとの削除の取り消し（全部・一部を戻す）は移動のブラウザと同じ形がよい、との提案。圧縮ファイルは (a) 案だが**ダウンロード**のカテゴリにチップを足す。サムネイルは**動画**で遅い。
- 追加（同日）: 土台の画面＝元のカテゴリ画面（フォルダタブ付き、リスト／グリッド。メディアのカテゴリはグリッドが既定でファイル名なし）。ユーザーの新案: MiXplorer のような「切り取り・コピー → 今開いているフォルダ（ブラウザでもカテゴリのタブでも）に貼り付け」方式。採否を確認中（推奨: 採用、元の「移動」は残す）。
- 決定（同日）: 自前のブラウザは作らない。元の内部ストレージの閲覧画面（`AdvancedBrowsingActivity`、仮名「ストレージブラウザ」）を SMB ブラウザにも使う。貼り付け先はストレージブラウザの今のフォルダ、またはメディアブラウザ（カテゴリ画面）の開いているタブ。「すべて」タブでの貼り付けは設定（既定: できない）。ストレージ⇔メディアの表示切り替えを付ける（「すべて」から切り替えたときの行き先は設定）。足りない定義は Opus が補って設計案をチャットで提示中 → 承認後 DESIGN に新しい節として書く。
- **DESIGN 0.13 §5.12 に書いた（§5.11 を置き換え）**。builder check（Opus）の 9 問は §5.12 に反映済み。ユーザー決定: P1 と P2 は別々に出してよい／§5.10「"<タブ名>" へ移動」は削除。ユーザーが §5.12 を承認 → 計画書 `docs/plans/2026-11-12-original-screens.md`（Task 0〜10）を書いた（`unified-move-browser.md` は使わない）。**方針（ユーザー指示）: 実機での確認は使わず、静的解析だけで解明する（dex は jadx の通常＋fallback 出力、ネイティブは idalib-mcp）。** 計画書もそれに合わせて直した（Task 1 = 静的解析で引数の型紙、Task 1b = proto の読み書き）。**次の1手**: Task 0・Task 1（メインの Opus が自分で調査）。

## 最新の中断（2026-11-12 11 時ごろ、5時間枠 83%。リセット 13:50）— まずここを読む
- **再開の予約**: 13:53 に1回だけ自動で再開する予約を立てた（`CronCreate`、起動時に superpowers スキルを読む指示つき、セッション内だけ。Claude Code を閉じると消える。`CronList` で確認）。消えていたら、ユーザーが「再開」と言ったときに下の「次の1手」から進める。
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
