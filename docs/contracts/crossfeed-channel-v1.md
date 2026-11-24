# Crossfeed・左右 gain・swap・mono v1（Issue #93）

[Issue #93](https://github.com/4rtoflow/Audipp/issues/93) は、[dsp-chain-v1](dsp-chain-v1.md) に入れる2つの段を提供する（FR-AUD-06、DD-DSP-02）。左右 gain・swap・mono は `ChannelStage` で `DspStages.channels` に、Crossfeed は `CrossfeedStage` で `DspStages.crossfeed` に入れる。現時点で本番経路から呼び出す箇所はない。設定画面（#133）と preset の保存形式は変えていない。

作り方は [peq-v1](peq-v1.md) と同じにした。検査と係数の生成は `apply` を呼んだスレッドで行い、`AtomicReference` で音声スレッドへ渡す。拒否した設定は何も変えず、直前の設定がそのまま使われる。音声スレッドは確保・ロック・I/O をしない。

## 処理の位置

`map → channels → ReplayGain/R128 + preamp → PEQ → IR → Crossfeed → Apodizing → SRC → clip guard → 量子化`

- **実装提案:** `channels` の枠を `map`（5.1→2ch の downmix、#41）の直後に足した。DD-DSP-01 の「channel map/downmix」に当たる。swap と mono は IR より前に置く必要がある。左右で別の IR を使う場合に、補正ごと入れ替えないためである。左右 gain も同じ段で掛ける。PEQ と chain のゲインは全 ch に同じ処理をするので、この段を前後どちらに置いても結果は変わらない。
- 左右 gain は Crossfeed の前に掛かる。Crossfeed の後に掛ける方式（耳ごとの音量差の補正）とは、Crossfeed が有効なときに結果が異なる。

## 左右 gain・swap・mono（`ChannelStage`）

各左右の組に 2×2 の行列を掛ける。順序は swap → mono → 左右 gain で、mono は `(L+R)/2`（**実装提案**）。逆相の組は 0 になり、片側だけの信号は両側に半分ずつ出る。swap と mono を両方指定すると mono と同じになる。

| ch 配置 | 扱い |
|---|---|
| stereo | FL / FR の組 |
| 5.1（背面・側面とも、Android の並び FL FR FC LFE BL/SL BR/SR） | FL / FR と BL/SL / BR/SR の2組に同じ行列を掛ける。FC と LFE はそのまま。mono は組ごとの平均で、5.1 全体を1つにまとめるものではない（**実装提案**） |
| mono（1 ch） | 左右の組がない。何もしない設定だけを受け付け、それ以外は `NO_LEFT_RIGHT_PAIR` で拒否する |

| 項目 | 範囲 | 拒否の理由 |
|---|---|---|
| 左 gain・右 gain | ±12 dB（両端を含む。**実装提案**。PEQ のバンドの gain と同じ幅） | `GAIN_OUT_OF_RANGE` |
| NaN / Inf | 不可 | `NON_FINITE` |

切替では、旧い行列から新しい行列へ `RAMP_MS`（10 ms）かけて `(1-w)·旧 + w·新` で移る。行列は記憶を持たないので、この補間は2つの出力を混ぜることと等しい。途中で届いた設定の扱いは PEQ と同じで、終わるまで待ち、複数届けば最後の1つだけが使われる。何もしない設定では入力が bit 単位でそのまま出る。

## Crossfeed（`CrossfeedStage`）

- ステレオだけに掛ける。1 ch と 6 ch で有効にすると `NOT_STEREO` を返し、何も掛けない。
- **実装提案:** Bauer の stereophonic-to-binaural 方式とし、強度から利得への対応は bs2b と同じにした。出力は、自分の ch の一次 high-boost と、反対の ch の一次 low pass の和になる。
  - 強度 `level`（dB）は、低域で反対側の信号が自分の側よりどれだけ小さいか。low pass の利得 `GL = 10^((-5·level/6 - 3)/20)`、high-boost の低域の減衰 `GH = 1 - 10^((level/6 - 3)/20)`、high-boost の角 `fh = fc·2^((20·log10 GL - 20·log10 GH)/12)`。全体に `1/(1 - GH + GL)` を掛け、同相の DC を 0 dB にする。
  - アナログ原型 `direct(s) = (s + ωh(1-GH))/(s + ωh)`、`cross(s) = GL·ωc/(s + ωc)` を、それぞれの角で前置歪みした双一次変換で離散化する。係数と状態は Float64。
- 範囲（**実装提案**、bs2b の範囲と同じ）と既定値:

| 項目 | 範囲 | 既定 | 拒否の理由 |
|---|---|---|---|
| cutoff（low pass の角） | 300〜2000 Hz。加えて `fh`（最大約 3.47 kHz）が 0.45·Fs 以下 | 700 Hz | `CUTOFF_OUT_OF_RANGE` |
| level | 1〜15 dB | 4.5 dB | `LEVEL_OUT_OF_RANGE` |
| NaN / Inf（level） | 不可 | | `NON_FINITE` |

無効の設定は値を検査せずに受け付け、素通しの枝へ切り替える（ステレオ以外では何もしない）。保存値の cutoff や level が範囲外でも、利用者が OFF にすれば必ず切れる。有効の設定の検査の順は `NOT_STEREO`、`NON_FINITE`、level、cutoff。`fh` の条件にかかるのは Fs が約 7.7 kHz 未満の場合だけである。

切替は PEQ と同じ方式にした。新しい設定の枝を零状態から始めて旧い枝と並行して動かし、10 ms かけて出力を混ぜる。係数は補間しない。最初の設定は素通しの枝から crossfade する。

## headroom（`maxPositiveGainDb`）

各段の `Applied.maxPositiveGainDb` を、呼び出し元が dB で足して `DspConfig.maxPositiveGainDb` に入れる。

`maxPositiveGainDb = ReplayGain/R128 と preamp の最大 + PEQ + ChannelStage + CrossfeedStage`

直列の段の最大値の積は、全体の最大値以上になる。このため dB で足した値は安全側に見積もられる。試験の組合せ（mono・左 +6 dB・右 +3 dB、Crossfeed 2000 Hz / 1 dB、PEQ の +6 dB low shelf）では、50 Hz の同相の正弦波の定常値は上限より約 3.9 dB 低かった。

- `ChannelStage`: `max(0, 左 gain, 右 gain)`。各出力の係数の絶対値の和がその側の gain になるため、正弦波に限らずどの入力に対しても上限になる。mono も swap も増えない。
- `CrossfeedStage`: direct と cross のインパルス応答の絶対値の和を足した値で、0 dB で下限を切る（**実装提案**）。各出力は `direct(自分の ch) + cross(反対の ch)` なので、左右にどんな信号が入っても上限になる。一次の区間のインパルス応答は `h[0] = b0`、`h[n] = (b1 - a1·b0)·(-a1)^(n-1)` なので、和は `|b0| + |b1 - a1·b0| / (1 - |a1|)` の閉じた式で求まる。値は、Fs 4通り（8〜384 kHz）× 範囲の角と既定の6通りで +0.43〜+2.96 dB。48 kHz での最大は +2.90 dB（300 Hz / 1 dB）である。
- `|direct| + |cross|` の周波数上の最大は使わない。この値は試した範囲では DC の 0 dB になるが、左右が同じ周波数のときにしか上限にならない。左右の周波数が異なると、高域での direct の利得 `g` と DC での cross の利得 `g·GL` が重なる。たとえば 48 kHz・既定の設定に満振幅の L=10 kHz・R=50 Hz を入れると、0 dBFS を超える。
- アナログ原型の閉じた式 `g·(1 + GH + GL)` も使わない。試した範囲ではデジタルの値を下回らず上限として働いたが、双一次変換の後でも常に上限になることは示していない。また、低い Fs では実際の値より大きく見積もる（8 kHz・2000 Hz / 1 dB で、実際の +1.61 dB に対して +2.97 dB）。デジタルの係数から直接求めた和は、実際のフィルターの値そのものである。

## 設定型との対応

`DspSettings.toChannelSettings()` と `DspSettings.toCrossfeedSettings()` が、保存済みの `DspSettings` をこの段の入力に写す。

| `DspSettings` | この段 |
|---|---|
| `channelMode` = `ORIGINAL` / null | 何もしない |
| `channelMode` = `SWAP_STEREO` | `swap` |
| `channelMode` = `MONO` | `mono` |
| `channelMode` = `DOWNMIX_5_1` | この段では何もしない。downmix は `map` の段（#41） |
| `leftGainDb` / `rightGainDb`（null） | 0 dB |
| `crossfeedEnabled`（null） | 無効 |
| `crossfeedCutoffHz` / `crossfeedLevelDb`（null） | 700 Hz / 4.5 dB（**実装提案**） |
| `includeLfe`、`enabled` | 対応しない。downmix と chain を作るかどうかは呼び出し元の判断 |

`DspSettings` は gain と level を有限値かどうかしか検査せず、cutoff は正の値かどうかしか検査しない。範囲外の値は `apply` が拒否する。

## 検証の範囲

| 要求・試験 | この変更の検証範囲 |
|---|---|
| FR-AUD-06 / FR-AUD-09 → DD-DSP-02 / TEST-AUD-02 | JVM 単体試験24件（`ChannelStageTest` 11件、`CrossfeedStageTest` 13件）。同相・逆相・左だけ・右だけの impulse で、swap・mono・左右 gain の7通りの組合せが定義の Float64 計算と 1e-7 以内で一致。5.1 の2組と FC/LFE、mono（1 ch）での拒否、何もしない設定の bit 単位の素通し。gain の境界の受理とその外側・NaN/Inf の拒否、拒否後に旧設定が bit 単位で残ること。行列の 10 ms の線形な移行と最大の段差（1/480）、途中で届いた設定の待機と最後の1つ。無効の設定が範囲外の値でも受理され、有効だった Crossfeed が切れて入力が bit 単位でそのまま出ること。Crossfeed の処理したインパルス応答を Float64 で DFT し、独立に書いたアナログ原型（前置歪みの周波数で評価）と比べ、Fs 5通り（44.1〜384 kHz）× 6通り × 9周波数で複素数の差が 1e-6 以内（実測の最大 2.9e-8）。DC の同相 0 dB、DC で反対側が `level` dB 低いこと、cutoff での -3 dB。非 2 ch で `NOT_STEREO` を返し入力が変わらないこと。cutoff・level の境界と、有効の設定での範囲外・NaN/Inf、低い Fs での `fh` の拒否。拒否後に旧設定が bit 単位で残ること（crossfade 中を含む）。crossfade が `(1-w)·旧 + w·新`（新は零状態から、独立の Float64 直接形 I）と 2e-6 以内で一致。無効と最も強い設定の間の切替で、1 サンプルの変化が定常時の最大変化の 1.1 倍以内。途中で届いた設定の待機と最後の1つ。最大の正ゲインが、処理した実際のインパルス応答の絶対値の和と 0.01 dB 以内（Fs 4通り × 6通り）。48 kHz ではアナログの `g·(1 + GH + GL)` 以下。Crossfeed だけの chain に満振幅の L=10 kHz・R=50 Hz を入れ、この寄与で減衰すると clip guard の前が 0 dBFS 以下になり、減衰しなければ level 15 dB 以外の設定では 0 dBFS を超えること。各段の寄与の和で chain を開き、同相・逆相・片側の満振幅の正弦波で clip guard の前の値が 0 dBFS 以下になること、必要量より 0.01 dB 少ない減衰を `HEADROOM_INSUFFICIENT` で拒否すること。`STANDARD` と `DIRECT_ATTEMPT` では chain ができず、数える段で包んだ ChannelStage・CrossfeedStage を含め、どの段も一度も呼ばれないこと（Native / DoP は `DspOutput` にない）。 |
| QLT-11（処理時間の部分） | **JVM（ホスト）での実測のみ。** 条件: Intel Core Ultra 7 155H、OpenJDK 17.0.20.1。入力は周期的な疑似信号。前に 3000 ブロックで暖機してから、1 ブロックの `process` を 4000 回測った。crossfade を含む場合は 20 ブロックに1回始める。値（中央値 / P99 / ブロック再生時間に対する P99）は次のとおり。Crossfeed: 48 kHz・2 ch・480 フレームで定常 4.0 / 5.5 µs / 0.05 %、crossfade を含むと 2.3 / 6.2 µs / 0.06 %。384 kHz・2 ch・3840 フレームで定常 17.9 / 20.6 µs / 0.21 %、crossfade を含むと 18.3 / 46.4 µs / 0.46 %。ChannelStage: 48 kHz・2 ch で定常 2.1 / 3.1 µs / 0.03 %、移行を含むと 1.9 / 4.2 µs / 0.04 %。384 kHz・2 ch で定常 16.0 / 18.3 µs / 0.18 %、移行を含むと 15.3 / 19.0 µs / 0.19 %。48 kHz・6 ch は 3.8 / 4.3 µs / 0.04 %。測定用の試験コードは残していない。 |

**NOT_RUN:** ART（API 23 / 36）での実行と処理時間。QLT-11 の合否は ART・実機で測るまで判定せず、上の値は JVM のもの。ほかに、実機・DAC の出力と測定、`LocalPlayback` や設定画面（#133）への接続、downmix（#41）・IR との組合せ、再生中の切替の聴感確認、音声スレッドの競合の試験。
