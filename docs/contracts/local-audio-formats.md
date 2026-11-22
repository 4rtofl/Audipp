# ローカル音源のメタデータとデコード対応

Issue #36 はファイルの形式判定、取得できた音源属性の保存、標準デコーダと DSD 専用経路の対応範囲を扱う。要件は FR-SRC-04、FR-SRC-05、FR-AUD-01。走査、読み取り権限、消失判定は #35 の契約を利用する。[Issue #36](https://github.com/4rtoflow/Audipp/issues/36) · [SAF 走査](saf-library-scan.md) · [試験台帳](../traceability/results.json)

## 形式とデコード経路

| コンテナ | 読み取る属性 | 標準デコード経路と検証範囲 |
|---|---|---|
| FLAC | STREAMINFO のレート、有効ビット深度、ch、サンプル数。取得できたタグと埋め込み画像の位置 | Android の FLAC。API 23／36 の合成 mono 44.1 kHz・16-bit で PCM デコードを確認。高レート、多ch、32-bit の実機差は未検証 |
| RIFF/WAVE | PCM 整数／IEEE float、レート、ch、格納幅、整数の有効ビット深度と曲長。取得できたタグと ID3 画像 | API 23／36 で mono 8 kHz・16-bit 整数 PCM の抽出を確認。拡張 PCM と float はヘッダーを単体試験。機種・出力経路別の再生は未検証 |
| MP3 | フレームヘッダーと Android の Layer III トラック、取得できたレート、ch、曲長、タグと ID3 画像 | Android の MP3。API 23／36 の合成 mono 44.1 kHz・64 kbps で PCM デコードを確認。free format は今回の識別範囲外 |
| AAC（ADTS） | ADTS ヘッダーと Android の AAC トラック。取得できたレート、ch、曲長、タグと ID3 画像 | Android の AAC。API 23／36 の合成 AAC-LC で PCM デコードを確認。ADIF は対象外。プロファイルごとの対応は Android の仕様と端末に依存し、xHE-AAC の Android 標準対応は API 28 以降 |
| M4A/MP4 | ISO BMFF のボックスと Android が抽出した音声トラックのコーデック、レート、ch、曲長とタグ。iTunes 形式の JPEG/PNG カバー参照 | API 23／36 の合成 AAC-LC で PCM デコードを確認。AAC 以外のコーデックはコンテナ名から推定せず、抽出機能は端末・OS に依存する |
| DSF／DFF（DSDIFF） | DSD レート、ch、サンプル数／DST フレームから算出した曲長、取得できた ID3 タグと画像 | 標準ローカル経路では `UNSUPPORTED`。DSD→PCM や Native DSD／DoP は専用経路を要する。DST は DSD 出力処理の前に DST デコードも必要。方式別の成立は後続 #54〜#59 で検証する |

標準デコードの仕様と形式別構造の参照資料。[Android の対応形式](https://developer.android.com/media/platform/supported-formats) · [FLAC RFC 9639](https://www.rfc-editor.org/rfc/rfc9639.html) · [WAVEFORMATEXTENSIBLE](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ksmedia/ns-ksmedia-waveformatextensible) · [Sony DSF 仕様](https://dsd-guide.com/sites/default/files/white-papers/DSFFileFormatSpec_E.pdf) · [Philips DSDIFF 1.5](https://www.sonicstudio.com/pdf/dsd/DSDIFF_1.5_Spec.pdf) · [ID3v2.4](https://id3.org/id3v2.4.0-frames) · [QuickTime メタデータ](https://developer.apple.com/documentation/quicktime-file-format/metadata_atoms_and_types)

## 走査と保存

- 形式は読み取り専用の記述子の内容から識別する。SAF が返す MIME はディレクトリの識別に使い、表示名の既知の音源拡張子は内容と矛盾する場合の検出に使う。表示名が省略されても内容で識別する。判定対象外の形式は既存の Android 抽出を試す。
- 0 長、ヘッダーや宣言された範囲の破損、拡張子不一致、既知の非対応符号化、判定上限、MP4 の音声トラック取得不可は、別の問題コードで記録する。走査は次のファイルへ進み、既存曲は訪問済みとして ID と最後の取得済みメタデータを保持する。明示的な非対応は `UNSUPPORTED`、破損などは `UNKNOWN` にする。再走査に成功すると同じ Variant の音源属性を更新し、今回取得できなかったタグは null に戻す。
- Room v5 は v4 の `track_variants` に nullable な `local_*` 列を追加する。既存の Variant、DAO、走査トランザクションを再利用する。格納幅と float の別は `local_pcm_storage_bits` と `local_pcm_floating_point` に保持し、float や DSD、非可逆圧縮の `bit_depth` は null とする。FLAC はデコード前の整数ソース精度を保持する。
- 画像は Variant の元ドキュメント内の `embedded:<offset>:<length>:<mime>` を保存する。外部 URL の画像は追跡しない。オフセットはドキュメント先頭基準で、将来画像を使う側は元ドキュメントへの読み取り許可を再確認し、取得量とデコード寸法に上限を設ける。
- 自前の検査は最大 512 回の構造読み取り、ID3v2.3／2.4 は最大 256 フレーム、画像の記述部は最大 4 KiB、保存するタグは最大 4,096 文字とする。画像全体や音声全フレームの読み出し、ハッシュ・CRC 照合は行わない。拡張ヘッダー・非同期化フラグ付き ID3 の自前読取、非 seekable な提供元、RF64／RIFX は対象外。Android のネイティブ抽出器内の資源使用量や、停止した提供元の処理時間にはこの上限は適用されない。
- `PLAYABLE` は索引作成時に Android が検出した音声トラックと先頭サンプルを根拠とする。実際のデコード結果、アプリでの連続再生、音声出力の観測は個別に検証する。

## 検証

2026-11-20 JST、実装コミット `59a3915ce531841f692dd529a0b66633bc4ea655` を JDK 17 と専用 x86_64 エミュレーターで検証した。

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` が成功。ユニット試験 208 件、Lint エラー 0 件・既存警告 13 件。
- API 23 は 23 件、API 36 は 24 件が成功。サンプルを抽出・デコードする試験、SAF の拒否・タイムアウト・取消・古い応答・再試行、曲単位エラー後の走査継続と既存 ID の保持、統合前の Room v1／v2 から v3 への移行を確認した。
- 合成画像で FLAC の大きな画像本文を読み飛ばすことと、MP4／ID3 の参照位置を確認した。実音源、物理 DAP／DAC、Media3 での連続再生、UI 表示、画像表示とネイティブ抽出器のメモリ実測は未実施。

音源 fixture は 1 kHz・44.1 kHz のモノラル正弦波 0.25 秒を FFmpeg で FLAC、MP3（64 kbps）、AAC-LC（64 kbps の ADTS と M4A）にエンコードした。M4A は合成 2×2 PNG をカバーとして格納している。WAV、DSF、DFF の各ヘッダーと不正入力は試験コードで組み立てる。
