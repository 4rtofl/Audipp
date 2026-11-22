# Android 出力経路の観測 v1

対象は [Issue #42](https://github.com/4rtofl/Audipp/issues/42) の FR-DEV-01、FR-AUD-01、NFR-AUD-01 に対応する経路観測部分です。

- `PlaybackService.outputRoute` はプロセス内の `StateFlow` です。`connectedOutputs` は AudioManager の接続一覧と機器が申告した対応レート・チャンネル数・エンコーディングを `OS_REPORTED` として保持します。空の能力配列は Android API の返値をそのまま保持します。ポート ID の用途は現在の接続の識別に限ります。
- `observedOutputs` は Media3 が実際に使う AudioTrack の再生中に観測した出力の一覧です。API 23〜35 は `getRoutedDevice()` の結果、API 36 以降は `getRoutedDevices()` の全結果を保持します。複数経路のときは単一の機器名を `UNKNOWN` とします。Track が公開されない、再生していない、または API から取得できない場合も観測値は `UNKNOWN` です。接続一覧、機器の対応レート、出力の指定値は観測値の補完に使いません。
- 最初に観測した実経路を基準値とし、その観測では経路 revision を増やしません。以後は、再生中の実経路の変化（取得不能や取得の復旧を含む）や、接続一覧・能力の変化で revision を増やします。通常の一時停止と停止では現在の観測を解除し、再開時に同じ経路を観測した場合は revision を維持します。重複通知で revision は増えません。
- revision が変わると古い `ExecutionPlan` を失効させます。ローカル標準再生の解決中に revision が変わった場合は、その解決結果を破棄し、同じ要求を最新 revision で1回だけ自動で再解決します。再解決中も revision が変わった場合は、呼出元に再試行可能な `AUDIO_ROUTE` エラーを返します。再生を一時停止する条件は下記の安全音量契約に従います。外部検証は共通契約の機器・経路・revision・形式の照合が必要です。実際の出力形式・DAC 実測・Bit-perfect は本監視が確定する項目に含みません。
- デバイス名とポート ID は Service のプロセス内メモリで扱います。メディアセッションの extras、保存データやログに追加しません。監視は Service の破棄時に解除します。

Media3 1.11.1 の既定の AudioTrack 出力処理に委譲し、公開 API で取得した Track だけを監視する構成を採用しました。[Media3 1.11.1 の AudioTrack 出力](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java) · [Android AudioTrack API](https://developer.android.com/reference/android/media/AudioTrack#getRoutedDevice())

後続の出力設定・外部検証・プロファイルは、接続一覧とは別に観測経路と revision を参照できます。試験結果と端末で未検証の範囲は[追跡台帳](../traceability/results.json)に記載します。

## 経路変更と安全音量（Issue #70）

**決定日 2026-11-22、決定者 リポジトリ所有者。** 使用中の出力の切断・再接続、実経路の変更、実経路の観測喪失で一時停止します。未使用の機器の増減後も同じ実経路を観測できる間は、再生を続けます。初回に実経路が不明なら、出力を音量ゼロで開始して観測します。経路を特定できない場合や USB DAC の音量を読めない場合は確認を待ち、安全を確認してから音を出します。

- 実装提案として、Media3 の出力の開始・再開時に毎回デジタル出力をゼロにし、観測済みの経路と照合してから出力を開きます。経路は現在の接続のポート ID と種別で照合します。経路が変わったときは出力をゼロにして一時停止し、保留中の音量復元を取り消します。スピーカーに切り替わった場合も利用者の確認を待ちます。
- `PlaybackService.outputSafety` は `Observing`、`Ready`、理由と確認 ID を持つ `ConfirmationRequired` を公開します。確認待ちの理由・確認 ID・経路の種別と、プリセット由来の上限・物理ゲインのメモを、自アプリ向けの既存 MediaSession extras に追加します。経路の機器名・ポート ID はこれまでどおり Service 内で扱います。
- 共通のミニプレイヤーは確認が必要な理由とボタンを表示します。`CONFIRM_OUTPUT` は最新の確認 ID を検証し、成功したら再生を要求します。確認 ID は新しい確認ごとに発行し、Service を作り直した場合も以前の ID は使えません。再開時も無音で実経路を再観測し、確認した経路と一致した場合に出力を開きます。このボタンからは保存済みのデジタル音量を復元しません。確認待ちの間は、通常の再生コマンドを受け付けません。
- `RETRY_OUTPUT_OBSERVATION` は最新の確認 ID で無音の観測をやり直します。初回に特定できなかった非 USB 経路を識別できたときは `Ready` に移り、一時停止した状態で通常の再生操作を待ちます。別経路への切替や USB DAC の場合は引き続き確認を求めます。最初の観測が得られない間は、実装上の待ち時間として1秒を目安に無音で再生してから一時停止します。この間、再生位置が進む場合があります。
- 経路切替をアプリ側で処理できるのは Android の切断通知や実経路の観測以降です。通知より前にハードウェアへ渡った音声や DAC の実音量は、実機で別に検証します。出力の音量・安全確認の状態はプロセス内に保持します。
