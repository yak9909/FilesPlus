# 元の画面で動かす — P0 調査（DESIGN §5.12 の Task 0・Task 1）

- 対象: Files by Google versionCode 2081362。**静的解析だけ**（jadx 1.5.6 の通常出力＋制御フローに関わる所は fallback モード、apktool のリソース）。実機での確認はしていない。
- 難読化名（`hut`・`kqg` など）はこの版だけの目印。パッチの fingerprint には使わない（文字列・リソース名・難読化されていないクラスを使う）。
- 確度: **確定** = コードで確かめた（制御フローが効く所は fallback でも確認）／**推定** = 読みから推した。
- fallback で確かめたクラス: `jad`（タブの選択）、`hsz`（Up の処理）、`hsy`（fragment の積み）、`qgc`（`b(Uri)` の例外時の戻り値）。出力はスクラッチパッド `p0/`。

---

## 0. まず押さえる訂正（§5.12.1 との違い）

| §5.12.1 の記述 | 実際 | 確度 |
|---|---|---|
| ブラウザの `activity_params` は `hut` を直接 | **`hus { 1: hut }`** という包み（picker の `huv { 1: hut }` と同じ形）。`hsy` のコンストラクタが `rek.F(hus.a)` で読む | 確定 |
| extra の値は `ProtoParsers$InternalDontUse` | 元のコードが入れるのは **`Bundle { "protoparsers": ProtoParsers$InternalDontUse }`**（`uee.p`）。読む側の `uee.m` は Bundle でも Parcelable 単体でも受ける。picker の結果 `ADVANCED_BROWSING_PICKER_RESULT` も同じ Bundle の形 | 確定 |
| `hut` のフィールド番号 1〜5 | 1〜5 で合っている（下の表）。ただし `kqg` のフィールド番号は**文字の順と一致しない**（e = 2、f = 3、h = 10 など） | 確定 |
| `hut` の 4 = 「2 コピー / 3 移動 / 4 保存」 | wire の値は **1 = 閲覧、2 = コピー、3 = 移動、4 = 保存**（§5.12.1 の値は内部で +1 した後の値だった） | 確定 |

**落ちる・閉じる危険（§5.12.10 に効く）**
1. `AdvancedBrowsingActivity`・`AdvancedBrowsingPickerActivity`・`TrashActivity` は、`activity_params` が無い／読めないと `rek.F` が null を返し、peer のコンストラクタの `getClass()` で **onCreate の中で NPE で落ちる**（`hsy`・`hvd`・`lyq`）。「開けなかったら元の画面で開き直す」は起動後にはできない。**起動の前に**バイト列を自前で検査すること。`hut.d` が空でも `d.get(size-1)` で落ちる（`hul`・`hvo`）。picker で `hut.f` が 2〜4 以外だと `hvg` が `IllegalArgumentException("Unexpected browsing mode!")`。
2. ブラウザの fragment は開いた直後に **`kqg.f`（今のフォルダの f）を `qgc.b(Uri)` で引き、無ければ activity を閉じる**（`htc.M` → `lrx.b(Uri.parse(i.f))` = `ROOT_URI_EXIST_DATA_SOURCE_KEY` → `hui.b` が false なら `hss` → `hsz` case 1 → `finish()`）。`qgc.b` は例外を握って「無し」を返す（fallback で確認）。データの中身は `krx` case 12 = `Boolean.valueOf(qgc.b(uri).f())`。**f が空・不正・存在しないと、開いた瞬間に閉じる**（推定。反例の疑い: ゲートウェイの入口 `kcn`（`hyj` case 14）は `dkc.k` でルートを作り f を付けないので、この読みどおりならその入口は開いてすぐ閉じることになる。元アプリの不具合か、どこかで f が補われるのかは未確認）。**いずれにしても f は必ず付ける**（ルートは f = e、子は f = 親の URI）。
3. **積み（`hut.d` が 2 個以上）で開くと、戻る・パンくずが壊れる。** `hsy.a(hut)` は `d.size() > 1` のとき最初の fragment を back stack に名前 `"0"` で積む（`ca.n` = addToBackStack、fallback で確認）。戻るでその fragment が外れて中身が空になり、パンくずの押下（`hsu` → `popBackStack(Integer.toString(i), inclusive)`）も対応する entry がない。元アプリで積み付きでブラウザを起動する箇所は**無い**（`hus` を作るのは `ioy.c`・`kcn`（`hyj` case 14）・`kbs` だけで、どれも `d` は 1 個）。→ §5.12.3「ボリュームのルートからの積み付きで開く」は**そのままでは不可**。下の 1.7 の対策が要る。

---

## 1. 元の画面の引数の型紙（Task 1）

### 1.1 包み方（3 画面共通）
- `Intent().setComponent(new ComponentName(ctx, "<Activity のクラス名>"))`、extra `"activity_params"` = `Bundle`、その中の `"protoparsers"` = `new ProtoParsers$InternalDontUse(bytes, null)`（`uea.j` → `uee.p`）。フラグは画面内からの起動では付けない（ゲートウェイからは `0x10008000` = NEW_TASK|CLEAR_TASK）。 **確定**
- `ProtoParsers$InternalDontUse`: `writeToParcel` は `writeInt(len)` ＋ `writeByteArray(bytes)`、CREATOR（`mwj` の default）は `readInt` → `readByteArray`。読む側は `vly.I().e(bytes, registry).o()`（builder に merge）なので未知のフィールドは保たれる。 **確定**
- 読む側 `uee.m`: `bundle.getParcelable("activity_params")` が Bundle なら中の `"protoparsers"`、そうでなければそれ自体を `ProtoParsers$InternalDontUse` として使う。 **確定**

### 1.2 記述子（`vmi` の文字列）の読み方
protobuf-lite の `RawMessageInfo`: 先頭 `\u0004` = editions 構文、続いて フィールド数・oneof 数・hasbit 数・最小番号・最大番号・項目数・map 数・repeated 数・必須検査数、そのあと各フィールドが「番号、型（下位 8 bit。0x1000 = hasbit あり、0x800 = 閉じた enum の検査）、hasbit 番号 or oneof 番号」。型: 2 = int64、4 = int32、7 = bool、8 = string、9 = message、12 = enum、26 = repeated string、27 = repeated message、51 + n = oneof（59 = string、55 = int32）。

### 1.3 各メッセージのフィールド（確定）
**`hus`（ブラウザ）・`huv`（picker）**: どちらも `{ 1: message hut }`（`ဉ\u0000` = message、hasbit 0）。

**`hut`**（`\u0004\u0005\u0000\u0001\u0001\u0005\u0005\u0000\u0001\u0000` + …）
| 番号 | wire | Java | 意味 | 値の出どころ |
|---|---|---|---|---|
| 1 | LEN（message `kqg`） | `c` | ボリュームのルート（積みの 0 番と同じもの） | `ioy.c(kqg)`・`hsz` case 5 |
| 2 | LEN（repeated `kqg`） | `d` | 積み（ルート → 今のフォルダ）。パンくず（`iad.aI`）と、今のフォルダ `d[size-1]`・ルート `d[0]` | `vkn.z(kqg)` = add |
| 3 | VARINT（enum `kqf`） | `e` | 表示: 0 UNKNOWN / 1 GRID_MODE / 2 LIST_MODE。初回の起動では**付けない**（0）。フォルダに入るときに今の表示を写して次へ渡す（`fmt` case 5、`hun` case 10・11） | — |
| 4 | VARINT（enum） | `f` | モード: **1 = 閲覧、2 = コピー先、3 = 移動先、4 = 保存先**（検査 `a.ac`: 0〜4） | ブラウザ 1（`ioy.c`・`kbs`・`kcn`）、picker 2/3（`hsz` case 5） |
| 5 | VARINT（enum） | `g` | 入口: **1 = 外部（ゲートウェイ・ディープリンク）、2 = ホームの一覧から**、0 = 未設定（picker）。g = 1 のときだけ、back stack が空で Up を押すと Home を開く（`hsz` case 3、`gsd.j(9)`、fallback で確認） | ホーム 2（`ioy.c`）、外部 1（`kbs`・`kcn`） |

