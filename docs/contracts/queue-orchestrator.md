# キューの再生順と次曲探索

[#60](https://github.com/4rtofl/Audipp/issues/60) は FR-PLY-01/04/07、DD-QUEUE-01/02 と TEST-PLY-01・TEST-QUEUE-01 の次曲探索を扱う。

- `QueueDao.setPlaybackOrder` は期待 revision を照合し、シャッフル設定・保存 seed・リピート設定とチェックポイントの revision をまとめて更新する。既存の現在項目と位置を維持する。
- `effectivePlaybackOrder` はシャッフル時に項目 ID で整列した集合を保存 seed でシャッフルする。同じ項目集合と seed から同じ順序が得られる。シャッフルを無効にするとDBの順序を使う。
- `advance` は開始時の revision と候補順を固定する。全曲リピートでも最大一巡で、1曲リピートの自然終了は現在項目だけを候補にする。明示的な次曲操作は1曲リピートを無視する。
- `ASK` は最初の不可項目で利用者の操作を待つ。`SKIP` は理由と項目 ID を残して次の候補に進む。タイムアウト・取消・古い遷移・revision変更はそこで探索を終える。
- `Ready` を受け取った再生側は、開始直前にキュー revision と経路・遷移を再確認する。再生主体の停止確認と切替は [#61](https://github.com/4rtofl/Audipp/issues/61)、Serviceの連続再生との結合は [#83](https://github.com/4rtofl/Audipp/issues/83) で扱う（[キューを使う連続再生](queue-session-v1.md)）。

RoomとSourceResolverを接続した `QueueOrchestratorTest` の9件がRobolectric API 35で成功した。全曲不可時の一巡終了、探索中の並べ替え、自然終了と明示的な次曲の違い、ASK/SKIP、タイムアウト・取消、保存 seed とチェックポイントの更新を確認した。音源解決後の revision 照会中に遷移IDが変わると、旧候補の確認要求を返さず探索を取り消すことも確認した。実機の連続再生と外部再生主体の動作確認は未実施。
