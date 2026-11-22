# アートワーク v1（Issue #59）

対象: [Issue #59](https://github.com/4rtoflow/Audipp/issues/59)。FR-SRC-05 / FR-SRC-06 → DD-SRC-04 → TEST-LIB-02 / TEST-META-01 のうち、出典別の保持、取込み上限、差替え・元に戻す・失効、表示キャッシュ。以下の型名・列名・縮小規則・失効の扱いは本PRの実装案で、製品条件の変更ではない。外部画像の取得（MusicBrainz / Cover Art Archive の通信）は #57、差替えの画面は #158、ライブラリと Now Playing の表示は #84 / #85 が担当する。

## 出典と許可

| 出典（`ArtworkOrigin`） | 保持の場所 | 表示許可 | 再配布許可 |
|---|---|---|---|
| `EMBEDDED` | 音源ファイル内。Variant の `local_artwork_ref`（`embedded:<offset>:<length>:<mime>`）と Source の `local_uri` から都度読む。画像は複製しない | 常に `ALLOWED` | 常に `UNREVIEWED` |
| `USER_SELECTED` | 利用者が選んだ文書の URI を `artwork_overrides` に保存 | 常に `ALLOWED` | 常に `UNREVIEWED` |
| `EXTERNAL` | 取得済みで端末内に保存された画像の URI と `provider_key`。取得は #57 | 出典ごとに保存（既定 `UNREVIEWED`） | 出典ごとに保存（既定 `UNREVIEWED`） |

表示許可と再配布許可は別の列・別のプロパティで、表示許可を `ALLOWED` にしても再配布許可は変わらない。表示許可が `ALLOWED` でない外部画像は、開く前に `DISPLAY_NOT_ALLOWED` で拒否する（`DENIED` と `UNREVIEWED` を区別せず表示しない）。許可の根拠・期限は #33 の `ProviderPolicy` が所有する。本層は出典ごとの結果の状態だけを持つ。

同期・共有・エクスポートの payload に画像を載せてよいのは `ArtworkSource.mayAttachToSharedPayload`（再配布が `ALLOWED`）の出典だけ。埋込と本人指定の画像は常に false。現在の同期 push（[sync-push-v1](sync-push-v1.md)）にアートワークのエンティティはなく、`ArtworkOverrideEntity` はシリアライズ対象ではない。画像本体と URI を同期・ログへ出さない。出典の `toString` は URI を含めない。payload を組み立てる後続の担当は、画像を載せる前に必ず上記の判定を通す。

## 保存（Room v7）

`LibraryDatabase` を v6 → v7 に上げ、`artwork_overrides` を AutoMigration で追加した。**スキーマ版を上げたので、他の Room 変更と並行する場合は版の衝突に注意する。** 主キーは `variant_id`（`track_variants` へのFKで `ON DELETE CASCADE`。プレイリスト・履歴と同じく、Variant を削除すると差替えも消える）。列は `origin`、`location`、`provider_key`、`display_permission`、`redistribution_permission`、`updated_at`。行がなければ元の埋込画像を使う。「元に戻す」は行の削除。失効は保存せず、`load` のたびに読めるかで決める。

アルバム単位の代表画像の選び方は #84 が決める。本層は Variant 単位で、アルバムの画像は代表 Variant の `load` を呼ぶ。

## 取込み上限

| 項目 | 値 | 判定 |
|---|---|---|
| 取込み容量 | 20 MiB | 埋込は宣言された長さで開く前に、それ以外は 20 MiB + 1 バイトまでの読取りで `TOO_LARGE` |
| 画素数 | 40 MP（`幅 × 高さ`） | ヘッダー寸法で判定し、超過は `TOO_MANY_PIXELS`。ちょうど 40 MP は受け入れる |
| 展開後の長辺 | 2048 px 以下 | 2 の冪の `inSampleSize` で縮小する |
| 表示キャッシュ | 64 MiB | `Bitmap.allocationByteCount` の LRU |

検査は読取り境界で1回だけ行う。(1) 表示許可 → (2) 容量 → (3) 先頭バイトで JPEG / PNG / GIF / WebP を判定（不明は `UNSUPPORTED_TYPE`）→ (4) 宣言された種別（埋込の mime、`ContentResolver.getType`）と食い違えば `TYPE_MISMATCH`（`application/octet-stream` と未宣言は照合しない）→ (5) `inJustDecodeBounds` で寸法を取得し、取れなければ `CORRUPT`、上限超過は拒否 → (6) 縮小率を決めてデコード、失敗は `CORRUPT`。埋込の範囲が実ファイルより長い・短い場合も `CORRUPT`。読み取れない（権限喪失・削除・IO エラー）は拒否ではなく `Unreadable` で、失効の判定に使う。

縮小率は、長辺が 2048 px 以下になる最小の2の冪から、さらに要求した表示辺（`targetEdgePx`）を下回らない範囲で大きくする。2 の冪なので 2048 px を少し超える画像は約半分（例: 4100 px → 1025 px）になり、2048 px ちょうどまで拡大縮小はしない。メモリは最大でも 2048 × 2048 × 4 バイト = 16 MiB。画像全体のバイト列（最大 20 MiB）を読んだ後でデコードする。GIF は先頭フレームだけ。切れた JPEG などで Android が部分的に画像を返す場合は破損として検出しない。

IO とデコードは `Dispatchers.IO` で行い、読込（最大 20 MiB のバッファ）とデコードの同時実行数を `Semaphore` で 2 に制限する（実装提案。#84 のグリッドなどで多数を同時に要求しても、保持するバッファは最大 2 枚）。キャッシュのヒットは制限の外で返す。読取り後とデコード前にキャンセルを確認する。デコード中の中断は保証しない。

## 表示側の入口（#85 / #84 / #158）

```kotlin
val provider = (context.applicationContext as AudippApplication).artworkProvider

// 表示（Main 以外から呼んでよい suspend）。targetEdgePx は実際に描く辺の px。
when (val art = provider.load(variantId, targetEdgePx = 512)) {
    is Artwork.Image -> draw(art.bitmap, art.origin)           // Bitmap は共有キャッシュの所有物。recycle しない
    is Artwork.Placeholder -> drawPlaceholder(art.reason, art.rejection)
}

// 差替え（#158）。検査に失敗したら何も保存しない
provider.replace(variantId, ArtworkSource.UserSelected(pickedUri), nowEpochMs)   // Applied / Rejected(reason) / Unreadable
provider.revert(variantId)                                                        // 元の埋込画像へ戻す。差替えがなければ false
```

- `load` は差替えがあればそれを、なければ埋込画像を返す。キャッシュは `targetEdgePx` ごとで、ヒットすれば画像を読み直さない。呼出側は同じ描画サイズに同じ値を渡す（バケット化は表示側の責務）。
- `Placeholder` の理由: `NO_ARTWORK`（画像なし）、`EXPIRED`（差替え画像が読めなくなった）、`UNAVAILABLE`（埋込元の音源が読めない）、`DISPLAY_NOT_ALLOWED`、`REJECTED`（`rejection` に理由）。
- 差替え画像が読めないときは、その都度 `EXPIRED` の placeholder を返し、何も保存しない。SD カードの一時的な取り外しなどで読めるようになれば、次の `load` で表示される。元の埋込画像へは自動では戻さず、利用者が差し替え直すか `revert` する。
- 表示側は `Bitmap` を保持せず、`load` の結果をそのまま描画する。Compose では `produceState` 等で呼び、取消は協調キャンセルに任せる。

## 検証と未実施

| 検査 | 試験 |
|---|---|
| 容量・画素・長辺の上限、縮小後サイズ、破損・偽装・種別違いの拒否理由、埋込範囲、表示許可、キャッシュの上限と追い出し・ヒット | `ArtworkLoaderTest`（JVM / Robolectric）、`ArtworkDecodeTest`（API 23 / 36） |
| 差替え・元に戻す・失効（一時的な読取り不能からの復帰）・拒否時の保持・Variant 削除での連鎖、同時読込数の上限、出典ごとの許可と再配布の分離、参照の解釈と秘匿 | `ArtworkProviderTest` |
| v6 → v7 の移行で既存行と `local_artwork_ref` を保持 | `ArtworkMigrationTest` |

未実施: 実際の文書プロバイダーと実ファイルの画像、画面での描画とスクロール、メモリの実測（PSS）、外部画像の取得（#57）、同期 payload の組立て（payload の担当が判定を使うこと）、物理端末。
