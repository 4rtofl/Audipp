# アルバムの自動分類と利用者による結合・分割（Issue #54）

FR-SRC-05 のうち、DD-SRC-03 に沿ってタグ・フォルダ・ディスク番号からアルバムを組み、確定できない組を候補として返し、利用者の結合・分割を保存する部分を扱う。走査は [SAF 走査](saf-library-scan.md)、タグの読み取りは [ローカル音源のメタデータ](local-audio-formats.md)、保存先は [ライブラリ DB](library-db-v1.md) の契約を前提にする。画面は作らず、ライブラリ画面（[#84](https://github.com/4rtofl/Audipp/issues/84)）が次の API を使う。[Issue #54](https://github.com/4rtofl/Audipp/issues/54) · [試験台帳](../traceability/results.json)

## 画面から使う API

`com.audipp.data.db.LibraryAlbums(db)` を、他の DAO と同じくワーカーのディスパッチャーから呼ぶ。各呼び出しは Room の1トランザクションで完結する。

| 呼び出し | 内容 |
|---|---|
| `observeTracks(albumId)` | アルバムの曲を `disc_no`、`track_no`、`variant_id` の順に返す `Flow`。番号が不明な曲は既知の番号の後ろに並ぶ。不明な番号を 1 などで補わない |
| `candidates()` | 自動では確定しなかった組の一覧。理由（`AlbumCandidateReason`）、代表の題名とアルバムアーティスト、対象の Variant ID、関係する自動アルバムの ID を返す |
| `merge(variantIds)` | 指定した Variant を1つの新しいアルバムにまとめる。候補を確定するときは `candidate.variantIds` を渡す。題名とアルバムアーティストは先頭の Variant が今属するアルバムから取る |
| `split(variantIds)` | 1つのアルバムの一部を、同じ題名の新しいアルバムへ分ける |
| `undo(editId)` | 指定した結合・分割を取り消す。存在しなければ `false` |
| `observeEdits()` | 結合・分割の履歴を新しい順に返す `Flow`。取り消しの画面に使う |
| `organize()` | 保存済みのタグと編集から、全体の所属を計算し直す。走査の完了時に呼ばれるので、通常は画面から呼ぶ必要はない |

`merge` と `split` は `AlbumEditResult.Applied(editId, albumId)` か、次の拒否理由を返す。拒否したときは何も保存しない。

- `UNKNOWN_VARIANT`: 選択が空、存在しない Variant、またはローカル走査で索引していない Variant を含む
- `ALREADY_TOGETHER`: 結合しようとした Variant がすでに同じアルバムにある
- `NOT_PART_OF_ONE_ALBUM`: 分割の対象が1つのアルバムの真部分集合ではない

アルバムの一覧は #55 の `LibraryQueryDao` が返す `LibraryItem.albumId` でまとめる。`albumId` が null の曲は未分類で、候補に含まれる場合がある。

## 自動分類の規則

走査は各音源の `MusicBrainz Album Id`（ID3 の TXXX、FLAC の Vorbis コメント `MUSICBRAINZ_ALBUMID`、MP4 の iTunes 形式 freeform）と、走査ルート内の親フォルダを Variant に保存する。release ID は UUID の書式を満たす値だけを小文字にして保存し、名前空間は `musicbrainz:release` とする。書式の検査だけで、MusicBrainz への照会はしていない。複数の値や書式外の値は不明として扱う。

分類は全ローカル Variant を次のように組に分ける。題名とアルバムアーティストは NFKC 正規化、前後の空白の除去、連続する空白の1つへの置換、小文字化をしてから比べる。

1. release ID がある Variant は、名前空間と ID が同じものを1組にする。フォルダが違ってもまとめるので、ディスクごとのフォルダに分かれた複数枚組も1つのアルバムになる。組の中で題名またはアルバムアーティストが食い違うときは確定せず、`CONFLICTING_TAGS` の候補にする。ディスク番号は並び順にだけ使い、欠けていても組は確定する
2. release ID がなく題名がある Variant は、走査ルート・親フォルダ・題名が同じものを1組にする。アルバムアーティストが食い違う組は `CONFLICTING_TAGS`、ディスク番号のある曲とない曲が混ざる組や同じディスク・曲番号が重複する組は `DISC_CONFLICT` の候補にする
3. 2 で確定した組のうち、題名とアルバムアーティストが同じでフォルダやルートが違うものは、まとめずに別のアルバムとし、`SAME_TITLE` の候補として返す。release ID が違う同名アルバムは候補にもしない
4. 題名も release ID もない Variant は未分類のままにし、候補にもしない

候補になった組の Variant は `album_id` が null になる。自動のアルバム ID は組の鍵（release なら名前空間と ID、フォルダならルート・フォルダ・正規化した題名）から決まるので、再走査しても変わらない。

## 結合・分割の保存と取り消し

結合・分割は `album_edits`（1回の操作と、新しいアルバムの題名・アルバムアーティスト・release）と `album_edit_variants`（対象の Variant）に記録する。アルバムの行 `user-album:<edit_id>` は、この記録から分類のたびに作り直す。各 Variant は、それを含む最も新しい編集のアルバムに属する。自動分類の結果を使うのは、編集のない Variant だけである。そのため、次のようになる。

- 再走査でタグが変わっても、利用者がまとめた・分けた Variant の所属は変わらない
- `undo` は編集の行を削除して計算し直す。同じ Variant を含む古い編集があればその所属に、なければ自動分類の所属に戻る。取り消す順番は問わない
- 編集のある Variant は自動分類の入力から外す。分割で重複を外した残りの組は、整合していれば自動で確定する

どの Variant からも参照されなくなった `auto-album:`・`user-album:`・`saf-album:` の行は分類のたびに削除する。それ以外の ID で保存されたアルバムは削除しない。Variant・キュー・プレイリスト・履歴の ID は変えない。

## Room v8

`AutoMigration(7, 8)` で `track_variants` に nullable な `local_release_namespace`・`local_release_id`・`local_folder` を加え、`album_edits` と `album_edit_variants`（`variant_id` に索引）を追加する。外部キーは他の表と同じく NO ACTION で、取り消しは対象の行を明示的に削除する。[Room v8 の生成スキーマ](../../app/schemas/com.audipp.data.db.LibraryDatabase/8.json)

v7 以前の Variant は `local_folder` が null なので分類の対象にならず、従来の曲ごとの `saf-album:` の所属をそのまま保つ。そのルートを再走査すると新しい列が埋まり、走査の完了時に分類し直される。従来の `saf-album:` の行はそこで参照がなくなり、削除される。

## 実装提案（仕様に値がない点）

- release の名前空間 `musicbrainz:release`、アルバム ID の接頭辞 `auto-album:`・`user-album:`、表と列の名前
- 題名の比較に使う正規化（NFKC・空白・小文字）と、ディスク構造の整合条件（番号の有無が混ざらない、ディスク・曲番号が重複しない）
- release ID がある組ではフォルダの違いを問わない。同じ release が2つのフォルダにある場合（形式違いの複製など）も1つのアルバムにまとめ、同じ曲の Variant が並ぶ
- Vorbis コメントのブロックは 64 KiB までだけ読む。壊れたコメントは release を不明にし、音源は拒否しない
- 結合後の題名・アルバムアーティストは先頭の Variant のアルバムから取り、全員が同じ release を持つときだけ release を引き継ぐ
- 分類は全ローカル Variant を1トランザクションでメモリに読み込んで計算する。走査の完了処理（消失の確定と状態の保存）と同じトランザクションで行う

## 制約と未実施

- 取り消した走査では完了処理を実行しないので、その走査で新しく索引した曲は次の走査か `organize()` まで未分類のままになる
- 1回の `merge`・`split` の対象は SQLite の変数上限（API 23 では 999）を超えられない
- 分類は Variant 数に比例するメモリを使う。大規模ライブラリでの時間・メモリは測定していない
- 走査ルートやフォルダを移動・改名すると #35 の契約どおり別の Variant になり、以前の編集は新しい Variant に引き継がれない
- MediaMetadataRetriever だけで読んだ形式（識別対象外の形式）からは release ID を読まない
- アルバム名の手動変更、外部照合での release の確認、アルバムアートの選択は対象外（DD-SRC-04、#59）

## 検証結果

2026-11-24 JST、実装コミット `b61d3e9eab5c575f29cc979d345942d75b15b568` を検証した。

- JDK 17 で `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` が成功。ユニット試験は505件成功・1件スキップ。うち本変更で追加した試験は `AlbumClassificationTest` 8件、`LibraryAlbumsTest` 7件（Robolectric API 35）、release ID の読み取り3件。Lint はエラー0件で、警告は既存の箇所だけだった
- 単体試験の対象: 複数ディスク、ディスク番号の欠損、同名別 release、フォルダとタグの不一致、曖昧な候補、結合・分割の保存と再走査後の保持、新旧どちらの順での取り消し、拒否時に何も保存しないこと、v7 からの移行で既存の行と従来の所属を保つこと
- API 23（`emulator-5580`）と API 36（`emulator-5582`）の専用 x86_64 エミュレーターで `SafLibraryTest` と `LibraryDatabaseTest` を実行し、各25件が成功した。本変更で加えたのは、release による2枚組の分類・同名フォルダの分離・結合の DB 再オープンと再走査後の保持・取り消しの試験と、v7 から v8 への移行試験
- 走査は試験用の SAF 実装を使った。実際の文書プロバイダー、実在の音源ファイルのタグ、物理端末、ライブラリ画面との接続は未実施
