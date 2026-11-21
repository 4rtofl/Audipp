# WebDAV ライブラリ v1（Issue #74）

WebDAV の一覧取得と端末向け読込を [LAN 共通契約](network-v1.md) と [再生計画の解決](source-resolution-v1.md) に接続する。利用側は本人が選んだ接続先と現在の LAN 読取可否を `WebDavLibrary` に渡し、`library.client` を `NetworkLibrarySourceAdapter` 経由で `NETWORK + DEVICE` に登録する。最初の一覧は `rootDirectoryId` から取得する。解決した `DeviceResource` を同じライブラリの読込 API に渡し、再生所有者が切替・終了時に読込を取り消して `release` する。画面、Service/Media3 の再生接続、Renderer の到達性確認は後続の接続範囲。

## 一覧と読込

- 一覧は [RFC 4918](https://www.rfc-editor.org/rfc/rfc4918.html) の `PROPFIND Depth: 1` を使用し、`207 Multi-Status` の DAV 名前空間と成功したプロパティを読む。要求したディレクトリ自身と直下の項目だけを受け付ける。フォルダと音源を分け、音源は `audio/*` または既知の拡張子で候補にする。`DirectoryRequest` のページ指定はサーバー順の一覧を分割する。
- XML は 2 MiB、深さ32、直下10,000件まで。件数または XML の容量の超過は `DIRECTORY_LIMIT_EXCEEDED` を返す。項目ごとのプロパティが多いサーバーでは、10,000件より先に 2 MiB に達する（推定、実サーバーでは未測定）。DTD・外部実体、別オリジンや配下外の href、符号化されたスラッシュ・親参照、userinfo・query・fragment は応答全体を拒否する。直下の項目名に二重エンコード風の文字列、バックスラッシュ、不正 UTF-8 などが含まれ、項目を表現できない場合はその項目だけを読み飛ばす。WebDAV の共通仕様にサーバー側ページングは定義されていない。
- 接続先とパスから決定的な ID を作る。音源は `SourceRef.providerItemId` にルートからの相対パスを保持し、パスと ID が指定接続先に一致すれば一覧を取り直さずに再解決できる。相対パスに資格や接続先 URL は含めない。再生計画には相対パスを含む `SourceRef` だけを入れ、接続先 URL・資格・ハンドルは入れない。ディレクトリの一覧キャッシュは4,096件、再生ハンドルは128件までメモリに保持する。一覧キャッシュ外のディレクトリ ID、パスのない旧音源 ID、期限切れの再生ハンドルは `REFRESH_REQUIRED` を返す。端末出力へはメモリ上のハンドルを渡す。
- 選択時の `GET Range: bytes=0-0` で応答の ETag、Last-Modified、長さを記録する。[RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html) に沿って、範囲と実データが一致する `206` と強い ETag が揃えばシークできる。弱い ETag、日時だけ、Range を無視した `200` では `canSeek=false` を返す。`readSequential` はバイト0から通常の `GET` を実行する。
- `readRange` は最大256 KiBで、`If-Match` と `Content-Range` を照合する。後続で `200` が返って同一性が保たれていれば、ハンドルのシークを停止して `UNSUPPORTED` を返す。`412` や同一性の不一致では旧ハンドルを破棄し、`STALE_RESPONSE` を返す。`404`・`410` は一覧・選択・読込のいずれでも `MISSING`、`416` は `UNSUPPORTED` を返し、読込中に受け取った場合は旧ハンドルを破棄する。呼出側は失敗時にバッファを捨て、選択し直して新しいハンドルを得る。

## 通信と復旧

- 本人の LAN 利用許可と関連する OS の権限を `isLanReadAllowed` に反映する。入口、応答処理、逐次のデータ引き渡し前に確認する。取消はソケットへ伝播する。`NetworkCalls` が IO worker と通常の読取操作の timeout・再試行を担う。逐次読込は32 KiBの再利用バッファを同期 callback に渡す。本文転送と callback に通算 timeout はなく、ソケットの無通信 read timeout と呼出側からの取消が有効になる。途中失敗後に同じストリームへの追記や自動再送はしない。
- WebDAV の製品サポートは公的認証局の証明書を Android の標準検証で確認できる HTTPS 接続に限る。Basic 資格は UTF-8 の `ユーザー名:パスワード` のバイト列を使用し、指定接続先だけへ送る。`verifyCredential` で入力を確認し、本人の認証成功後に既存の `NetworkCredentials.authorize` で保存する。`401` は現在の資格を失効させる。再入力後は新しい資格を用いて一覧を取得し直し、音源を再選択する。すべての HTTP リダイレクトを拒否する。平文 HTTP の接続先は通信せずに `UNSUPPORTED` を返す。TLS 検証の失敗も再試行せずに `UNSUPPORTED` を返す。証明書の固定と LAN 内の平文 HTTP への対応は後続で判断する。
- `429` は `Retry-After` の秒数または HTTP 日時に従う。未指定・不正なら1秒待機する。同じライブラリの要求を待機させ、1回まで再送する。指定が操作 timeout の3倍を超える場合は待機を保存せず、`RATE_LIMITED` を返す。待機が操作の残り時間以上の場合も `RATE_LIMITED` を返す。通信断・timeoutは分類済みの失敗を返し、復旧後に同じ音源を選択し直せる。

HTTP クライアントは [ADR-009](../specifications/08_design_decisions.md#adr-009webdav-の-http-クライアント) に基づき OkHttp 5.4.0 を使う。以下の追跡は既存の要件・試験 ID に対応する。

| 要求・設計・試験 | ローカル fixture の検証対象 |
|---|---|
| FR-NET-01 / DD-NET-01・03 / TEST-NET-01 | 直下一覧、既存 SourceResolver での端末再生計画、206/200 の区別、媒体変更、途中切断・timeout・取消と再選択 |
| NFR-SEC-03 / DD-NET-01・03 / TEST-SEC-01 | XML 制限、明示許可と取消、HTTPS の資格分離、401 失効と再認証、429 待機、別ホスト転送の拒否 |

試験の結果と対象 commit は [検証台帳](../traceability/results.json) と対応 PR に記録する。API 23・36 の端末、実 NAS、Galaxy/FiiO、音声出力の確認は NOT_RUN。指定 ADB serial と実環境を使った検証は行っていない。音声サンプルを変更する処理がないため、DSP 精度と DAC の測定はこの変更の対象外。