**`kqg`**（`\u0004\b\u0001\u0001\u0001\f\b…`: 8 フィールド、oneof 1 個、番号 1〜12）
| 番号 | wire | Java | 意味 |
|---|---|---|---|
| 1 | LEN（oneof string、case 1） | `d`/`c=1` | 表示名（フォルダ名。USB では端末の名前） |
| 2 | LEN（string） | `e` | **このフォルダの URI**。一覧の読み込み（`hte` → `lsd.e` → `qgc.b(Uri.parse(e))`）、選択の鍵（`hwd.b` は `kqg.e` で引く）、差分（`iov`） |
| 3 | LEN（string） | `f` | 「存在を確かめる URI」。ルート（`dkc.l`）では **e と同じ**。一覧から作った子（`opz`）では**一覧を作った親フォルダの URI**。新しいフォルダ（`icb` case 10）では親の f を写す。`dkc.k` は付けない |
| 7 | VARINT（oneof int32、case 7） | `d`/`c=7` | 表示名の文字列リソース id（内部ストレージ = `R.string.internal_storage` 0x7f130270、SD = `R.string.sd_card` 0x7f1304b5。**id は版ごとに変わるので実行時に `getIdentifier`**） |
| 8 | VARINT（int64） | `g` | `qbd.a()` = サイズ（フォルダでは 0 か provider の `_size`）。`dkc.k` だけが付ける。実質使われない |
| 10 | VARINT（enum `kql`） | `h` | ストレージの種類: 0 INTERNAL / 1 SD_CARD / 2 STORAGE_LOCATION_UNKNOWN / 3 USB / 4 ADOPTABLE_SD_CARD。**hasbit 付きなので 0 でも書かれる**（`50 00`） |
| 11 | VARINT（int64） | `i` | 空き容量（バイト）。ホームの行の副題「空き …」にだけ使う（`iou`）。ルートの内部ストレージ（`ikr` case 15）と SD（`rjn`）だけ |
| 12 | LEN（message `vmw` = Timestamp `{1: int64 秒, 2: int32 ナノ秒}`） | `j` | 更新日時（`qbd.d()`） |

**`jce`（picker の結果）**: `{ 1: VARINT enum c（1 = コピー、2 = 移動、3 = 保存）, 2: LEN kqg d（選んだフォルダ） }`。

**`jcp`（メディアブラウザ）**: §3 を参照。**`lyp`（ゴミ箱）**: `{ 1: VARINT enum c }`（§8）。

### 1.4 URI の形（確定）
- ローカルのフォルダ: `Uri.fromFile(file).toString()` = `file:///storage/emulated/0/DCIM`（パスは Uri のエンコード。空白は `%20`）。内部ストレージのルート = `file:///storage/emulated/0`（`qey`/`qex` の `Uri.fromFile`）。SD のルートも file の URI（`qft.b` = `qek`、`b()` は中の `qey` の `Uri.fromFile`）。
- e・f は**文字列のまま比べられる**（`iov`・`hwd`・`Objects.equals(hul.i, kqg)`）ので、必ず `Uri.fromFile(file).toString()` で作る（自前でパスを連結しない）。
- USB は `content://com.android.externalstorage.documents/tree/...`（`ijw` case 13 が e = f = 許可された tree URI にする）。→ ブラウザは tree URI のフォルダで実際に動いている。

### 1.5 型紙（バイト列の組み方）
**(a) ストレージブラウザ: ホームの「すべてのストレージ」→ 内部ストレージ**（`ioy.c`、確定）
```
kqgRoot = { 2: "file:///storage/emulated/0", 3: 同じ, 7: <internal_storage の id>, 10: 0,
            11: <空きバイト（任意）>, 12: { 1: 秒, 2: ナノ秒 }（任意） }
hut     = { 1: kqgRoot, 2: [kqgRoot], 4: 1, 5: 2 }        // 3 は付けない
hus     = { 1: hut }
→ Intent(component = AdvancedBrowsingActivity, extra "activity_params" = Bundle{"protoparsers": bytes(hus)})
```
SD カード: e = f = `file:///storage/XXXX-XXXX`、7 = `sd_card` の id、10 = 1。11・12 は表示にほぼ効かない（12 は `dkc.l` が必ず付ける。付けておく）。
**ルート以外のフォルダを開くとき**の子の `kqg`（`opz` と同じ形）: `{ 1: 名前, 2: 子の URI, 3: 親の URI, 10: 親の種類, 12: 更新日時 }`。

**(b) picker（元の「移動」）**（`hul.ao(3)` → `jbx(rootKqg, 3)` → `hsz` case 5、確定）
```
hut = { 1: kqgRoot, 2: [kqgRoot], 4: 3 }     // 移動。コピーは 4: 2、保存は 4: 4。3・5 は付けない
huv = { 1: hut }
→ startActivityForResult(Intent(component = AdvancedBrowsingPickerActivity, extra "activity_params" = Bundle{...}))
```
`kqgRoot` は「すべてのストレージ」の行と同じもの（`hul.ae` = `lsd.i(3)` の `isl` → `isb.c`）。ルートが 1 つだけなら元アプリは選択画面を出さずにすぐ picker を開く（`hul.ao`）。

**(c) ゴミ箱**（`lid`: `uea.i(lyp.a)`、確定）: `lyp` は空（0 バイト）でよい。外部からは `{ 1: 1 }`（`kbs`）。

### 1.6 結果の読み方（picker、確定）
- 「ここに移動」ボタン（`single_action_button` / `action_button`、`fqf` case 6・7）→ `hux(hvo.d = 今のフォルダ, mode)` → `hun` case 6 → `setResult(-1, intent)`、extra `"ADVANCED_BROWSING_PICKER_RESULT"` = Bundle{"protoparsers": `jce`}。
- picker の中で「新しいフォルダ」を作ると、その場で作ったフォルダを結果にして閉じる（`hva` → `huz` → `hun` case 9）。
- 読み方: `jce` のフィールド 2（`kqg`）のフィールド 2（e）を `Uri.parse(e).getPath()`。フィールド 1 は 1 コピー / 2 移動。

