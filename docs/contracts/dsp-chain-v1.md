# DSP 処理順・headroom・clip guard・量子化 v1（Issue #68）

[Issue #68](https://github.com/4rtoflow/Audipp/issues/68) は、`PROCESSED` 経路の DSP の処理順、headroom の検査、clip guard、整数 PCM への量子化を提供する。各段の中身（PEQ・IR・Crossfeed・Apodizing・SRC・ReplayGain/R128 の算出）は #89〜#93・#67・#90 が担う。ここでは段の受け口と、その前後の処理だけを持つ。現時点で本番経路から呼び出す箇所はない。PCM の取得は [native-pcm-v1](native-pcm-v1.md)、接続は #66・#69 などで扱う。

## 処理順

`map → ReplayGain/R128 + preamp → PEQ → IR → Crossfeed → Apodizing → SRC → clip guard → 量子化`（DD-DSP-01、[09 ②](../specifications/09_private_completion_review.md)）。`DspStages` の各スロットが順序を固定し、未設定のスロットは素通しにする。ReplayGain/R128 と preamp は線形ゲインで可換なので、map の直後に1つの平滑化ゲインとして適用する。

## 入出力と所有権

- 入力は interleaved の Float32 を `FloatArray` で受け取り、その場で処理する。出力は呼び出し元の `ByteBuffer` の position からリトルエンディアンで書く。`PROCESSED` 以外では何も書かない。
- 形式は mono / stereo / 5.1 の 1・2・6 ch、1〜384000 Hz。`DspChain.open` が設定を1回だけ検査し、失敗した設定は `Rejected` を返して chain を作らない。`process` は検査済みの値だけを扱う。
- `process` は音声スレッドで動き、確保・ロック・I/O を行わない。実行中のゲイン変更は `@Volatile` の目標値だけで渡し、次のブロックの先頭で読む。
- フィルター係数や段の差し替えは、バックグラウンドで新しい `DspChain` を作ってから呼び出し元が差し替える。差し替えの手順と crossfade は各段の Issue で扱う。

## headroom

`requiredHeadroomAttenuationDb = max(0, 最大の正ゲインの合計 dB + 20·log10(IR 係数の絶対値の和))`。入力は 0 dBFS を想定する。呼び出し元は表示した減衰量を `headroomAttenuationDb` に渡す。必要量に満たなければ `HEADROOM_INSUFFICIENT`、必要量が 60 dB を超えれば `OVERLOAD` で拒否する。chain が減衰量を自動で選ぶことはない。

## ゲインの平滑化

目標ゲインが変わると、現在値から 10 ms（`round(レート × 0.01)` フレーム）で線形に近づける。途中で目標が変わったら、その時点の値から新しい 10 ms を始める。

## clip guard

- True Peak は 8 倍オーバーサンプリング（Hann 窓の sinc 補間、入力 16 サンプル）で見積もる。見積もりは下限で、Nyquist 付近の満振幅の正弦波で最大約 0.17 dB 低い。
- 全 ch で共通のゲインを使い、必要ゲインのスライディング最小値を先読み長の移動平均で滑らかにする。ピークが出力に届く時点で、ゲインは必要値まで下がっている。先読みは 5 ms（`ceil(レート × 0.005)` フレーム）、目標は -1 dBTP。遅延は先読みに補間の片側 8 フレームを足した値で、先頭はその分の無音になる。
- 戻りは同じ長さの移動平均になる（約 5 ms）。ストリーム終端で遅延分を吐き出す処理はない。

## 量子化と dither

- 16/24-bit 整数だけに TPDF dither（±1 LSB、xorshift32）をかけ、最近接偶数へ丸めて範囲内に収める。
- 32-bit 整数は dither しない。Float32 の仮数は 24 bit で、この変換では精度を落とさないため。
- Float32 出力と DSD 変調器への出力は Float のまま渡し、PCM 用の dither を重ねない。

## 経路の分離

`OutputMode.PROCESSED` 以外（Bit-perfect・Native・DoP を要求する経路を含む）は `NOT_PROCESSED_PATH` で拒否し、guard やデジタル音量を差し込む chain を作らない。`DspOutput` に Native / DoP の DSD はない。

## 拒否の種類

| 入口 | 結果 |
|---|---|
| `open` | `NOT_PROCESSED_PATH`、`UNSUPPORTED_FORMAT`、`INVALID_PARAMETER`（NaN/Inf・負の減衰・0 以下の IR 和・`gainDb` が最大正ゲイン超）、`HEADROOM_INSUFFICIENT`、`OVERLOAD` |
| `process` | `NON_FINITE_SAMPLE`（入力に NaN/Inf、段は呼ばない）、`OVERLOAD`（段の出力に NaN/Inf）、`INVALID_BLOCK`（フレーム数・バッファ容量） |
| `setGainDb` | 非有限、または最大正ゲイン超は `false` |

## 検証の範囲

| 要求・試験 | この変更の検証範囲 |
|---|---|
| FR-AUD-07 / NFR-AUD-04 → DD-DSP-01 / TEST-AUD-02 | JVM 単体試験20件。処理順、headroom の式と拒否、NaN/Inf・過負荷の拒否と状態の不変、10 ms の線形平滑化（48/44.1 kHz）、guard の先読み 240 フレームと -1 dBTP（1/11/20 kHz の正弦波・インパルス）、ch 共通ゲイン、dither の適用範囲と統計、Native/DoP 経路の拒否。True Peak は試験側の別実装（16 倍・±128 サンプルの sinc、Float64）で測り、許容は +0.2 dB（QLT-05 の True Peak 誤差）とする。 |
| FR-AUD-09 | 設定の検査と生成を `open` に分けた。失敗した設定からは chain ができない。音声スレッドのロック不使用は設計で担保し、競合の試験はない。 |

**NOT_RUN:** ART 上（API 23 / 36）での実行、ブロック処理時間と P99（QLT-11）、実機・DAC の出力と測定、`LocalPlayback` への接続、IR・PEQ など実際の段との組合せ、再生中のゲイン変更を含む聴感確認。
