# PCM処理 v1（Issue #41）

[Issue #41](https://github.com/4rtoflow/Audipp/issues/41) は、呼び出し元が渡した PCM を処理する native 境界を提供する。標準再生は `PlaybackService` が所有する Media3 経路で行い、PCM 取得能力は無効と表示する。現時点で本番経路から PCM 処理を呼び出す箇所はない。PCM の取得・認可と取得位置の証跡は #76 以降、DSP グラフと出力経路の接続は #66・#67・#69 などで扱う。

## 入出力と所有権

- Service の application looper で `LocalPlayback.openPcmProcessor` に現在の遷移 ID と経路 revision を渡す。この入口が確認するのは遷移 ID・経路 revision の一致と、有効なローカル再生計画の存在。呼び出し元は同じ文書の OS 権限下で得た PCM を渡す。`NativePcmProcessor.open` を直接呼ぶ場合も、PCM の取得と認可の確認は呼び出し元が担う。
- `PcmFormat` は little-endian、interleaved の signed 16/24/32-bit と Float32、レート、Android の出力位置マスクを表す。対象は mono、stereo、5.1（背面または側面）で、レート上限は 384 kHz。ch 配置やレートの変換は別処理とする。正のレート、マスクの許容集合、バッファの表現は本 PR の実装案。
- `PRESERVE_INTEGER` は整数の格納バイトを維持する。コピー不要な同一範囲はそのまま返す。`TO_FLOAT32` の承認済み正規化規約は、符号付き N ビット整数を Float64 で `2^(N-1)` で割り、出力時に一度だけ Float32 へ丸める。32-bit の正側最大値は `1.0f` に丸まる。Float32 入力では有限性を検査して全ビットを保つ。量子化・dither・クリップ制御をここで適用しない。
- ブロックには世代トークン、先頭フレームの非負 presentation time（µs）、フレーム数を渡す。上限は設定した 40/80/160 ms 分のフレーム数（既定 80 ms、端数切り上げ）。処理前後でフレーム数と ch 順序は変わらない。時刻は負値の拒否にのみ使い、時刻の根拠と世代は取得側が付ける。
- 呼び出し元は direct ByteBuffer の領域を事前確保し、呼出し中のデータ・position・limit を管理する。入口は position から指定フレーム分を借用し、cursor と ByteBuffer の byte order 設定に依存した変更を行わない。出力は writable。整数バイパスと Float32 同士は領域の重なりを許容し、整数→Float32 の重なりは拒否する。
- Stop、再生失敗、終了、シーク等の不連続、成功した再構成は世代を破棄する。JNI はポインターや native handle を保持しない。実行中のブロックは終了時にも取消を検査し、呼び出し元は `PROCESSED` 以外の出力と、受け渡し時点で無効になった世代を破棄する。処理結果を受け渡すまでバッファを再利用しない。
- ライブラリのロードと構成の検査は処理開始前に I/O worker で行う。呼び出し元の取消や再生の切替後に返った構成は適用しない。ブロック処理は同期・有界で、I/O、DB、ネットワーク、ロック待ち、バッファの確保・再確保を行わない。C の境界では無効範囲と非有限入力を結果コードで返す。ライブラリや JNI シンボルの解決に失敗した場合は `NATIVE_UNAVAILABLE` を返し、標準再生を継続する。

## ビルドと検証の範囲

単一 `:app` の CMake ターゲットを minSdk 23 でビルドする。Debug は arm64-v8a / armeabi-v7a / x86_64、Release は ARM 2 ABI。x86_64 はエミュレーター検証用とする。新規の Maven 依存・外部 DSP ライブラリ・C++ 標準ライブラリは追加しない。Audipp が実装した C ソースはリポジトリの MPL-2.0 に従う。ELF の動的リンク先は Android の `libc.so`、`libdl.so`、`libm.so`。#66 以降で導入するライブラリの API/推移依存/再配布の評価と R-02 / P-05 / IMP-03 の残項目は継続する。

| 要求・試験 | この変更の検証範囲 |
|---|---|
| FR-AUD-01 / FR-AUD-07 → DD-DSP-01 / TEST-AUD-02 | 固定 PCM の整数バイパスで 32-bit の下位ビットまで一致。16/24/32-bit の Float32 丸めを固定した独立期待値と照合。JNI に渡す範囲・形式・非有限値の拒否と再試行を検査。 |
| FR-OBS-07 → TEST-TAP-01（#41 の PCM 境界に関する部分） | 負の時刻の拒否、ch 配置、旧世代の拒否、取消・再試行を検査。取消は `close()` と `invalidatePcm()` を直接呼び出して検査する。JNI 復帰直後の取消判定と Service のシーク通知からの呼び出しは検査範囲外。取得位置ごとの信号比較と EQ の有無の識別は #76 等で追跡。 |
| FR-AUD-02 / NFR-COMP-01 → DD-PKG-02 / DD-AUD-01 / TEST-AUD-02 / TEST-COMP-01 | ローカル再生の解決、拒否・取消、native ロード失敗から独立した標準再生を検査。PR 作者のローカル実行では、API 23 / 36 の x86_64 エミュレーターで JNI 契約試験各6件に合格。ARM は APK 内の ABI とリンク先を確認。検証対象 commit と結果は[試験結果台帳](../traceability/results.json)を参照。 |

**NOT_RUN:** ARM の JNI 実行、Galaxy S25 / FiiO M21 / S20、DAC の出力・デジタル列の測定、音声コールバックの実時間性能、Provider PCM の用途別許諾と実サービス接続、EQ 前後を含む Tap の実測。ARM 実機での JNI 試験は #41 の完了条件から外し、後続で扱う。内部バッファの一致から外部出力の Bit-perfect を判定しない。