### 1.7 積み付きで開く方法（未解決 → 対策案）
元アプリにはこの起動の仕方が無い（§0 の 3）。案:
- **案 A（推奨）**: `AdvancedBrowsingActivity.onCreate`（難読化されていないクラス）の中の、peer の「初回の fragment を置く」呼び出し（`hsy.a(hut)`、`setContentView(R.layout.advanced_browsing_activity)` の直後で `dX().d(R.id.content) == null` のとき）を拡張の関数に差し替え、`d` の先頭 k 個（k = 1…n）の `hut` を順に `hsy.a` に渡す。普通に 1 段ずつ入ったときと同じ back stack（名前 "0", "1", …）になる。`hut` の複製は、拡張で `ProtoParsers$InternalDontUse(null, hut)` の `writeToParcel` でバイト列を取り、`d` を切り詰めて `ProtoParsers$InternalDontUse(bytes, null)` の 2 引数のメソッド（`b(vly, vkg)`）で戻す（型はリフレクションで引数の型から取る）。
- 案 B: ルート 1 個で開き、開いたあとに fragment の「フォルダを押した」経路（`huw` イベント）を順に流す。フックが 2 か所になり、非同期で読み込み中の一覧と競合する。
- どちらもフックが要る。案 A は onCreate の 1 か所で済む。 **推定**（案の可否はコードの読みから。実装時にもう一度確かめる）

---

## 2. 下端のボタン欄（`framed_single_button_view` / `button_section`）

**結論**: どちらの画面の layout にも入っていない。両方とも親が `CoordinatorLayout`（id `main_coordinator`）なので、**実行時にその中へ足せる**。 **確定**（layout）／差し込みは **推定**

- `framed_single_button_view.xml` は**独立した layout リソース**（根は `LinearLayout` id `action_button_container`、`layout_gravity="bottom"`、中に `MaterialCardView` → `MaterialButton` id `single_action_button`）。`LayoutInflater.inflate(R.layout.framed_single_button_view, mainCoordinator, true)` で、XML の `layout_gravity` が CoordinatorLayout.LayoutParams として効く。ボタンは 1 個だけ（「ここに貼り付け（n 件）」）。「クリア」は置けない。
- `button_section` は **`advanced_browsing_picker_fragment.xml` の中に直接書かれている**（独立した layout ではない）。ボタン 2 個（`new_folder_action`（outlined）＋ `action_button`）。使うなら picker の layout を丸ごと inflate して `button_section` を外し、目的の coordinator へ付け替える（P8 的には「元の部品」だが手間）。
- picker がどちらを出すかは phenotype フラグ `com.google.android.apps.nbu.files.device` / `45676054`（`hrn.fz()` → `lrv`（`hrn.nk`））。**true なら `button_section`、false なら `framed_single_button_view`**（`hvg.M`）。ストレージブラウザの `hul.aC` も**同じ** `hrn.nk`（`hsg` の `new hul(...)` と `new hvo(...)` の引数）で、true のとき FAB `create_new_folder_expressive`、false のとき `create_new_folder` を使う（`hul` 489 行付近）。→ **実行時に「どちらの FAB が使われているか」でフラグを推し量り、picker と同じ部品を選べる**。メディアブラウザのタブ（`izw.Q`）も**同じ** `hrn.nk`（`hsg` の `new izw(...)`、ファイルの︙の `izo.a` もこの値で分かれる）。 **確定**
- **フラグが false の端末では、下端のボタンは 1 個だけ**（`framed_single_button_view`）なので「クリア」は下端に置けない（︙の予備に回す）。`button_section`（2 個）を使うには picker の layout から取り出すしかない。
- 差し込む場所:
  - ストレージブラウザ: fragment `htc`（view は `advanced_browsing_browser_fragment_v2`）。目印: `R.layout.advanced_browsing_browser_fragment_v2` を inflate するメソッド、または activity 側 `AdvancedBrowsingActivity`。重なり: FAB（`end|bottom`）と、一覧（`content_list`、`clipToPadding=false`）の下端。バーを出すときは一覧の下 padding を足し、FAB を上げる（picker は一覧に `paddingBottom=120dp` を付けている）。
  - メディアブラウザ: fragment `iwm`（view は `tabbed_file_browser_fragment_v3`、根 `main_coordinator`、中に `ViewPager2 view_pager`）。各タブの一覧は子 fragment `izi` の `file_list`。下 padding は各タブの一覧に要る。
- 予備（置けないとき）: 上の︙（§4）に項目。

---

## 3. メディアブラウザをカテゴリとタブを指定して開く

**結論**: `FileBrowserRegularActivity` に `jcp` を渡す。**タブは `jcp.c` にタブの表示名（大文字小文字を無視して一致）を入れると、最初の表示でそのタブが選ばれる。** フックは不要。 **確定**（fallback で確認）

- `jcp` の記述子 `\u0004\u0004\u0000\u0001\u0001\u0005\u0004\u0000\u0001\u0000` + `\u0001ဈ\u0000 \u0003᠌\u0001 \u0004᠌\u0002 \u0005\u001a`:
| 番号 | wire | Java | 意味 |
|---|---|---|---|
| 1 | LEN（string） | `c` | 最初に選ぶタブの名前（例: `lid` は最近のカテゴリで `"Compressed files"`、`hvl` は `"Scanned"`） |
| 3 | VARINT（enum `isd`） | `d` | カテゴリ: 2 DOWNLOAD / 3 IMAGE / 4 VIDEO / 5 AUDIO / 6 DOCUMENT / 7 APP / 15 DOCUMENT_ONLY / 16 RECENTS … |
| 4 | VARINT（enum `jcq`） | `e` | 入口: 1 = ENTRY_POINT_CATEGORY_BROWSE（ホームのカテゴリ） |
| 5 | LEN（repeated string） | `f` | ドロップされたファイル（`iul` イベントで選択などに使う）。通常は空 |
（フィールド 2 は無い）
- ホームのカテゴリの押下（`ijw` case 3、`iko`）: `jcp { 3: カテゴリ, 4: 1 }` を `uea.i` で起動（フラグなし）。 **確定**
- タブの選び方（`jad.b`、fallback で確認）: 初回（`selected_tab_index` が保存されていない）で `jae.r`（= `jcp.c`）が null でなければ、タブ一覧（`List<kqe>`）を先頭から見て `itl.a(ctx, kqe)`（`kqe` のフィールド 7 `i`（文字列リソース）があればその文字列、無ければフィールド 1 `c`）と `jcp.c` を ASCII で大文字小文字を無視して比べ、最初に一致したタブ。無ければ 0 番（「すべて」）。
- 注意:
  - フォルダのタブの名前はフォルダ名なので、**同じ名前のフォルダが別の場所にあると先に出た方が選ばれる**（パスでは選べない）。確実にするなら、開いたあとに `jad.b` の後で `ViewPager2.e(index)` を呼ぶフック（タブの `kqe.d` のクエリにパスが入っているかで探す）が要る。 **推定**
  - FilesPlus のフォルダのタブ（§5.7）の名前の付け方次第。タブの一覧は非同期で届くので、タブが届く前に別のタブを選ぶことはない。
  - `jcp` が無くても落ちない（`iwj` は `jcp.a` を既定に使う）。
- 「すべて」タブのラベルは文字列リソース（`kqe.i`）なので、`jcp.c` を空にしておけば 0 番 = 「すべて」。

---

## 4. メニューの継ぎ目と選択の取り方

**前提（重要）**: ファイルの︙は phenotype フラグ `45676054`（§2 と同じ `lrv`）で**2 通り**に分かれる。false = `PopupMenu` に XML のメニューを inflate、true = `List<kji>`（`kji(String 表示名, Runnable 動作, boolean 有効, Integer アイコン)`）を返して元アプリ独自のシートで出す（`iqq` の 2 つのメソッド `a(PopupMenu, Object)` / `b(Object)`）。端末のフラグの値は静的には分からないので、**両方に差し込む**。 **確定**

