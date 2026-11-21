# SAF 登録とライブラリ走査（Issue #35）

FR-SRC-02・NFR-SEC-01・NFR-REL-01 のうち、DD-SRC-01・DD-DB-03・IMP-02 に沿った登録、走査、消失判定を扱う。対象ケースと検証対象コミットを記録した。[Issue #35](https://github.com/4rtofl/Audipp/issues/35) · [設計](../specifications/03_detailed_design.md#dd-src-01-registerlocaltree) · [試験台帳](../traceability/results.json)

## 呼び出し契約

- `SafLibrary.openTreeIntent()` を起動し、成功時に Android が返した `Intent` を `registerLocalTree()` に渡す。受領結果に読み取り権限と永続化の両フラグがあるツリー URI を受理し、OS には読み取り権限だけを要求する。登録に失敗した場合は、その呼び出しで新規に取得した権限の解放を試みる。登録済みルートは DB の再オープン後も `registeredRoots()` で取得できる。[Android Developers の SAF ガイド](https://developer.android.com/training/data-storage/shared/documents-files)
- `startScan(rootId, scope)` は呼び出し元のスコープに属する走査を IO ディスパッチャーで実行する。`progress` は訪問件数、列挙を完了したディレクトリ数、追加・更新・スキップ・解析失敗件数を通知する。`cancel()` で消失確定処理の開始前まで取り消せる。`await()` は走査 ID、最終件数、状態と列挙型の問題コードを返す。
- `COMPLETE` は全ディレクトリの列挙と最後のルート確認が成功した状態を示す。解析に失敗した既存曲も訪問済みとし、既存の Variant とメタデータを保持する。以前 `MISSING` だった曲を列挙できた場合、解析失敗・スキップ時は `UNKNOWN`、解析成功時は `PLAYABLE` に戻す。
- `PARTIAL` は上限やディレクトリの列挙エラー等を示す。`CANCELLED` は取り消し、`ACCESS_LOST` は永続読み取り権限がない場合やルートにアクセスできない場合を示す。いずれも途中まで保存済みの行を保持し、未訪問曲の状態を維持する。再許可は同じフォルダを選び直し、同じルート ID で再走査する。
- 走査が確定すると、その状態（`COMPLETE`・`PARTIAL`・`ACCESS_LOST`）をルート行に保存する。`lastScanStatus(rootId)` は再起動後もこの値を返すので、画面は `ACCESS_LOST` のルートを再許可が必要なルートとして示せる。取り消した走査、確定前に停止した走査、新しい走査 ID に置き換えられた走査は値を変えない。再許可だけでは値を変えず、次に確定した走査で更新する。一度も確定していないルートは `null` を返す。
- ルート登録の解除（永続読み取り権限の解放と `local_scan_roots` の行の削除）は本変更に含めない。FR-SRC-02 の削除と NFR-SEC-01 の残りとして [#84](https://github.com/4rtofl/Audipp/issues/84) で扱う。

## Room と走査上限

Room v4 は v3 までの11表（v2 のキュー、v3 のプレイリストを含む）を維持し、`AutoMigration(3, 4)` で `local_scan_roots` と `local_scan_sources` を追加する。ルートと Source の ID、ツリー URI、走査 ID、最後に確定した走査の状態（未確定は NULL）は TEXT とする。Source ごとにルートと最後の訪問を関連付け、`(root_id, last_seen_scan_id)` に索引を張る。同一ルートの操作をプロセス内で直列化し、Room のトランザクション内で各訪問・消失確定を最新の走査 ID と照合する。実行中にプロセスが停止した後は、次回の走査が新しい ID を使う。消失は Source の状態更新で記録し、Variant の ID と参照先を保持する。[Room v4 の生成スキーマ](../../app/schemas/com.audipp.data.db.LibraryDatabase/4.json)

ルートと音源の ID は、選択されたツリー URI とその配下のドキュメント URI から決定する。同じ URI での再許可・再走査では同じ Source・Track・Variant を更新する。別ルートの重複登録や、提供元が URI を変更した移動・改名は別 ID となる。v3 以前の行は登録ルートにひも付かないまま移行で保持し、走査の対象には加えない。

深さ 32（ルートは深さ 0）、列挙行 50,000 件、走査中に保持する ID とディレクトリ情報の見積もり 16 MiB を暫定値とする。深さの上限を超えるディレクトリは配下を列挙せずに `DEPTH_LIMIT` を記録し、残りのディレクトリの走査を続ける。飛ばしたディレクトリは未完了として残るため、結果は `PARTIAL` となり消失は確定しない。件数またはメモリの上限に達すると、その時点で走査を打ち切って `PARTIAL` を返す。提供元の列挙順が変わらない限り、上限を超えるライブラリでは毎回同じ後半部分が索引されず、そのルートでは消失確定も行われない。上限に達した後も索引を続ける分割走査は後続で扱う。カーソルは一度に1つ開き、提供元の未完了・エラー通知も部分走査として扱う。個々の問い合わせやファイル解析には 10 秒で取消を要求する。この取消の経路（`CancellationSignal` の取消と `SafTimeout` への変換）は試験しておらず、試験は提供元が `SafTimeout` を返した場合の `SafLibrary` 側の扱いだけを検証する。提供元が取消を無視する場合や、カーソル取得後の読み出し・ネイティブ解析が停止した場合は、呼び出しが戻るまで待機が残る。SAF の列挙結果とルート確認は、複数ファイルの同時変更に対する一貫したスナップショットを提供しない。[DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract)

音源は読み取り専用で開き、実際のコンテナから音声トラックを検出する。MediaExtractor がメディアとして開けないファイル（カバー画像、cue、ログ等）と、動画トラックを含むコンテナは曲として登録せず、スキップ件数に数える。開けない理由が非メディアか読み取りエラーかは MediaExtractor から区別できないため、既存曲で読み取りエラーが起きた場合もスキップに数える。この場合も訪問済みとし、消失にはしない。解析失敗には、ファイルを開けない場合と解析中の例外を数える。埋め込みタグは Android で取得できる範囲を保存し、文字列は 4096 文字で制限する。アルバム名の保存単位は音源で、同名アルバムの統合・画像処理は後続のライブラリ機能で扱う。`PLAYABLE` はインデックスの状態であり、端末でのデコード・出力は別試験で確認する。URI はアプリ専用 DB に保存し、この機能の進捗・失敗結果には URI や提供元例外の本文を含めない。[MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor)

## TEST-LIB-01 の検証範囲

| 観点 | 検証 |
|---|---|
| 受領フラグ、最小権限、登録の再取得 | `SafLibraryTest` の Android OS による未許可拒否、合成提供元による許可・拒否・再取得、DB 再オープン |
| 未完了カーソルとリソースの解放 | `AndroidSafAccessTest` の API 23／35 Robolectric で null、未完了通知、エラー通知、取得済みの行、早期終了とカーソル解放 |
| 非メディアと動画のスキップ | `AndroidSafAccessTest` の API 23／35 Robolectric で、MediaExtractor が開けないファイルと動画トラックを含むコンテナを曲にしない。Robolectric の MediaExtractor は内容を解析しないため、トラック構成と `IOException` を合成する |
| 消失と復旧、部分走査、古い走査 | `SafLibraryTest` の API 23／36 専用エミュレーターでルート分離、完全走査、解析失敗、再許可、Variant 参照、上限、深さ超過のサブツリーだけを飛ばす継続、途中のアクセス喪失・サブフォルダ例外・タイムアウト・取消・古い走査 ID の障害注入、確定した状態の保存と DB 再オープン後の取得 |
| DB の互換性 | `LibraryDatabaseTest` のエクスポート済み v3 スキーマからの移行と既存参照保持、未対応版の非破壊失敗。`SafScanMigrationTest` の Robolectric で v1／v3 からの移行、既存 ID・キュー・プレイリストの保持 |
| コンテナ解析 | `AndroidSafAudioTest` の API 36 専用エミュレーターで、提供元の MIME と拡張子に依存せず合成 WAV の音声トラックを検出し、読み取り専用の記述子を解放 |

システムピッカーを経由した実際の許可成功、物理 SD 抜去、OEM 提供元、実プレイリスト UI、実機再生は未実施。永続許可取得後の復旧・物理 SD 抜去相当は合成提供元で検証する。プレイリストの結合試験では保存済み Variant の参照を利用できる。登録画面は #84、再生・キューの結合は #83、保存ジョブとの接続は #104 で扱う。DAC と外部サービスの用途許可は本変更の試験範囲外。

## 検証結果

2026-11-20 JST、実装コミット `28e4367aa9ee3bd76ebefa0b2c42b5e900a78f2a` を検証した。

- JDK 17 で `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` が成功。ユニット試験 196 件（本変更の8件を含む）が成功し、Lint はエラー0件、既存箇所の警告13件。
- API 23 と API 36 の専用 x86_64 エミュレーターに Debug／AndroidTest APK を導入し、`SafLibraryTest`、`LibraryDatabaseTest`、`AndroidSafAudioTest` を実行した。API 23 は17件、API 36 は18件が成功。実コンテナ解析の試験は API 36 で実行し、API 23 では試験用 API の下限により省略した。
- v1／v2 の生成スキーマを比較し、従来の6表の定義が一致することを確認した。

## レビュー修正の検証結果

2026-11-22 JST、origin/main（`7e8e4f1`）を merge して Room の版を v4 に振り直し、深さ超過の継続、確定状態の保存、非メディアと動画のスキップを加えた変更を検証した。

- v3／v4 の生成スキーマを比較し、v3 までの11表の定義が一致し、追加が2表と索引1つだけであることを確認した。
- `SafScanMigrationTest`、`AndroidSafAccessTest`、v1／v2 からの移行を含む `QueueDaoTest`・`PlaylistDaoTest` を Robolectric で実行し、成功した。
- JDK 17 で `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` が成功。ユニット試験225件が成功し、Lint はエラー0件、警告3件（本変更の箇所なし）。
- 専用エミュレーターでの `SafLibraryTest`・`LibraryDatabaseTest` は未実施。変更した instrumented 試験はビルドだけを確認した。
