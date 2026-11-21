# プレイリストの保存とキューへの複製

[#39](https://github.com/4rtoflow/Audipp/issues/39) は FR-PLY-05、DD-DB-01/02、TEST-PLAYLIST-01 を扱う。Room v3 に `playlists` と `playlist_items` を追加し、v2 のキューとチェックポイントを移行する。

- `PlaylistDao` で作成・一覧・取得・改名・項目編集・削除を行う。名前は前後の空白を除いて保存し、同名は `NAME_CONFLICT` を返す。大文字と小文字は区別する。古い revision を指定した編集と削除は `STALE_REVISION` を返す。
- 各項目は独自の ID と Variant への参照を持つ。同じ版の複数登録を許し、音源が消失しても参照を残す。項目の再採番と revision の更新は同じトランザクションで実行する。
- `LibraryDatabase.appendPlaylistToQueue` はプレイリストと対象キューの revision を同じトランザクションで確認し、プレイリストの順序でキュー末尾へ追加する。キュー項目には新しい ID を割り当てる。コピー後にどちらかを編集しても、もう一方の順序と項目は影響を受けない。

`PlaylistDaoTest` の5件が API 35 の Robolectric で成功した。TEST-PLAYLIST-01 の作成・改名・項目更新・削除・複製に加え、同名と古い revision の拒否、途中失敗時のロールバック、v2 からの移行を確認した。実機での動作確認は未実施。

プレイリスト画面と編集の操作導線は [#86](https://github.com/4rtoflow/Audipp/issues/86) で扱う。