### 4.1 メディアブラウザの選択モード（`tabbed_fragment_regular_selection_mode_menu_v2`）
- 既に使っている継ぎ目（`MoveToCurrentTabPatch`）: 持ち主 `ixh`（`tabbed_fragment_menu_v2` を inflate するクラス）、ActionMode の持ち主 `ipm`（文字列 `"setActionModeTitle should be called when action exists"` ほか）、選択が変わるたびに呼ばれるメソッドの後で `onSelectionMenu(View, Menu, List, List, Runnable)`。選択は MediaStore の id（`Long`）のリスト → パスは MediaStore で引く（`TabMove.pathsOf`）。**そのまま「切り取り」に使える。** **確定**（既存パッチ）
- 項目のクリックは `MenuItem.setOnMenuItemClickListener` で受ける（`MenuItemImpl.invoke` はリスナーを先に見る。既存の FolderTabContext と同じ）。

### 4.2 ストレージブラウザの選択モード（`advanced_browsing_action_menu_v2`）
- 選択モードは同じ `ipm`（ActionMode）。開始は `hul.t()`（`ipm.g(R.menu.advanced_browsing_action_menu_v2, ixd)`）、選択が変わるたびに `hul.U()` が `ipm.e(R.id.xxx, 条件)` で項目の表示を更新する（fingerprint: `R.id.move_to_action`・`R.id.copy_to_action` などの id を順に `ipm` の `(int, 条件)` メソッドに渡すメソッド）。**`hul.U()` の後**に、ActionMode のメニュー（`ipm` の ActionMode の `getMenu()`、既存パッチと同じ取り方）と選択を拡張へ渡す。 **確定**（構造）／差し込み **推定**
- ほかに、選択が **1 件で ActionMode が無い**とき（`htc.af`、fallback で確認: `hwd.d() == 1 && !ipm.f()`。どの操作でこの状態になるかは未確認。キーボード・マウス操作などと推定）は、ツールバーの options メニューに `advanced_browsing_action_menu_v2` を inflate する。念のため `htc.af(Menu, MenuInflater)` の後でも足す。
- **選択の取り方**: 選択モデル `hwd`（`hul.k`）。`hwd.e()` が proto `lls` を返すので、それを `ProtoParsers$InternalDontUse(null, lls)` でバイト列にして拡張へ渡せば、難読化名に触れずに読める。 **確定**（記述子）
  - `lls`: oneof（1 = `llp`「フォルダの全部（除外つき）」、2 = `llr`「個別」）。`llr { 1: repeated llq }`、`llp { 1: kqg 親, 2: repeated llq 除外 }`、`llq`: oneof（1 = `kqi` ファイル、2 = `kqg` フォルダ）。
  - `kqi` の主なフィールド: **1 = 絶対パス**（ローカルのときだけ。provider の中では空）、2 = 名前、4 = サイズ、5 = 更新日時（ms）、6 = MIME、7 = `kql`、**9 = URI**（`file://…` か `content://…`）、13・18 = MediaStore の id（推定）。フォルダは `kqg` のフィールド 2（e）。
  - 100 件を超える「すべて選択」は `llp`（親フォルダ＋除外）になる（`hul.aq`）。このときは親フォルダを自前で列挙して除外を引く。

### 4.3 ファイルの︙
| 画面 | PopupMenu（フラグ false） | `List<kji>`（フラグ true） |
|---|---|---|
| ストレージブラウザ・ファイル | `hul.x(MenuInflater, Menu, kqi)`（`advanced_browsing_file_dropdown_menu`。ゴミ箱のファイルは `advanced_browsing_trashed_file_dropdown_menu`）。クリック → `hul.aa(MenuItem, kqi)` | `htp.b(Object)` の case 1（`kqi`）。fingerprint: `R.string.file_browser_menu_item_open_with` と `R.string.file_browser_backup_to_google_drive` を使い `List` を返すメソッド |
| ストレージブラウザ・フォルダ | `hul.w(MenuInflater, Menu)`（`advanced_browsing_folder_dropdown_menu`）。クリック → `hul.Z(MenuItem, kqg)` | `htp.b` の case 0（`kqg`。`R.string.file_browser_menu_item_folder_info`） |
| メディアブラウザ | `izw.d(MenuInflater, Menu, kqi)`（`file_browser_list_item_popup_menu`、APK・アプリは別のメニュー）。クリック → `ixy.onMenuItemClick` | `izo.b(Object)`（`R.string.menu_item_move_to`・`R.string.menu_item_copy_to` を使う） |
- 長押しのコンテキストメニュー（`htc.onCreateContextMenu` / `izi`）も同じ `x`/`w`/`d` を呼ぶ。
- PopupMenu 側: `x`/`w`/`d` の最後で項目を足し、`setOnMenuItemClickListener` で受ける（対象の `kqi`/`kqg` は引数にある）。`List<kji>` 側: 返す前の `ssa`（list builder）に `new kji(label, runnable)` を足す（`kji` のコンストラクタは同じメソッドの中の `new-instance` から取れる）。Runnable は拡張のもの。
- 対象のパス: `kqi` のフィールド 1／URI はフィールド 9（4.2 と同じくバイト列で渡す）。

### 4.4 プレビューの︙
- 既に使っている継ぎ目（`PreviewMultiSelectPatch`）: `file_preview_menu` を inflate する `(Menu, MenuInflater)` メソッド（`jju`）→ `PreviewHooks.onMenuCreated(View, Menu, long mediaId, boolean, Object)`、クリック → `onMenuItem`。§5.2 の選択もここで持っている。**「切り取り」はここに足せる。** 画像・動画・音声・PDF のページにも `image_preview_menu` など（`jld`・`jpl`・`jie`・`jod`）があるが、ファイル操作の項目は `file_preview_menu` 側。 **確定**（既存パッチ）
- 注意: プレビューの項目の出し分けは `jmp.e`（`ise`）の旗による（例: `ise.m` = お気に入り）。ストレージブラウザから SMB のファイルを開いたときも削除などは出る（§6）。

### 4.5 上の︙（予備・表示の切り替え）
- ストレージブラウザ: `htc.af` の `advanced_browsing_top_menu_v2`（`view_mode_switch`・`select_all_action`・`sort`・`new_folder_action`・`storage_settings`）の後で足す。
- メディアブラウザ: `ixh` の `tabbed_fragment_menu_v2` の後（既存パッチ `DeleteFolderFromTabsPatch` と同じ場所）。

---

## 5. 設定 `lna.ew()` への差し込み

**結論**: Preference のクリックは **リスナー（フィールド `o`、型 `fqx`）しか効かない**。Intent でのクリックは R8 で消えている。項目の追加は、`ew()` の中の命令から Preference のメソッドを読み取って同じ呼び出しを出せる。 **確定**

