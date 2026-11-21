# 同期push v1

[Issue #52](https://github.com/4rtoflow/Audipp/issues/52)の実装提案。FR-USE-03 / NFR-PRIV-02 → DD-CLOUD-01/02 → ADR-006 / R-04 → TEST-SYNC-01 のうち、サーバーのpushを扱う。

## HTTPとイベント

`POST /v1/sync/batches` は [業務API v1](backend-v1.md) のBearer認証と、`POST /v1/auth/session` で本人に登録された端末を使う。本文は非圧縮のUTF-8 JSONで最大1,048,576バイト。イベント数は1～100件。未知フィールド、重複JSONキー、末尾の別JSON値、型違いを拒否する。全イベントの形を検査してからDBトランザクションを開く。

```json
{
  "device_id": "0a4b0075-6a6b-413f-aff3-21a126579599",
  "events": [
    {
      "event_id": "4e940a46-b681-455e-a058-dcbbbe6d2af8",
      "entity_type": "setting",
      "entity_id": "theme",
      "operation": "upsert",
      "base_revision": 0,
      "history_epoch": 0,
      "payload": {"value": "DARK"}
    }
  ]
}
```

UUIDは正規形の小文字。revisionと世代は符号付き64bitの非負整数。新規エンティティの `base_revision` と初期の履歴世代は0。履歴以外の `history_epoch` は0。通常のエンティティは `upsert` または `delete` を使い、`delete` のpayloadは `null`。同意と履歴はpushでは `upsert` のみ。

| entity_type | entity_id | upsert payload |
|---|---|---|
| `consent` | `cloud_sync` または `history_sync` | `{"version":1,"granted":true}`。撤回では `false` |
| `setting` | `theme` / `visualizer_enabled` / `visualizer_fps` | `{"value":…}`。順に `SYSTEM` / `DARK` / `LIGHT`、真偽値、15 / 30 / 60 |
| `playlist` | UUID | `name`、順序付き `tracks` |
| `queue` | UUID | 順序付き `tracks` |
| `profile` / `audio_preset` | UUID | `name`。機器属性・DSP設定の詳細は対応機能と結合する際に定義する |
| `history` | 履歴を生成した時点の固定UUID | `track`、末尾ZのUTC `started_at`、正整数 `listened_ms` |

trackは `{"track_id":"内部UUID","source":"local"}`。Providerによる音源は、利用者がクラウド同期に同意していても `source: "provider"` を403とする。Provider用途別の許諾判定を接続するまでは、Android側が送信準備時に音源を分類し、未許可のデータをoutboxから除外する。pushが確認できる分類は送信側の申告。個別のProvider許諾や出所を判定する機能は #115 との結合に残る。

payloadの項目は表の形に限る。元音源、URI、Cookie、tokenなどを格納するフィールドは設けない。自由入力の名前では、空白のみの値、制御文字を含む値、不正Unicodeを拒否する。URIと認証値はASCIIの英字の大小を区別せず、次の形を拒否する。

- 名前の途中も含め、直前に `[a-z0-9+.-]` がない `[a-z][a-z0-9+.-]*://`、または単語境界（`\b`）から始まる `content:/`、`file:/`、`data:[a-z0-9!#$&^_.+-]+/`。
- 名前全体が `Bearer` または `Basic`、1個以上の半角スペース（U+0020）、認証トークンの順に並ぶ形。名前の前後に半角スペースがあっても拒否する。トークンは `[a-z0-9._~+/-]` の8文字以上と、末尾の0個以上の `=` で構成し、数字・記号のいずれかを1文字以上含む。

自由入力に任意の秘密文字列が含まれるかの判定はできないため、送信側も名称フィールドへ資格情報や音源参照を転記しない。レスポンスにはイベントIDとrevisionまたは固定エラーコードだけを含め、`Cache-Control: no-store` を付ける。

同意は本人・用途ごとに初期値が未許可で、同意だけのバッチに分ける。更新・撤回は同意の現在revisionを使う。履歴以外の新規イベントには `cloud_sync`、履歴には両同意が必要。撤回後は新規の対象イベントを拒否し、既処理イベントの再送には保存済みの受付結果だけを返す。端末の同意画面・実outboxは #115 で接続する。

| HTTP | 意味と復旧 |
|---|---|
| 200 | commit確定。入力順の `{"results":[{"event_id":"…","server_revision":123}]}` |
| 400 | `invalid_request`。スキーマ、本文またはqueryを修正 |
| 401 | `unauthorized`。認証を回復する |
| 403 | `forbidden`。端末登録、アカウント、同意、用途を確認する |
| 409 | `event_id_conflict`、`revision_conflict`、`history_epoch_conflict`、`history_deleted`。現行状態を確認するまで新規適用なし |
| 410 | pullのcursor失効。#80で完全同期を実装する。pushはcursorを受け付けず400を返す |
| 413 | `payload_too_large`。100件または1 MiBを超過。正しいイベントを分けて送る |
| 429 | `try_later`。同一プロセスのpush受付8枠が使用中。 `Retry-After: 1` |
| 503 | `temporarily_unavailable`。DB・鍵取得の一時障害。 `Retry-After: 1` |

429/503やcommit到達不明の通信断では、待機後に同じ端末から同じID・同じ内容を再送する。403/409を認証更新だけで繰り返さない。push本文の受信を5秒に制限する。同時受付枠と受信期限はプロセス内の資源制限で、配備時のレート制限や性能値は別途検証する。

## 保存、削除世代、引き継ぎ

`V002__sync_push.sql` は `sync_events` の主キーを `(user_id, event_id)` とし、端末所有者との複合外部キーを持つ。端末IDとキー順を正規化したイベントJSONのSHA-256で再送を比較する。同一イベントの結果は同じrevisionを返し、バッチ内で複数回現れる場合も位置ごとに返す。エンティティの現在revisionは、本人・型・IDで絞った最新イベントの `server_revision`。設定はフィールドをエンティティとして更新する。revisionはV001の共通bigint sequenceを使い、欠番を許容する。

本人の行ロックを取得し、適用順に競合を検査する。途中の拒否・DB障害・commit前の取消では新規変更が全件rollbackされる。V002は既存のV001のデータとchecksumを維持する。migrationが失敗した場合は原因を直してから再起動する。

端末は、プレイリスト、キュー、設定などの同じエンティティへの未送信の変更を1イベントにまとめる。別イベントとして送る場合は、前のイベントの確定 `server_revision` を受け取ってから、次の未送信イベントの `base_revision` をその値に設定する。この規則は #115 のoutbox実装に引き継ぐ。

履歴は生成時の `history_epoch` と固定 `entity_id` を持ち、端末は再送時にも両方を保持する。履歴の削除処理はpushと同じ本人行ロックを使い、全削除では `history_epochs` を進め、個別削除では `history_tombstones` に対象IDを保存する。対象の履歴payloadを同じトランザクションで消去し、受付情報とダイジェストを残す。旧世代の送信はイベントIDを変えても409。固定履歴IDの重複も409で、削除済みイベントの正確な再送は受付結果のみ返す。

#80 は `sync_events`、`history_epochs`、`history_tombstones` から、世代や削除を含めて本人のsnapshotを作る。履歴の受付情報だけの行は復元対象から外す。#115 は旧履歴の世代や固定履歴IDを書き換えず、完全同期後に未送信データを再検査する。両方を書き換えたデータや、サーバー未観測の履歴を新規作成と区別するには、端末側の生成・取込の規則が必要。#131 は本人再認証、削除の公開API、派生値、端末側取消、DB・バックアップの失効と復旧を接続する。pushのDB writerは呼び出し元で再認証済みの本人IDを確定する契約とする。

## 検証と追跡

| 要求 / 試験 | ローカルで確認した範囲 |
|---|---|
| DD-CLOUD-01 / TEST-SYNC-01 | 100/101件、1 MiBと1バイト超過、長さ不明の本文、途中不正、200/400/401/403/409/413/429/503 |
| DD-CLOUD-02 / TEST-SYNC-01 | 同一イベント再送と異内容再利用、2台の競合、途中の409・DB timeout・取消によるrollback、同じIDでの復旧、削除世代と個別tombstone |
| FR-USE-03 / NFR-PRIV-02 | 一時PostgreSQLで登録端末と本人の分離、同意の適用・撤回、用途未許可のProvider拒否、URI・秘密項目の拒否、履歴payload消去 |
| R-04 / ADR-006 | 一時HTTPSリスナーの認証付きpush、V001からの移行とV002失敗後の再試行 |

検証結果と対象commitは [試験結果台帳](../traceability/results.json) に記録する。pullの410と完全同期、Androidからの送信、Providerの実許諾、運用環境での配備・失効・復旧は未実施。実機とDACでの試験はサーバーのpush契約には不適用。
