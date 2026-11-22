# 20 バンド PEQ と設定切替 v1（Issue #91）

[Issue #91](https://github.com/4rtoflow/Audipp/issues/91) は、[dsp-chain-v1](dsp-chain-v1.md) の `DspStages.peq` に入れるパラメトリック EQ の段（`PeqStage`）を提供する（FR-AUD-06、DD-DSP-02）。現時点で本番経路から呼び出す箇所はない。設定画面（#133）と preset の保存形式は変えていない。

## 入力と検査

`PeqStage.apply(PeqPreset)` が唯一の入口で、検査・係数の生成・引き渡しを呼び出し元のスレッドで行う。音声スレッドは検査済みの係数だけを受け取る。

| 項目 | 範囲 | 拒否の理由 |
|---|---|---|
| バンド数（無効なバンドも数える） | 0〜20 | `TOO_MANY_BANDS` |
| 中心周波数 | 20 Hz 〜 min(20 kHz, 0.45·Fs)（両端を含む） | `FREQUENCY_OUT_OF_RANGE` |
| gain | ±12 dB（両端を含む。LOW/HIGH_PASS でも検査するが係数には使わない） | `GAIN_OUT_OF_RANGE` |
| Q | 0.1〜20 | `Q_OUT_OF_RANGE` |
| preamp | -30〜0 dB | `PREAMP_OUT_OF_RANGE` |
| NaN / Inf（周波数・gain・Q・preamp） | 不可 | `NON_FINITE` |
| preset の `sampleRateHz` | stage のレートと一致 | `SAMPLE_RATE_MISMATCH` |

拒否した preset は何も変えず、直前の preset がそのまま効く。無効なバンドの値も検査する。種類は PEAK / LOW_SHELF / HIGH_SHELF / LOW_PASS / HIGH_PASS（`EqBandType`）。

## 係数と処理

- 係数は RBJ Audio EQ Cookbook の式（shelf は Q 形式）で Float64 に求め、a0 で割る。Float32 のバッファに対し、biquad（転置直接形 II）の状態と累積は Float64。バンドは配列順に直列にかけ、全 ch に同じ preset を使う。5.1 でも LFE を区別しない。
- 検査範囲内の全種類・Fs 8〜384 kHz で極が単位円内にある（Jury の条件）ことを単体試験で確認している。実行時の安定性検査は入れていない。
- 有効なバンドが 0 で preamp が 0 dB の preset は素通しになる。

## 設定の切替（crossfade）

- `apply` は係数を `AtomicReference` に置く。音声スレッドはブロックの先頭で `getAndSet(null)` により受け取り、確保もロックもしない。
- 受け取ると、新しい preset の枝を零状態から始め、旧い枝と**並行して動かす**。`RAMP_MS`（10 ms、`round(レート × 0.01)` フレーム）の間、`出力 = (1-w)·旧 + w·新`（w はフレームごとに線形に 1/N〜1）で混ぜ、終われば新しい枝だけにする。係数を補間した中間の係数は使わない。両方の枝はそれぞれ安定している。
- 最初の preset は素通しの枝から crossfade する。crossfade 中に届いた preset は終わるまで待ち、複数届けば最後の1つだけが残る。反映は crossfade の終了後の最初のブロックの先頭になる。
- 新しい枝は零状態から始まるため、低い周波数・高い Q では過渡が crossfade より長く残りうる。振幅の連続性は保つが、その過渡の消え方は保証しない。

## 10 ms のゲイン平滑化との関係

- chain の 10 ms 線形ゲイン平滑化（ReplayGain/R128 と chain の preamp）と PEQ の crossfade は別の仕組みで、同じブロックの先頭で始まれば 10 ms で同時に終わる。両方とも乗算で、互いの状態を持たない。
- PEQ の preamp（`PeqPreset.preampDb`）は chain の `gainDb` に含めず、PEQ の枝の線形ゲインとして出力に掛ける。preset を替えると preamp も crossfade で動く。chain の `setGainDb` の ramp を使わない。

## 最大の正ゲイン（headroom）

`Applied.maxPositiveGainDb` を、呼び出し元が `DspConfig.maxPositiveGainDb` に加える。

- **実装提案:** バンドの gain の正の値を足すのではなく、直列にした全体の振幅応答を求め、その最大値に preamp を足して 0 dB で下限を切る。バンドが重なれば dB で加わり、離れていれば加わらない。LOW/HIGH_PASS は gain 欄が 0 でも Q が約 0.707 を超えると角で正のゲインを持つ（Q 20 で約 26 dB）ので、バンド単位の合算では漏れる。
- 応答は DC・Nyquist・各バンドの中心・10 Hz〜Nyquist の対数格子 4097 点で評価する。独立に求めた Float64 の 1/80000 oct 格子の最大値との差は、試験した8通り（Q 20 の重なり、20 Hz 近辺の shelf と high pass を含む）で 0.01 dB 以内。
- これは正弦波の定常応答の最大で、任意の信号の上限ではない。上限（インパルス応答の絶対値の和）はこれより大きくなりうる。余りは clip guard が受ける。
- preset の切替後の値が、chain を開いたときの `maxPositiveGainDb` と減衰量を超えうる。切替時にどうするか（拒否、chain の作り直し、減衰量の再確認）は製品の判断で、この変更では決めていない。呼び出し元は `Applied.maxPositiveGainDb` で比較できる。

## 設定型との対応

`ParametricEq.toPeqPreset()` が保存済みの `ParametricEq` を `PeqPreset` に写す。

| `ParametricEq` / `ParametricBand` | `PeqPreset` |
|---|---|
| `sampleRateHz`、`bands`（type・centerHz・gainDb・q・enabled） | そのまま |
| `preampDb`（null） | 0 dB |
| `enabled`（null） | 有効として扱う（**実装提案**） |
| `enabled = false` | バンドなし・preamp 0 dB（素通し） |
| `DspSettings.preampDb`（EQ の外） | 対応しない。chain の `gainDb` 側（ReplayGain/R128 と preamp の1つの平滑化ゲイン） |

`ParametricEq` と `ParametricBand` は構築時に範囲を検査して例外を投げるため、保存済みの値は `apply` の検査を通る。`apply` は DSP に渡る値の境界であり、不正値が構築できない型からは `NON_FINITE` などの拒否は起きない。

## 検証の範囲

| 要求・試験 | この変更の検証範囲 |
|---|---|
| FR-AUD-06 / FR-AUD-09 → DD-DSP-02 / TEST-AUD-02 | JVM 単体試験18件（`PeqStageTest`）。係数の周波数応答を独立の参照（cookbook のアナログ原型 H(s) を前置歪み付き双一次変換で評価、Float64 の複素数）と比較して 1e-6 dB 以内（5種類 × Fs 6通り × 周波数・gain・Q の組合せ）。実際に処理した正弦波の利得が参照と 0.01 dB 以内（Float32 の入出力による）。直列のバンドと preamp の積。無効バンドの除外、ch ごとの独立。各上下限の境界値の受理と、そのすぐ外側・NaN/Inf・21 バンド・Nyquist 以上・Fs 不一致の拒否。拒否後に旧 preset が bit 単位で変わらない（crossfade 中を含む）。crossfade が `(1-w)·旧 + w·新`（新は零状態から、独立の Float64 直接形 I）と 2e-6 以内で一致。反対向きの preset の切替で 1 サンプルの変化が正弦波自身の最大変化の 1.1 倍以内。crossfade 中に届いた preset が待ち、最後の1つが効く。preamp の crossfade と chain の 10 ms ゲイン平滑化が同時に走るときの積。最大の正ゲインを 0.01 dB 以内で独立の格子と比較し、重なり・preamp・Q 20 の low pass の値を確認し、chain の headroom 検査へ渡して不足と受理を確認。 |
| QLT-11（処理時間の部分） | **JVM（ホスト）での実測のみ。** 条件: Intel Core Ultra 7 155H、JDK 17、20 バンドの PEAK、preamp -6 dB、入力は周期的な疑似信号、1 ブロックの処理を 4000 回測り、前に 3000 ブロックで暖機。`process` の1回の時間で、crossfade の開始は 20 ブロックに1回。値（中央値 / P99 / ブロック再生時間に対する P99）: 48 kHz・2 ch・480 フレーム（定常）36 / 43 µs / 0.43 %、同（crossfade を含む）35 / 75 µs / 0.75 %、384 kHz・2 ch・3840 フレーム（定常）302 / 346 µs / 3.46 %、同（crossfade を含む）296 / 620 µs / 6.20 %、48 kHz・6 ch・480 フレーム（定常）110 / 126 µs / 1.26 %、同（crossfade を含む）109 / 232 µs / 2.32 %。測定用の試験コードは残していない。 |

**NOT_RUN:** ART（API 23 / 36）での実行と処理時間（QLT-11 の合否は ART・実機で測るまで判定しない。上の値は JVM のもの）、実機・DAC の出力と測定、`LocalPlayback` や設定画面（#133）への接続、IR など他の段との組合せ、再生中の preset 切替の聴感確認、音声スレッドの競合の試験、無音時の denormal による処理時間の変化。