- `ew()` で使われているメソッド（`androidx.preference.Preference` は難読化されていないクラス名、メソッド名は R8 で変わっている）:
| 呼び出し | 元の意味 | 根拠 |
|---|---|---|
| `new Preference(Context, null)` / `new PreferenceCategory(Context, null)` / `new MaterialSwitchPreference(Context)` | コンストラクタ | — |
| `T()` | `setPersistent(false)`（`this.w = false`） | `Preference.T` |
| `E(String)` | `setKey` | `this.s = str` |
| `I(CharSequence)` | `setTitle` | `this.r` |
| `n(CharSequence)` / `G(int)` | `setSummary`（文字列 / リソース） | `this.a`、`G` は `n(getString(i))` |
| `D(int)` | `setIcon(resId)` | `cpa.co(context, i)` |
| `J(boolean)` | **不明**（`this.x` を変えて親の adapter に知らせる。`x` は属性 25 番、既定 true）。元はカテゴリと言語の項目に `J(false)` | 不明 |
| フィールド `y` | `iconSpaceReserved`（属性 24 番、既定 false。bind でアイコンが無いとき INVISIBLE か GONE かを決める）。元は `if (preference.y) D(アイコン)` | 確定 |
| フィールド `o`（型 `fqx`: `void a(Preference)`） | `OnPreferenceClickListener`。元は `new shh(shi, "… clicked", new lij(...))` で包む | 確定 |
| フィールド `n`（型 `fqw`: `void a(Preference, Object)`） | `OnPreferenceChangeListener` | 確定 |
| `PreferenceGroup.W(Preference)` | `addPreference` | — |
| `MaterialSwitchPreference.k(boolean)` | `setChecked` | — |
| `lna.p(PreferenceScreen)`（`frg` の） | `setPreferenceScreen`。最後に呼ぶ | — |
- **足し方の決まり（Task 3 向け）**: 意味の分からないメソッドがあるので、元の項目と**同じ呼び出しの並びをそのまま写す**。カテゴリは `new PreferenceCategory(ctx, null)` → `T()` → `E(key)` → `I(title)` → `J(false)` → `screen.W(cat)`。項目は `new Preference(ctx, null)` → `T()` → `E(key)` → `I(title)` → `n(summary)` → `if (y) D(icon)` → `o = listener` → `cat.W(pref)`。スイッチは `new MaterialSwitchPreference(ctx)` → … → `k(checked)` → `n = 変更リスナー`。パッチは `ew()` の命令からこれらのメソッドの参照を読む（`"ABOUT_FILES_PREF_KEY"`・`"OTHER_PREF_CATEGORY_KEY"` の `const-string` の後の `invoke-virtual` の並び）。
- 最後の `ABOUT_FILES_PREF_KEY` の後に、キーなしの空の `Preference`（下の余白）を `OTHER_PREF_CATEGORY_KEY` に足している。FilesPlus のカテゴリは `p(screen)` の直前、つまりこの余白の後に足すことになる（余白の前に入れたいなら、余白の `W` の前に差し込む）。
- **クリック（performClick = `Preference.A()`）**: 有効なら `c()` → `o != null` なら `o.a(this)` して終わり → 無ければ `PreferenceManager` の `frn.ey(this)`（`frg.ey`: `preference.t`（fragment のクラス名）があれば fragment を開く）。**`Intent` のフィールドも分岐も無い**（R8 で消えた）。→ Intent での遷移は不可。fragment の遷移は自前の Fragment（難読化された `av` の子）が要るので不可。
- **クリックの付け方**: `fqx` は 1 メソッドのインターフェースなので、拡張で `java.lang.reflect.Proxy` を作り、`Preference` のフィールドのうち「型が interface で、そのメソッドが `(Preference)` 1 引数」のもの（= `o`）に入れる（名前を使わずに型で探せる）。または、パッチで `fqx` を実装したブリッジのクラスを生成する。 **推定**（方法）
- **タイトル**: `SettingsActivity.onCreate`（難読化されていないクラス）が `setContentView(R.layout.activity_settings_v2)` → fragment `lna` を置く → `m(Toolbar)` → **`setTitle(R.string.settings_screen_title)`**（`Activity.setTitle(I)V`）。この呼び出しの直後に、目印の extra を見て `setTitle(...)` し直すフックを入れればよい。 **確定**
- **サブページ**: `SettingsActivity` は `activity_params` を読まない（peer `lmx`）。目印の extra を付けて自前の Intent で開き直せる（同じアプリの中なので exported は関係ない）。 **推定**
- **文字入力のダイアログ**:
  - 新しいフォルダのダイアログは、共通のダイアログ fragment `kkr`（`"NEW_FOLDER_DIALOG"`）を proto `kkx`（タイトル・ボタン・中身の layout `R.layout.new_folder_content`・種類 `kky.NEW_FOLDER_DIALOG`）で開き、種類ごとの処理 `kkq`（ここでは `hvb`）を **Dagger のマルチバインディングで注入**している。→ **自前の種類は足せない**（注入された peer が要る）。 **確定**（構造）
  - 使えるのは部品: layout `new_folder_content`（`TextInputLayout` id `edit_text_input_layout`＋`TextInputEditText` id `folder_name_edit_text`、`Widget.GoogleMaterial.TextInputLayout.OutlinedBox.Dense`）と、ダイアログの builder `qrk`（Material の `MaterialAlertDialogBuilder`。`kkr` は `new qrk(context, 0)` または `R.style.FilesCenteredDialog`）。`qrk` は普通のクラス（Hilt/peer は不要）なので、パッチで「`qrk` を作り、タイトル・`setView`・ボタンを付けて `show`」するブリッジを生成すれば、元と同じ見た目のダイアログで文字入力ができる。`qrk` の親（appcompat の `AlertDialog.Builder`、難読化 `ef`）のメソッドは `kkr` の中の呼び出しから取る。 **推定**
  - 診断ログの表示も同じブリッジ（`setView` に TextView）で出せる。

---

## 6. SMB をストレージブラウザで（§5.12.4 の 1〜4）

**結論**: 元のコードの範囲では、`kql` = UNKNOWN の tree URI でブラウザを開いて一覧を出すまでに**落ちる所は見つからない**。ただし provider 側に条件があり、**削除・名前の変更・移動・コピー・新しいフォルダ・ゴミ箱へは、UNKNOWN でも多くが出たまま**なので、隠すフックが要る。 **推定**（経路は確定、全体の動作は推定）

### 6.1 UNKNOWN で落ちないか（§5.12.4 の 1）
- `AdvancedBrowsingActivity.onCreate` のログ: `iaj.O(kql)` は UNKNOWN → 1 を返す（例外は ordinal が 0〜4 以外のときだけ）。`lty.l(kql, …)` も `iaj.O` だけ。 **確定**
- `lsd.c(kql)` は UNKNOWN で `IllegalArgumentException` を投げるが、呼ぶのは `ijq`・`iwm`・`jju` で、引数は定数の `SD_CARD`/`USB` だけ。ブラウザからは呼ばれない。 **確定**
- `hul` のコンストラクタ: `E`・`A` = 種類が INTERNAL か SD_CARD のときだけ true（UNKNOWN では false）。 **確定**
- `htc.M`: タイトル = `kqg` の表示名（case 1 の文字列）、`lwf.c(qdv.INTERNAL)` などは定数。`storage_settings` は SD 以外で隠れる。 **確定**
- 開いた直後の存在確認（§0 の 2）: `qgc.b(Uri.parse(kqg.f))` が「有る」を返さないと閉じる。tree URI のとき `qgc.b` は `qhg.l(uri)`（自分のアプリの `DOCUMENTS_PROVIDER` の provider なら true）→ `isDocumentUri` が false で `isTreeUri` が true → `qhj.e`（`buildDocumentUriUsingTree(uri, getTreeDocumentId(uri))`）→ `qei` → `o()` = `qhj.n()`（document の `document_id` を query して **ちょうど 1 行**なら有る）。→ **provider は tree のルートの document の `queryDocument` に 1 行で答えること。** 接続できないとき 0 行や例外だと、開いた瞬間に閉じる（例外は `qgc.b` が握るので落ちはしない）。 **確定**
- 自分のアプリの provider なので、`MANAGE_DOCUMENTS` や URI の許可は要らない（同じ UID）。 **推定**

