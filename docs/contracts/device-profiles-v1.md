# 機器プロファイルと Registry v1（Issue #44）

DD-DB-01 / DD-DEV-03 の端末内保存と共有コピーの実装。単一 `:app` 内の `DeviceProfileStore.open(context)` でアプリ専用の `device-profiles.db` を開く。呼出側が Store を所有し、使用後に閉じる。機器とプロファイルにはライブラリへの参照がないため DB を分けた。#181 の `library.db` の移行と独立して適用できる。

## 保存と読込

| テーブル | 主キーと参照 |
| --- | --- |
| `device_components` | `component_id`。初回登録時に UUID を生成し、編集では同じ ID を使う。VID/PID は NULL 可で、同型機も個別登録できる。 |
| `device_evidence` | `component_id, ordinal`。機器に属する複数の根拠を入力順に保持する。 |
| `device_profiles` | `profile_id`。表示名と作成日時（UTC epoch ms）を保持する。 |
| `profile_components` | `profile_id, component_id, role`。構成順を保持し、機器 ID に索引を付ける。 |
| `audio_presets` | `profile_id, preset_id`。プリセット順、revision、設定文書を保持する。 |

ID と種別は TEXT、時刻・revision・VID/PID・順序は INTEGER。根拠・音声設定・音量は `ModelJson` の `schemaVersion: 1` 付き TEXT として保存する。初期値・通常設定・安全上限の音量を分け、それぞれデジタルゲイン、Android 音量ステップ、機器固有の音量と単位を NULL 可で保持する。物理ゲインは手動メモ。PCM の要求値には製品上限を適用し、能力の報告値は原値を保持する。ストアの読込で音量や出力を変更する処理は走らない。

保存は機器と根拠、またはプロファイルと構成・プリセットをそれぞれ一つのトランザクションで全件置換する。例外・コミット前の取消では変更をロールバックし、同じ ID で再試行できる。プロファイルに使用中の機器は削除できない。revision は呼出側の値を保存する。端末間の競合解決は #130 の範囲。破壊的移行は無効で、次回の変更は [Room schema v1](../../app/schemas/com.audipp.data.device.DeviceProfileDatabase/1.json) を移行元とする。

## 根拠と共有コピー

Registry はメーカー公称、本人申告、OS 申告、測定を区別する。#16 の `Measured` で取得元・記録日時・試験証跡 ID・不明値を保存し、測定にはアプリ版、Android API、設定、手順、任意の機器・ファームウェア情報を添える。

`sharedCopy(profileId)` は表示確認に使える構造化データを返す。構成上の役割、機器種別、VID/PID、プリセットの音声処理設定、根拠の区分と報告値、測定時のアプリ版・Android API・音声処理設定を含む。名称を含む自由記述、ローカル ID、シリアルハッシュ、URI、音量、物理ゲインメモ、証跡 ID は共有用の型から除外した。送信時に表示する機器名の明示選択と公開前確認は共有 UI の範囲。コピーは他者の報告として扱い、再生経路の `BitPerfectVerification` への昇格には使わない。

## 検証範囲と後続作業

TEST-DEVICE-01 は設定・根拠の再読込、同型機の個別登録、保存失敗・取消・再試行を検査する。TEST-SHARE-01 はローカル共有コピーの項目と機微情報の除外を検査する。実行結果と対象コミットは [追跡台帳](../traceability/results.json) に記録する。

#42 の出力経路、#97 の接続候補・優先順位・安全適用、DSP やドライバ固有の実行設定との接続は後続。機器ごとの適用可否や実音量は実機での検証が必要。共有 UI、認可・送信・撤回等のサーバー連携、個人用 export/import は今回の試験対象外。DSP 係数の計算や音声処理は追加していない。
