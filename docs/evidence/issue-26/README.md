# Issue #26 実機・外部音声測定の準備記録

試験担当者が端末と測定設備を確保し、同じ条件で測定できるようにする。現時点で使える試料は [Issue #17 の48 kHz PCM fixture](../../../tests/audio/manifest.json)。対象ADBシリアル・操作承認、外部デジタル取得系、広帯域アナログ測定系、6chの成立機器は未確保。実機・DACの操作と収録は **NOT_RUN**、成立機器や設備を要する測定は **BLOCKED**。

対象は E11-01、承認仕様1.0.1（`2adc276`）の [DD-TEST-03/04・TEST-COMP-01](../../specifications/03_detailed_design.md)、[Q-03・R-03・P-04](../../specifications/06_decisions.md)、[QLT-02/08/09](../../specifications/09_private_completion_review.md) の準備部分。検証対象commitとケース結果は [要求・試験台帳](../../traceability/results.json) に記録する。

## 対象端末とDAC

| 区分 | 対象 | 指定・メーカー公称（試験前の情報） | 個体で確認する項目 |
| --- | --- | --- | --- |
| 主要スマートフォン | Samsung Galaxy S25 | Android 16指定 | 正式モデル番号、OS/API、build hash、ABI一覧、端末firmware、USB host・実出力経路 |
| 主要DAP | FiiO M21 | 製品資料はAndroid 13を記載。USB Audio出力・同軸出力・USB DAC入力で公称条件が異なる。 [製品紹介](https://www.fiio.com/M21)・[仕様](https://www.fiio.com/m21_parameters) | 正式モデル番号、実機OS/API、build hash、ABI一覧、firmware、Androidモードと実出力経路 |
| 追加スマートフォン | Samsung Galaxy S20 | OS・モデル派生は未確認 | 正式モデル番号、OS/API、build hash、ABI一覧、firmware、標準再生・USB host・実出力経路 |

3台とも実機観測値は未取得（NOT_RUN）。S20は互換性の追加枠とする。API 23境界・API 36のinstrumented試験もNOT_RUN。S25とM21の必須ケースは各実機で記録する。

| DAC候補 | 接続案とメーカー公称 | 個体観測 |
| --- | --- | --- |
| iFi audio ZEN DAC V2 | USB入力。PCM 384 kHz・DSD256公称。アナログ取得は背面RCAまたは差動出力を候補とする。 [取扱説明書](https://downloads.ifi-audio.com/wp-content/uploads/2021/04/ZEN-DAC-V2-Manual_Ver1.0-1-1.pdf) | NOT_RUN |
| MOONDROP ECHO-B | USB-C入力、4.4 mm差動出力。32-bit/384 kHz公称、内蔵DSPあり。 [製品仕様](https://moondroplab.com/en-products/echo-b) | NOT_RUN |
| MOONDROP ECHO-A | USB-C入力、3.5 mmシングルエンド出力。32-bit/384 kHz公称。 [製品仕様](https://moondroplab.com/en-products/echo-a) | NOT_RUN |

DACの実firmware、USB VID/PID、構成・interface・alternate・endpoint、形式/レート/ch申告、接続・給電、動作経路、ロックと出力は各組合せで未確認。S25/M21の各々で内蔵出力を基準ケースに取り、USB hostから候補DACを一台ずつ直結して列挙を確認する。測定器を挿入する場合は挿入前後で列挙・経路・動作条件を確認する。ハブや外部給電が必要なら別条件として残す。Native、DoP、384 kHz、6chの成立はすべて組合せごとの確認対象とする。

## 測定構成と校正案

| ケース・状態 | 構成と校正 | 測定条件・判定 |
| --- | --- | --- |
| [外部デジタル列](usb-digital.json) / BLOCKED | Android USB host → 受動監視型USBプロトコルアナライザー → DUT DAC。実バス速度に合うアナライザーでisochronous OUTの全payload、packet status、時刻を取得する。既知の短いPCM列で欠落・切り詰め・byte順・ch順を事前照合。対象速度で捕捉を取りこぼさず書き出せることを確認する。 | QLT-02。DSP・音量処理・SRCをバイパスし、既知の16/24/32-bit整数PCMを使う。各レート/chでUSB subslotと有効bit、符号・ch配置を別記。固定遅延の根拠と整列sample数を残し、有効区間の全sampleと長さを独立比較する。不一致・欠落・重複は0。アプリ内tap、OS申告、DAC表示はそれぞれ別の観測として残す。 |
| [DSD転送・切替](usb-digital.json) / BLOCKED | 同じUSB捕捉系に加え、DACロック表示とアナログ収録を共通トリガーまたは時刻で関連付ける。既知のDSDペイロードで独立抽出器を照合する。 | QLT-09。USB PCM、Native、DoPを分け、DSD64/128/256ごとにbyte/bit順・ch・DoP marker・原DSD列を照合する。切断、再接続、Stop、経路切替を別試行にする。停止境界と転送時刻を突き合わせ、取消後の転送0、USB断時の内蔵スピーカー自動出音なしを確認。解放後callback使用0はアプリ側の計測・故障注入と組み合わせて確認する。 |
| [DSD帯域外・DAC過渡](dsd-analog.json) / BLOCKED | DAC → 定格内の抵抗性ダミー負荷 → 広帯域差動入力のアナライザーと、切替をトリガー収録できるオシロスコープ。ライン出力とヘッドホン出力で負荷条件を分ける。差動出力の負側を接地しない。校正済み発振器で電圧・帯域応答を点検し、短絡終端で残留ノイズ、既知信号でクリップ余裕と過渡の捕捉を確認する。 | QLT-08の負荷付きDAC出力は下記の手順で測定する。QLT-09では切替前後のピーク電圧、DC、持続時間、DACロックを記録する。負荷別の安全判定条件は #148 で確認する。 |
| [5.1chとステレオ復帰](multichannel.json) / BLOCKED | Android → 実際に6chを出力できるDAC/経路 → 6入力以上の同時録音系。6出力に規定のダミー負荷を接続し、ch別に既知信号を入れて配線・感度・位相・時間差・漏れを校正する。デジタル経路も検証する場合は対応する6ch取得器で配置と列を保存する。 | 48 kHz/24-bitの既存5.1 impulseでFL/FR/FC/LFE/BL/BRを個別に同定。ch取り違え・欠落、漏れ、クリップを調べる。2ch経路への切替は別試行とし、選択経路と説明、承認済み係数で生成した独立基準との波形差を記録する。 |

アナログ収録には耳やスピーカーを接続せず、初回は低出力で負荷と入力レンジを確認する。負荷の実測抵抗・電力定格、gain/volume、フィルター・DSP、給電、アナライザー/プローブの版・校正日・帯域・レンジ、サンプリング周波数、アンチエイリアス設定、FFT窓・積分帯域・基準レベル、トリガー位置と欠測を記録する。校正不足・取得器のオーバーレンジ・欠測は測定系を修正して別セッションで再試験する。

### QLT-08の倍率別試行

試験担当者は各倍率で、変調器単体、DSD→PCMデコード、負荷付きDAC出力を個別に試験する。各試行で1 kHz −6 dBFS、無音、DC、最大入力と30分連続を扱い、信号と経路を記録する。

| 試行 | 予定取得点と比較・測定方法 | 未確保の条件 |
| --- | --- | --- |
| PCM→DSD変調器単体 | 変調器直後、USB転送用に整形する前の1-bit列を保存する。レート・bit順・ch配置を記録し、独立解析器で列を展開してスペクトルを求める。帯域内の指標を入力PCMおよび独立基準と比較し、帯域外電力を記録する。連続運転時の非有限値・発散・バッファ不足を数える。 | 各倍率の変調器出力を取り出す手段、入力PCM試料、独立解析器。 |
| DSD→PCMデコード | 既知のDSD列を入力し、デコーダー直後のPCMを取得する。同じDSD列から独立デコーダーで生成したPCMと、レート・遅延を揃えて波形と帯域内の指標を比較する。連続運転時の非有限値・発散・バッファ不足を数える。 | 各倍率の既知DSD試料、デコード直後のPCMを取り出す手段、独立デコーダー。 |
| 負荷付きDAC出力 | 負荷を接続したDACのアナログ端子で電圧を収録する。NativeとDoPを個別に試し、帯域内の指標と帯域外電力を負荷・経路ごとに記録する。 | 各倍率のDSD試料と成立する出力経路、操作承認、広帯域測定系、校正済み負荷。 |

帯域内は20 Hz〜20 kHzの振幅偏差±0.1 dB以内、THD+N −80 dB以下を確認し、測定帯域と基準レベルを残す。変調器とデコーダーは非有限値・発散・バッファ不足が各0件であることを確認する。変調器単体とDAC出力では、20〜100 kHzおよび100 kHz超の積分電力を別々に記録し、それぞれの測定上限を明記する。

| 倍率 | PCM→DSD変調器単体 | DSD→PCMデコード | 負荷付きDAC出力 |
| --- | --- | --- | --- |
| DSD64 | BLOCKED | BLOCKED | BLOCKED |
| DSD128 | BLOCKED | BLOCKED | BLOCKED |
| DSD256 | BLOCKED | BLOCKED | BLOCKED |

倍率と試行の組合せごとに、既存の証跡テンプレートで個別の`session_test_id`を発行する。`settings`に倍率・試行・予定取得点、`outcome`と`reason`に状態と理由を記録する。取得前は`capture_point`と`result`を`null`とし、実施後は取得点と結果を該当する試行の証跡に記録する。[dsd-analog.json](dsd-analog.json)は負荷付きDAC出力の準備記録。

## 予算案と再開条件

| 構成 | 積算の基準 | 確保前に確認する項目 |
| --- | --- | --- |
| USB取得 | USB 2.0 High-Speedの候補 Beagle USB 480のメーカー表示は **1,295米ドル**（2026-11-20参照、本体のみ）。借用・施設利用の見積もりと比較する。 [価格・仕様](https://www.totalphase.com/protocols/usb/)・[ユーザーマニュアル](https://www.totalphase.com/support/articles/200472426-beagle-protocol-analyzer-user-manual/) | 実際のバス速度、raw isochronous payloadの継続取得、Type-C配線・給電と記録容量。SuperSpeedなら対応器を別途見積もる。 |
| DSDアナログと過渡 | 広帯域2chアナライザー、差動プローブ付きオシロスコープ、校正源、負荷・治具を含む施設利用または借用。APx555 B Seriesは帯域候補。利用料・校正費は未見積もり。 [メーカー資料](https://www.ap.com/fileadmin-ap/technical-library/APx555_B_Series_Datasheet.pdf) | 100 kHz超の有効上限、選択したレンジでの残留ノイズ、負荷別の電圧上限、過渡の収録・書出し。 |
| 5.1ch | 成立する6ch出力機器の借用と同時入力の施設利用。APx585 B Seriesは8同時アナログ入力の候補。利用料・校正費は未見積もり。 [メーカー資料](https://www.ap.com/fileadmin-ap/technical-library/APx58x_B_Series_Datasheet.pdf) | 6chの出力形式と配置、6ch同時入力・校正を含む構成、負荷・ケーブル・収録費。 |

見積もりは機材の借用料または施設単価×使用時間に、校正、負荷・ケーブル、データ書出し、送料・税を加えて比較する。使用時間は事前校正、各測定、再試験で積算する。総額は見積もり待ち。発注・契約の実績はない。

1. 所有者からS25、M21、追加S20の対象ADBシリアルと、端末ごとの操作承認を得る。シリアルはローカルで照合し、共有台帳には仮名のセッションIDを載せる。
2. 機器・測定器を確保し、許可済み端末でOS/API、ABI、build fingerprintのSHA-256、端末とDACのfirmwareを取得する。USB属性は [既存の取得契約](../../contracts/usb-devices-v1.md) に従い、OSレポートと再生時の観測を分ける。USB拒否・抜去は停止と解放を確認し、再装着後は新しいセッションとして明示許可からやり直す。
3. [音声試験の手順](../../../tests/audio/README.md)でfixtureを再生成し、SHA-256を台帳に照合する。CC0-1.0の48 kHz PCMの長さ・bit深度・ch配置はmanifestの値を使う。別レート、DSDの既知列・長時間試料、6chの別レートに必要な信号は追加確保し、生成器/取得元・SHA-256・形式・長さ・用途条件を利用前に記録する。
4. 取得時に対象commit、fixtureと設定、機器版、時刻、出力先、取得点と取得値を固定する。[証跡テンプレート](../../../tests/audio/result-template.json)からセッションごとに作成し、既存validatorで検査する。開始後に失敗した試行はエラーと取得可否を記録し、再実施時は別IDを使う。未確保はBLOCKED、未着手はNOT_RUN、いずれも結果値はnullとする。数値のPASS/FAILには原データまたは報告書とそのSHA-256を添える。平文のfingerprint・機器シリアル・認証情報・私用URIは共有資料から除く。

## 要求と後続作業

| 対象 | 現状と引き継ぎ |
| --- | --- |
| FR-PLT-01 / NFR-COMP-01 → TEST-COMP-01、FR-DEV-02 → TEST-DEVICE-01 | NOT_RUN。#152で主要実機2台の互換性と機器属性を確定。#146/#147は同じ実機・構成を使い性能・長時間ケースを測定する。#151の同一鍵更新には対象端末と署名検証条件が必要。 |
| NFR-AUD-02 / FR-AUD-04/05 → TEST-AUD-02 / TEST-FORMAT-02 | BLOCKED。#145で独立比較・外部列取得・安全切替、#148で帯域外と過渡の安全判定、#152で方式別の成立例と6chを記録する。 |
| FR-SRC-11 → TEST-RESEARCH-01 のDAC制約 | BLOCKED。機器・経路が未確保。実サービスの操作と用途別許諾の確認はNOT_RUN。技術成立、用途別許諾、移植可能性、依存ライセンスはそれぞれ未確認で、#150と各担当Issueの証跡を参照して判断する。 |

既存fixtureの音声比較はホストで実施できる。validatorの成功は記録形式の確認に限る。実機試行の実施、設備の校正、測定値の妥当性、承認条件の成立はセッションごとの証跡で確認する。

## Issueの受入条件

| 条件 | 本記録での判定と根拠 |
| --- | --- |
| 型番からNative/DoP/384 kHz/6chを保証しない | PASS（資料確認）。公称値は出典とともに示し、成立は組合せごとの確認事項とした。各方式はBLOCKED。 |
| 主要2端末をS20やエミュレーターで代替しない | PASS（資料確認）。S25とM21の実機ケースを維持した。動作確認はNOT_RUN。 |
| 未確保方式をBLOCKEDで残す | PASS（台帳確認）。デジタル列、DSD、5.1chは結果値なしのBLOCKEDを保存した。 |
| 関連要求・試験に結果とcommitを記録する | PASS（台帳確認）。[要求・試験台帳](../../traceability/results.json)にホストの検査範囲と、測定・用途確認の未達を記録する。 |
