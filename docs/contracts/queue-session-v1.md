# キューを使う連続再生と Session の結合

[#83](https://github.com/4rtofl/Audipp/issues/83) は、`PlaybackService` を保存済みのキュー・[次曲探索](queue-orchestrator.md)・[チェックポイント](playback-checkpoint.md)につなぐ。FR-PLY-01〜04、NFR-REL-03、DD-PLY-03、DD-TEST-01 と TEST-PLY-01/02、TEST-RESTORE-01 のうち、Service とキューの結合を扱う。ギャップレス（次曲の先行 decode、encoder delay / padding の補償）は [#82](https://github.com/4rtofl/Audipp/issues/82) の範囲で、ここでは作らない。

## 再生の要求とキューの revision

`PlaybackConnection.playQueue(controller, queueId, entryId, queueRevision)` で、保存済みキューの項目を再生する。Service は DB からキューを読み、渡された revision がコミット済みの revision と一致し、項目がそのキューにある場合だけ開始する。一致しなければ `ERROR_INVALID_STATE` と `STALE_QUEUE_REVISION` を返し、再生中の曲には手を触れない。選択版の行がなければ `SOURCE_MISSING` を返す。

既存の `playLocal(controller, entryId, variant)` はそのまま残す。キューを持たない 1 曲の再生で、自然終了後は従来どおり `ADVANCING` のまま止まり、チェックポイントを保存しない。

`PlaybackSnapshot.queue` は、現在の再生が属するキューの ID と revision を示す。キューの再生では、曲の開始時、編集の反映時、停止後に値が入る。`playLocal` では `null` になる。

## 自然終了で次の項目へ進む

Player が `ENDED` になると、Service は同じ遷移のうちに一度だけ `QueueOrchestrator.advance(NATURAL_END)` を呼ぶ。探索ではキューの最新の順序・シャッフル・リピートを使う。結果の扱いは次のとおり。

| 結果 | Service の動作 |
| --- | --- |
| `Ready` | 開始の直前にキューの revision と項目をもう一度読み、一致すればその項目を開始する。一致しなければ停止して IDLE にする |
| `QueueEnded` | 停止して IDLE にする |
| `RequiresUserAction` / `TimedOut` | 該当の項目を現在項目にした ERROR を出し、先へ進まない |
| `QueueChanged` / `NoPlayableItem` | 停止して IDLE にする。古い候補は開始しない |
| `Cancelled` | 何もしない（Stop・別曲で遷移がすでに変わっている） |

次の項目の開始は、旧項目を `stop` してから新しい項目を準備する。そのため、切り替えのあいだ一時的に IDLE が出る。#82 は、この切り替え（`PlaybackService.advanceQueue`）を先行準備に置き換える。

## 編集・削除・停止

Service は現在のキューの revision を Room の Flow で監視する。revision が変わると、キューを読み直して次のことを行う。

- 現在の項目が残っていれば、`PlaybackSnapshot.queue` を新しい revision に更新し、その revision でチェックポイントを保存し直す
- 現在の項目が消えていれば、停止する。DAO が確認するのは保存済みチェックポイントの項目だけで、それが Player の現在項目より遅れることがあるためだ
- 明示停止の直前に取ったサンプルを保持している場合、その項目が残っていれば新しい revision で保存し直し、消えていれば破棄する

現在の項目を削除するときは、[キューの保存](queue-db-v2.md)の手順どおり、`StopRequired` を受けて Stop を送り、IDLE を確認してから `stoppedEntryId` を付けて再試行する。削除のあとに次の項目へは進まない。チェックポイントの書込みが `STALE_QUEUE` になった場合も、同じ読み直しを行う。

## チェックポイントの保存と復元

キューの再生では、次の時点で `PlaybackCheckpointJournal.saveConfirmed` に確定サンプルを渡す。

- 新しい項目の位置を初めて観測したとき
- 一時停止を観測したとき
- シークのあと、位置を観測したとき
- 明示停止の直前
- キューの編集を反映したとき

再生中の定期保存（5 秒ごと）は #62 の journal が行う。Service の終了時は保存しない。

Service の生成時に `restorePlaybackCheckpoint` を実行し、結果を IDLE の Snapshot として示す。このときの値は、現在項目、位置、キューの ID と revision である。復元だけでは再生を始めず、Player に項目も載せない。復元した状態で play を受けると、保存位置からその項目を開始する。復元中に別の要求が届いた場合は、復元の結果を捨てる。

## 実装提案

仕様にない次の詳細は、このPRでの実装案である。

- `PlaybackQueueRef(queueId, revision)` と `PlaybackSnapshot.queue`。`PLAY_QUEUE` の custom command と、引数 `queueId`・`entryId`（各 256 文字まで）・`queueRevision`（0 以上）
- 古い revision の拒否は `ErrorDomain.DATABASE` / `STALE_QUEUE_REVISION`（再試行可）とする。自然終了の探索中に revision が変わった場合（`QueueChanged`）は、探索をやり直さずに停止する。探索は短時間で、その間に編集が重なることはまれだと考えた
- 自動の次曲で再生できない項目は `UnplayableItemPolicy.ASK` で扱う。利用者の設定がまだないためで、[標準ローカル再生](local-playback-v1.md)の「自動スキップしない」に合わせた
- キューの最後まで再生したときは、チェックポイントを追加で保存しない。復元すると、最後に保存した位置（最後の曲の終わり近く）が戻る
- 復元した IDLE の状態は、ミニプレイヤーでは従来どおり Idle として表示される。復元した曲の表示は UI 側の後続の作業で扱う
- 再生主体が 1 つ（ローカル）だけなので、`PlaybackOwnerCoordinator` はまだ Service につないでいない。Provider やネットワークレンダラーを追加するときに接続する

## 試験

| 試験 | 環境 | 確認範囲 |
| --- | --- | --- |
| `PlaybackQueueServiceTest`（9 件） | Robolectric API 23 / 35、実 Room・MediaController・ContentProvider | 自然終了で次の項目、キューの末尾、古い revision・未知の項目の拒否、再生中の編集に沿った次曲、探索中の revision 変更による候補の破棄、現在の項目の削除と停止、一時停止の保存と Service の作り直し後の復元・再開、保存失敗（SQLite trigger）で直前のコミットを保持、次の項目での権限の喪失、連続した再生要求 |
| `QueuePlaybackDeviceTest`（7 件） | Android API 23 / 36 のエミュレーター。実 Service、MediaSession、アプリの Room、別プロセスの ContentProvider | 画面 OFF のまま次の項目が始まること、通知の一時停止・再生の action、現在の項目の削除、Service の作り直し後の復元・再開、保存失敗、次の項目での権限の喪失、連続した再生要求。いずれも Snapshot と Session の曲・位置・状態の一致を確かめる |

結果と対象の commit は[試験結果](../traceability/results.json)に記録する。エミュレーターでの成功は、音声が出力に届いたことを示さない。

計装試験の fixture は試験 APK の通常の ContentProvider で、SAF の picker や DocumentsProvider の永続的な許可は通らない。Service の作り直しは `stopService` と再接続で行い、プロセスの強制終了は試していない。物理端末、実際の通話・音声フォーカス・BT/USB の切断、実音声の出力、DAC は未実施である。
