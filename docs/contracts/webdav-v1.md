# WebDAV ライブラリ v1（Issue #74）

WebDAV の一覧取得と端末向け読込を [LAN 共通契約](network-v1.md) と [再生計画の解決](source-resolution-v1.md) に接続する。利用側は本人が選んだ接続先と現在の LAN 読取可否を `WebDavLibrary` に渡し、`library.client` を `NetworkLibrarySourceAdapter` 経由で `NETWORK + DEVICE` に登録する。最初の一覧は `rootDirectoryId` から取得する。解決した `DeviceResource` を同じライブラリの読込 API に渡し、再生所有者が切替・終了時に読込を取り消して `release` する。画面、Service/Media3 の再生接続、Renderer の到達性確認は後続の接続範囲。

## 一覧と読込

- 一覧は [RFC 4918](https://www.rfc-editor.org/rfc/rfc4918.html) の `PROPFIND Depth: 1` を使用し、`207 Multi-Status` の DAV 名前空間と成功したプロパティを読む。要求したディレクトリ自身と直下の項目だけを受け付ける。フォルダと音源を分け、音源は `audio/*` または既知の拡張子で候補にする。`DirectoryRequest` のページ指定はサーバー順の一覧を分割する。
- XML は 2 MiB、深さ32、直下1,000件まで。上限超過、DTD・外部実体、別オリジンや配下外の href、符号化された区切り・親参照、userinfo・query・fragment を拒否する。上限を超えるディレクトリは取得を失敗として返す。WebDAV の共通仕様にサーバー側ページングは定義されていない。
- 接続先とパスから決定的な ID を作り、対応するパスはディレクトリと音源それぞれ4,096件、再生ハンドルは128件までメモリに保持する。ライブラリを作り直した場合や一覧キャッシュから消えた場合は一覧を取り直す。再生計画に URL・資格・ハンドルを入れず、端末出力へメモリ上のハンドルだけを渡す。
- 選択時の `GET Range: bytes=0-0` で応答の ETag、Last-Modified、長さを記録する。[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html) に沿って、範囲と実データが一致する `206` と強い ETag が揃えばシークできる。弱い ETag、日時だけ、Range を無視した `200` では `canSeek=false` を返す。`readSequential` はバイト0から通常の `GET` を実行する。
- `readRange` は最大256 KiBで、`If-Match` と `Content-Range` を照合する。後続で `200` が返って同一性が保たれていれば、ハンドルのシークを停止して `UNSUPPORTED` を返す。変更や事前条件不成立はデータを渡す前に判定して旧ハンドルを破棄し、`STALE_RESPONSE` を返す。呼出側はバッファを捨て、選択し直して新しいハンドルを得る。

## 通信と復旧

- 本人の LAN 利用許可と関連する OS の権限を `isLanReadAllowed` に反映する。入口、応答処理、逐次のデータ引き渡し前に確認する。取消はソケットへ伝播する。`NetworkCalls` が IO worker、有限 timeout、読取の再試行を担う。逐次読込は32 KiBの再利用バッファを同期 callback に渡し、その処理を含む1回の操作が timeout の対象になる。途中失敗後に同じストリームへの追記や自動再送はしない。
- Basic 資格は UTF-8 の `ユーザー名:パスワード` のバイト列を使用し、HTTPS の指定接続先だけへ送る。`verifyCredential` で入力を確認し、本人の認証成功後に既存の `NetworkCredentials.authorize` で保存する。`401` は現在の資格を失効させる。再入力後は新しい資格を用いて一覧を取得し直し、音源を再選択する。すべての HTTP リダイレクトを拒否し、別ホストへ資格・メディア URL を引き継がない。無資格の HTTP は Android 側の平文通信ポリシーに従う。
- `429` は `Retry-After` の秒数または HTTP 日時に従う。未指定・不正なら1秒待機する。同じライブラリの要求を待機させ、1回まで再送する。操作の時間枠で待てない場合は `RATE_LIMITED` を返す。通信断・timeoutは分類済みの失敗を返し、復旧後に同じ音源を選択し直せる。

HTTP クライアントは OkHttp 5.4.0 を使う。PROPFIND とレスポンスボディの取消が必要で、5.4.0 の AAR はプロジェクトの compileSdk 36 に適合する。OkHttp と試験専用の MockWebServer は Apache-2.0。Android の TLS と証明書検証を使う。以下の追跡は既存の要件・試験 ID に対応する。

| 要求・設計・試験 | ローカル fixture の検証対象 |
|---|---|
| FR-NET-01 / DD-NET-01・03 / TEST-NET-01 | 直下一覧、既存 SourceResolver での端末再生計画、206/200 の区別、媒体変更、途中切断・timeout・取消と再選択 |
| NFR-SEC-03 / DD-NET-01・03 / TEST-SEC-01 | XML 制限、明示許可と取消、HTTPS の資格分離、401 失効と再認証、429 待機、別ホスト転送の拒否 |

試験の結果と対象 commit は [検証台帳](../traceability/results.json) と対応 PR に記録する。API 23・36 の端末、実 NAS、Galaxy/FiiO、音声出力の確認は NOT_RUN。指定 ADB serial と実環境を使った検証は行っていない。音声サンプルを変更する処理がないため、DSP 精度と DAC の測定はこの変更の対象外。