### 6.2 子の問い合わせ（§5.12.4 の 2）
- 一覧: `lsd.e` → `qgc.b(Uri.parse(今の kqg.e))` → `qei.t`（`L`）→ `qhj.g()` = **`DocumentsContract.buildChildDocumentsUriUsingTree(c, getDocumentId(c))` に projection `{_display_name, mime_type, last_modified, _size, flags, document_id}`** で query（= provider の `queryChildDocuments`）。子の URI は `buildDocumentUriUsingTree(c, document_id)`。`mime_type` が `vnd.android.document/directory` ならフォルダ（`qei`）、それ以外はファイル（`qef`）。MIME が空なら拡張子から補う。 **確定**
- 一覧から作る `kqg`（フォルダ）: `{1: 名前, 2: 子の document URI, 3: 一覧を作った親の URI, 10: 親の種類（UNKNOWN）, 12: 更新日時}`。ファイルの `kqi`: パス（1）は空（`qej.e()` は externalstorage の provider でないと null）、URI（9）は `content://…`。 **確定**
- 空のフォルダのときだけ、隠しファイルの有無を `qei.q`（子を 1 段だけ列挙）で調べる。 **確定**
- **document id の形**: `qhj` は子や兄弟の URI を「document id の `%2F` / `%3A`」を前提に組み立てる（`qhj.f`・`t`・`r`・`u`。`u` = id の `:` が末尾ならルート）。読み取りの一覧には使われないが、新しいフォルダ・名前の変更・`.nomedia` などで使う。**SMB の provider の document id は externalstorage と同じ形（`<root>:` がルート、`<root>:dir/sub/file`）にしておくのが安全。** **推定**

### 6.3 出るメニューと、何で出し分けているか（§5.12.4 の 3）
出し分けは**ストレージの種類（`hul.E`/`hul.A`）**と選択の数で決まり、**document の `flags`（`FLAG_SUPPORTS_DELETE` など）は見ていない**（`qhj` は flags を読むが、一覧・メニューの判断には使っていない。名前の変更の前の権限確認 `qhj.l` だけが見る）。 **確定**
| 場所 | UNKNOWN（SMB）で出るもの | 隠れるもの |
|---|---|---|
| 選択モード（ActionMode、`hul.U`） | 共有、ニアバイシェア（条件次第）、アプリで開く（1 件）、情報、**名前の変更、移動、コピー、圧縮**、Drive へバックアップ、**削除**（`delete_action` は XML の既定で表示。`E` のときに付ける条件 `htf` case 7 は `setShowAsAction` だけで、表示は種類にかかわらず変えない） | ゴミ箱へ・元に戻す・セーフフォルダ・お気に入り（`E`/`A` が条件） |
| 選択 1 件のときの options メニュー（`htc.af`） | 上と同じ。ただし `delete_action` は `setVisible(E)` で**隠れる** | 同上 |
| ファイルの︙（PopupMenu、`hul.x`） | 選択・共有・アプリで開く・**移動・コピー・名前の変更**・圧縮・**削除**・バックアップ・情報 | ゴミ箱へ・セーフフォルダ・お気に入り |
| ファイルの︙（`List<kji>`、`htp.b`） | 共有・アプリで開く・名前の変更/移動/コピー/圧縮（`hul.e`〜`i`）・削除（`g`）・バックアップ・情報 | ゴミ箱へ・お気に入り・セーフフォルダ |
| フォルダの︙ | 選択・**移動・コピー・名前の変更・削除**・情報 | — |
| 上の︙ | 表示・すべて選択・並べ替え・**新しいフォルダ** | ストレージの設定 |
| FAB（新しいフォルダ） | **出る**（`hul.K` は種類で分けない） | — |
- → **隠すフックが要る**: 4.2・4.3・4.5 の継ぎ目で、今のブラウザの `kqg.e` が SMB の authority のとき、`delete_action`・`rename_action`・`move_to_action`・`copy_to_action`・`compress_action`・`new_folder_action`・`move_to_trash_action`（念のため）を隠し、`List<kji>` からも外し、FAB を隠す。provider 側も書き込み（`createDocument`・`deleteDocument`・`renameDocument`・`openDocument` の "w"）を例外で拒否する（二重の守り）。 **推定**

### 6.4 ファイルを押したとき（§5.12.4 の 4）
- `hum`（クリック）: `julVar.f(kqi)` で元のプレビューの対象なら `FilePreviewActivity` を `jmp { 1: isf{1: 今のフォルダの kqg, 2: 並べ順}, 2: 位置, 3: ise, 4: isg ADVANCED_BROWSER }` で開く。UNKNOWN のときは `ise.m = false`（お気に入りを出さない）にした `jmo.b` を使う。対象外の種類は「アプリで開く」（`juw.i`、ACTION_VIEW に content URI）。 **確定**
- プレビューの中の一覧は `isf.c`（フォルダの `kqg`）から作り直すので、同じ container の層（`qgc.b` → `qei`）を通る見込み。再生は ExoPlayer の `ContentDataSource` 相当（`ecb`: content なら `openTypedAssetFileDescriptor(uri, "*/*", {ACCEPT_ORIGINAL_MEDIA_FORMAT})`、他は `openAssetFileDescriptor(uri, "r")`）→ **provider の `openDocument` が seek できる FD（`StorageManager.openProxyFileDescriptor`）を返せば再生・シークできる見込み**。 **推定**（プレビュー側の一覧の作り直しは未確認）
- プレビューの︙では削除・移動などが出る（`ise` の旗次第）→ 4.4 の継ぎ目で SMB のときは隠す。 **推定**
- 一覧のサムネイル: Glide に `kjm.a` = `juk.j(kqi)`（画像・動画・音声は `kqi` のフィールド 9 の URI）を渡す。content URI では Glide は `openAssetFileDescriptor`（動画は `MediaMetadataRetriever` でフレームを取る）で**ファイル本体を読む**。provider の `openDocumentThumbnail` は呼ばれない（`DocumentsContract.getDocumentThumbnail`・`EXTRA_SIZE` を使う箇所が無い）。→ SMB では動画のサムネイルのたびに本体を読む。§5.3.1 のキャッシュを使うなら §9.2 の継ぎ目で URI を差し替える。 **推定**

---

## 7. ホームの「すべてのストレージ」（`root_container_list_v2_fragment`）

**結論**: 行のデータは proto `isl`。一覧はセクション 2 つの adapter（`rql(adapter, 2)`）で、セクション 0 = ストレージの行、セクション 1 = 「その他のストレージ」。 **確定**

- 記述子（確定）:
  - `isl`（`\u0004\u0002\u0001\u0000\u0001\u0002…` + `\u0001<\u0000\u0002<\u0000`）: **oneof**。フィールド 1 = `isb`（ストレージの行）、フィールド 2 = `isk`（「その他のストレージ」）。
  - `isb`（`…\u0001ဉ\u0000\u0002ဇ\u0001`）: `{ 1: LEN kqg, 2: VARINT bool d }`。d は内部（`ikr` case 15）と SD（`rjn`）で true、adoptable（`ikr` case 19）で false、最初の placeholder では未設定。**d を読む箇所は見つからなかった**（行の bind `iou.b` も使っていない）。true にしておけば内部・SD と同じ。
  - `isk`: `{ 1: LEN string }`（「その他のストレージ」の表示名）。
