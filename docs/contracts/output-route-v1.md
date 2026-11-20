# Android 出力経路の観測 v1

対象は [Issue #42](https://github.com/4rtofl/Audipp/issues/42) の FR-DEV-01、FR-AUD-01、NFR-AUD-01 に対応する経路観測部分です。

- `PlaybackService.outputRoute` はプロセス内の `StateFlow` です。`connectedOutputs` は AudioManager の接続一覧と機器が申告した対応レート・チャンネル数・エンコーディングを `OS_REPORTED` として保持します。空の能力配列は Android API の返値をそのまま保持します。ポート ID の用途は現在の接続の識別に限ります。
- `observedOutputs` は Media3 が実際に使う AudioTrack の再生中に観測した出力の一覧です。API 23〜35 は `getRoutedDevice()` の結果、API 36 以降は `getRoutedDevices()` の全結果を保持します。複数経路のときは単一の機器名を `UNKNOWN` とします。Track が公開されない、再生していない、または API から取得できない場合も観測値は `UNKNOWN` です。接続一覧、機器の対応レート、出力の指定値は観測値の補完に使いません。
- 初めて実経路を観測したとき、再生中の経路が変わったとき、再生中の経路を取得できなくなったとき、接続一覧・能力が変わったときに経路 revision を増やします。通常の一時停止と停止では現在の観測を解除し、再開時は同じ経路かを確認します。重複通知で revision は増えません。
- revision が変わると古い `ExecutionPlan` を失効させ、解決中の要求は取り消します。開始済みの標準再生には観測による停止指示を出さず、位置・再生状態の更新を続けます。取り直した再生要求は最新 revision で解決します。外部検証は共通契約の機器・経路・revision・形式の照合が必要です。実際の出力形式・DAC 実測・Bit-perfect は本監視が確定する項目に含みません。
- デバイス名とポート ID は Service のプロセス内メモリで扱います。メディアセッションの extras、保存データやログに追加しません。監視は Service の破棄時に解除します。

Media3 1.11.1 の既定の AudioTrack 出力処理に委譲し、公開 API で取得した Track だけを監視する構成を採用しました。[Media3 1.11.1 の AudioTrack 出力](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java) · [Android AudioTrack API](https://developer.android.com/reference/android/media/AudioTrack#getRoutedDevice())

後続の出力設定・外部検証・プロファイルは、接続一覧とは別に観測経路と revision を参照できます。試験結果と端末で未検証の範囲は[追跡台帳](../traceability/results.json)に記載します。