- fragment `iop`（`R.layout.root_container_list_v2_fragment` を inflate、`content_list` に `StaggeredGridLayoutManager`）、peer `ioy`、行の view `BrowseCapsuleItemView`（`R.layout.browse_capsule_item_view`、難読化されていない）、bind は `iou.b`。
- データ: `lsd.i(int)`（`"StorageDataService_getAvailableStorageVolumesAndExternalContainers"`）→ 内部（`ikr` case 15）＋ SD（`rjn`）＋ USB ＋ adoptable（`ikr` case 19）を `jwb` case 3・4 で `List<isl>` にまとめる → `iox.b(List)`（`"Cannot load root containers"`）→ `h.a(0).b(list)`。最初は「placeholder」の内部ストレージの行（e = `"placeholder"`、押しても何もしない）が入っている（`iop.M`）。
- **SMB の行のバイト列（型紙）**:
```
kqgSmb = { 1: "<接続名>", 2: "<tree URI>", 3: "<tree URI>", 10: 2 }   // 7・8・11・12 は付けない
isb    = { 1: kqgSmb, 2: 1 }
isl    = { 1: isb }
```
- **行を足す継ぎ目**（推奨）: `iox.b(Object)` の `h.a(0).b(list)` の直前で、拡張が list に `isl` を足す。fingerprint: 文字列 `"Cannot load root containers"`（同じクラスの `a(Throwable)`）。`isl` は拡張からバイト列で作り、`ProtoParsers$InternalDontUse(bytes, null)` の 2 引数のメソッドで、list の要素のクラスの既定インスタンス（その型の static final フィールド）を使って戻す（リフレクション）。list には必ず内部ストレージの行があるので型は取れる。 **推定**
- **SMB の行が押せるか**: `iou.b` は `kql` が 0/1/2（INTERNAL / SD / **UNKNOWN**）のとき `onClickRootContainer`（`iot`）を付ける → `ioy.c(kqg)` → `hus{hut{1: kqg, 2: [kqg], 4: 1, 5: 2}}` でブラウザ。→ **上の型紙の行は、元の押下の処理でそのままブラウザが開く**（e が `"placeholder"` でないこと）。表示名は oneof case 1 の文字列（`iou.b` は case 7 ならリソース、case 1 なら文字列）。アイコンは UNKNOWN = `quantum_gm_ic_phone_android`（電話のアイコン）、view id `storage_location_unknown_item_view`。副題はフィールド 11 を付けなければ出ない。 **確定**（bind と押下の経路）
- 差分（`iov.b`）は `kqg.e` の文字列で比べる → e を接続ごとに変える。
- 行の︙は ADOPTABLE（4）だけ。SMB の行に︙（編集・削除）を出す仕組みは元には無い（§5.12.4 どおり Mod 設定で編集）。

---

## 8. ゴミ箱（`TrashActivity`）

**結論**: 引数は `lyp`（空でよい）。画面はタブ（`List<lwr>`）＋各タブがメディアブラウザと同じタブ fragment（`izi`、カテゴリ TRASH）。行はファイル（`kqi`、MediaStore の is_trashed=1 のもの）。元の期限は **30 日**（固定値）。 **確定**（構造）

- `TrashActivity.onCreate` → `R.layout.activity_trash` に fragment `lyz`（`R.layout.trash_fragment_v3`）、peer `lzn`。下のボタン: `action_button`（削除）、`secondary_action_button`（元に戻す）、`single_action_button`（「ゴミ箱をすべて削除」）。
- `lyp`（`…\u0001᠌\u0000`）: `{ 1: VARINT enum c }`。外部からは c = 1（`kbs`）、アプリ内（`lid`）は空。**空でも extra 自体は必須**（無いと `lyq` で NPE）。
- タブ: `lws`/`lwy` のデータ → `lzk.b(List<lwr>)`（文字列 `"Unable to load trash collection info"`、`"Failed to refresh trash page."`）。`lwr { 1: kqe（そのタブのクエリ）, 10: enum }`。各タブの fragment は `lzc.z(i)` が `izi.aO(isc{ c: kqe, k: CATEGORY_TRASH(13), m: TRASH_FOLDER_BROWSER, g: R.layout.trash_folder_empty_view, … })` で作る。
- 行: タブの一覧はページングされた list（`lbe`/`lbm`）で、ファイルの行は `itm`（中に `kqi`）。
- ゴミ箱の実体: Android 11 以上は MediaStore の `is_trashed = 1`（`ksm.h`）。ファイル名は `.trashed-<期限の秒>-<名前>`（`ksm.b` の正規表現）、残り日数は `ksm.a`。Android 10 以下は自前の `.FilesByGoogleTrash/.index.db`（`ksm.e`）。
- 期限: 文言 `trash_action_subtitle`・`no_trash_files` に **30** を固定で渡している（`lyt`・`lzt`）。実際の削除は OS（MediaStore）の期限。→ §10.6 の既定は 30 日。
- **フォルダの行を足すのは重い**: 行の list は MediaStore のクエリで作るページングの list で、行の型は `kqi` のファイル。フォルダの行の型が無い。現実的な案は (a) `lzk.b` の `List<lwr>` に FilesPlus のタブを 1 つ足し、その中身を別の経路で出す、(b) ゴミ箱の画面の上（`the_all_label` の辺り）に元の部品で入口を置き、押したらストレージブラウザで `.FilesPlus` のゴミ箱を開く。**§5.12.5 の「フォルダの行を 1 行ずつ足す」はこのままでは作れない**ので、P7 の前に設計を見直す。 **推定**

---

## 9. ダウンロードのチップ・動画のサムネイル・プレビューの長押し

### 9.1 チップ（`file_filter_*`）
- **ダウンロードにはチップは無い。** チップの入れ物 `file_filter_container`（`HorizontalScrollView`、既定は GONE）＋ `file_filter_chip_group` は**どのタブの layout（`tab_fragment`）にも入っている**が、中身を入れるのは「アプリ」カテゴリの「ユーザーが入れたアプリ」タブ（`itl.d`）だけで、しかも使用状況へのアクセスが許可されているとき（`izi.M` の `"android:get_usage_stats"`）。チップは `R.layout.file_filter_item`（`Chip`）、項目は `krj`（大きいアプリ・ゲーム・使っていないアプリ）、絞り込みは `kri`/`krh`（アプリ用の enum `kqh.e`）。 **確定**
- 継ぎ目: `izi.M`（タブの fragment の onCreateView。fingerprint: `R.layout.tab_fragment` と文字列 `"OnFileFilterClick"`・`"android:get_usage_stats"`）の後で、カテゴリがダウンロード（`isc.k` = 2）のとき、`file_filter_container` を見せて `file_filter_item` の Chip を 1 個（「圧縮ファイル」）足す。**部品は元のもの**。
- 絞り込み自体は元の仕組み（`kri`）が使えない（アプリ専用）。タブのクエリ（`kqe` のフィールド 2 `d` = エンコードされた `lrp` のクエリ）に条件を AND する方法が元にある（`iwz.i(kqe, qbi)`: `qbi.h(2, 元の条件, 足す条件)`）ので、チップの on/off でタブのクエリを差し替えて読み直すのが筋。ただし `qbi`（条件の木）を拡張から作るのは難しい。代案: ページングされた一覧の結果（`lbe`）から拡張子で落とす。**どちらも P5 の前にもう一度調べる。** **推定**
- 参考: 「最近」カテゴリの `"Compressed files"` タブ（`lid`）は、元アプリが圧縮で作った ZIP の置き場（`Files by Google/Compressed files`）であって、圧縮ファイルの絞り込みではない。

### 9.2 動画のサムネイル
- 一覧のサムネイルは **Glide**。データは `kjm`（`ThumbnailData(uri, placeholderIcon, imageSignature, disableDiskCache, downsampleStrategy, …)`、`toString` の文字列が残っている）。URI は `juk.j(kqi)`（MediaStore 系は `kqi` のフィールド 22 か 9）、signature は `"<サイズ>/<更新日時>"`（`bcd`）。読み込みの組み立ては `gwp`（`kjm.d ? gzr.c : gzr.e` = DiskCacheStrategy、`hij(kjm.c)` = signature）・`kja`。 **確定**
- 動画のフレームは Glide の VideoDecoder 相当（`hfi`/`hff`/`hfg` = `MediaMetadataRetriever` の初期化、`hew` case 5 = 埋め込み画像）。MediaStore の `loadThumbnail`・`createVideoThumbnail` は**使っていない**（呼び出しが無い）。 **確定**（呼び出しの有無）／Glide の VideoDecoder との対応は **推定**
- ディスクキャッシュ: Glide 既定の `cacheDir/image_manager_disk_cache`（`lar`）、**上限 250MB**（`gwe` の `262144000`）。`disableDiskCache` が立つ画面では DATA 戦略（ローカルの URI ではほぼキャッシュされない）。キャッシュは cacheDir にあるので、ColorOS の掃除で消えうる。 **推定**
- 差し込み先: `gwp` の Glide リクエストを組む所（`kjm.a` の URI を、保存済みのサムネイルのファイルがあればその `file://` に替える）。キーはパス・サイズ・更新日時（`kjm.c` がそのまま使える）。 **推定**
- 別物: `jqg`/`jqi`（`"VideoFramesRetrieverImpl_getFrameAtTime"`）はプレビューのシークバーのコマ用。

### 9.3 プレビューの長押し（ドラッグの開始）
- 各ページの長押しがイベント `ivh`（中身の無いイベント）を投げる: 画像 `jlk`（`View.OnLongClickListener`）、動画 `jps`・音声 `jip`（`GestureDetector.onLongPress`）。プレビューの peer が `ivh` を受けて（`jku` case 9。登録は `iad` 5259 行の `smb.I(fragment, ivh.class, new jku(jkt, 9))`）、セーフフォルダ以外なら `juq.j(...)` でドラッグを始める。 **確定**
- `juq.j` は一覧からのドラッグ（`huc`・`iwc`）でも使うので、そこを替えると一覧のドラッグも変わる。**替えるのはプレビューの `ivh` の処理（`jku` case 9）だけ**にする。fingerprint: `juq.j`（文字列 `"drag and drop logging"`・`"files-source"`・`"parent-folder-uri"`）を呼ぶメソッドのうち、プレビューのもの（同じメソッドの中で `isg.SAFE_FOLDER_BROWSER` を比べ、`jmp` を読む）。その分岐の先頭で拡張の「選択の切り替え」を呼び、true なら元の処理を飛ばす。 **推定**（差し込み方）

---

## 10. 元アプリの文字列（切り取り・貼り付け・元に戻す）

**結論**: **「切り取り」「貼り付け」は元アプリに無い。「元に戻す」はある。** **確定**
- 英語（base の `values/strings.xml`）: `undo_action` = "Undo"、`annotation_undo_button_content_desc` = "Undo"。cut / paste を名前・値に含む文字列は無い（`clipboard_label` = "Content copied from PDF" は PDF ビューア用）。`copy` = "Copy"、`menu_item_copy_to` = "Copy to"、`move_here` = "Move here"、`copy_here` = "Copy here"。
- 日本語: 日本語は分割 APK `split_config.ja.apk` にある（`analysis/apktool` には入っていない。スクラッチパッドで apktool で展開して確認）。`undo_action` = 「元に戻す」、`copy` = 「コピー」、`menu_item_copy_to` = 「コピー」、`move_here` = 「ここに移動」、`copy_here` = 「ここにコピー」、`restore` = 「復元」、`new_folder_button_title` = 「新しいフォルダ」。切り取り・貼り付けは無い。
- 代わりに使えるもの: **Android フレームワークの公開文字列 `android.R.string.cut`（切り取り）・`android.R.string.paste`（貼り付け）・`android.R.string.copy`（コピー）**。端末の言語で OS が訳したものが出る（拡張から `context.getString(android.R.string.cut)` で使える）。「ここに貼り付け（n 件）」「クリップボードをクリア」などの組み合わせの文言は自前（日英）になる。

---

## まとめ: 設計に効くこと

1. **§5.12.1 を直す**: ブラウザは `hus{1: hut}`。extra は `Bundle{"protoparsers": …}`。`hut.f` の値は 1/2/3/4 = 閲覧/コピー/移動/保存。`hut.g` = 1 外部 / 2 ホーム。`kqg` のフィールド番号は 1/2/3/7/8/10/11/12。
2. **引数が悪いと起動直後に落ちる**（`rek.F` の null）。「開けなかったら元の画面で開き直す」は、起動の**前**にバイト列を検査する形にする（§5.12.10 の書き直し）。
3. **`kqg.f` が存在しないと、ブラウザは開いた瞬間に閉じる。** SMB では provider がルートの document に答えられないと閉じる（落ちはしない）。
4. **積み付きでストレージブラウザを開くには、onCreate のフックが要る**（§1.7 の案 A）。§5.12.3「メディア → ストレージ（フォルダのタブ）」と §5.12.5「ゴミ箱のフォルダを開く」に効く。
5. **メディアブラウザのタブ指定は `jcp.c` = タブの名前でできる**（フックなし）。同じ名前のフォルダは区別できない。
6. **SMB では削除・名前の変更・移動・コピー・新しいフォルダが出たまま**（種類で一部しか隠れず、document の flags は見ない）→ 隠すフックが必須。
7. **設定の項目のクリックはリスナーだけ**（Intent は不可）。文字入力は元の `qrk`（Material の builder）＋ `new_folder_content` をブリッジで使う。
8. **切り取り・貼り付けの文字列は元アプリに無い** → `android.R.string.cut` / `paste` を使う。
9. **ゴミ箱の一覧はファイルの行だけ**（MediaStore のゴミ箱、ページング）。フォルダの行を足すのは重い → §5.12.5 の形は P7 の前に見直しが要る。
10. 下端のボタン欄はどちらの画面にも無い。`framed_single_button_view` は独立した layout なので coordinator に足せる。`button_section` は picker の layout の中にしか無い。どちらを使うかは FAB の種類で判断できる。

## 確かめられなかったこと
- 端末の phenotype フラグ `45676054` の値（下端の部品・︙の形が変わる）。実行時に判断する前提。
- プレビューが `isf` のフォルダから一覧を作り直す経路の細部（SMB の content URI での再生は推定）。
- `kqg.f` が空のときに本当に閉じるか（ゲートウェイの入口 `kcn` が反例の疑い。§0 の 2）。
- 選択 1 件で ActionMode が無い状態（`htc.af` の分岐）がどの操作で起きるか。
- `Preference.J(boolean)` の元の意味。
- ダウンロードの絞り込みを、元のクエリ（`qbi`）で足すか結果で落とすか。
- ゴミ箱にフォルダの行を足す具体的な方法。
- `kqi` のフィールド 13・18 が MediaStore の id であること（`qbc.i` の読みからの推定）。
