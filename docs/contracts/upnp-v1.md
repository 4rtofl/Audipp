# UPnP の探索・制御基盤（Issue #75）

FR-NET-02・NFR-SEC-01 → DD-NET-01・DD-NET-03 → TEST-NET-01・TEST-SEC-01 のうち、SSDP、機器・サービス定義、SOAP、権限拒否からの再試行を対象にする。[Issue #75](https://github.com/4rtoflow/Audipp/issues/75)

## 操作と境界

- 「設定 → UPnP 出力」で Wi-Fi または有線 LAN を一つ選ぶ。「機器を探す」で、選択した Android `Network` にソケットを束縛して IPv4 の SSDP M-SEARCH を送る。探索は最大10秒・最大1,000件。同一 UDN の応答を重複排除し、応答送信元と一致する数値アドレスの LOCATION だけを候補にする。応答では ST・USN・LOCATION・CACHE-CONTROL を検査する。探索後は最大1秒、候補の説明から機器名を取得する。取得できた機器名または接続先に UDN の末尾を添えて表示する。
- 手動指定は機器説明の HTTP URL またはホスト（任意のポートとパスを含む）を受け付ける。ホストのみなら HTTP のルートを取得する。機器によっては説明 URL のパスが必要になる。ホスト名は選択した `Network` で解決する。HTTP は、選択した LAN のサブネットに直接属するプライベート IPv4、IPv4 リンクローカル、または IPv6 ULA の一つに固定する。選択をやり直したら候補も取得し直す。
- 機器説明の UDN と種類を照合する。同じ種類の上位版を受け入れ、手動指定では対象種類の埋め込み機器も選べる。URLBase・SCPDURL・controlURL は説明 URL と同じ HTTP オリジンに限定する。controlURL ではクエリ付き URL を受け付ける。他の URL ではクエリを拒否し、すべての URL でリダイレクト、別ポート、userinfo・フラグメントを拒否する。これは認証を伴わない LAN 内 HTTP 用の実装で、他のアプリ通信の cleartext 設定は変更しない。UDN の照合は機器の真正性を証明するものではない。
- 機器説明・SCPD・SOAP および SOAP に内包された一覧 XML では DTD と外部実体を無効にする。単一 XML は2 MiB、深さ32、同階層の子1,000件、文書全体65,536要素まで。SOAP は SCPD で宣言された action・引数と応答の名前空間を照合する。
- 出力の状態には #49 の `RendererAdapter`・`NetworkRendererClient` を使う。宣言された `GetTransportInfo` が使える接続だけを2秒間隔でポーリングし、SOAP 操作後も状態を取得する。取得時の機器の申告を表示し、未取得と失敗を区別する。切断、別ネットワークの選択、画面離脱、バックグラウンド移行で通信を取り消す。常時探索、GENA 購読、端末音源配信用の HTTP リスナーは起動しない。
- Wi-Fi のマルチキャストロックは探索中だけ取得する。Android 16 でソケットの EPERM または EACCES を観測した場合に限り、画面から近くのデバイス権限を再要求できる。タイムアウトと一般的な I/O エラーでは、通信の再試行を案内する。Android 17 向けの targetSdk 引き上げに伴う権限切り替えは今後の範囲。[Android の LAN アクセス権限](https://developer.android.com/privacy-and-security/local-network-permission)

## 実装上の選択と後続作業

HTTP は依存を追加せず、選択ネットワーク・固定アドレスに束縛した短命ソケットで扱う。接続と読込の無通信上限は各3秒、操作全体は既存の `NetworkCalls` で制限する。取消でソケットを閉じる。OS の同期 DNS 解決は worker で実行し、取消後の結果を採用しない。探索候補の有効期限は SSDP の max-age（上限30分）、手動候補は5分とした。接続開始時に候補の期限を検査し、機器説明を再取得して接続できた時点と、その後に SOAP が成功するたびに、接続の有効期限を同じ期間だけ延長する。期限が切れたら探索または手動指定からやり直す。SSDP のマルチキャストは IPv4 に限る。

既存設定の下に探索・接続確認画面を置き、共通画面コンテナ、テーマ、エラー色、タッチ領域を使う。ネットワーク選択と手動入力は画面内の状態で、機器や URL を保存・同期しない。対象を操作できるかは機器定義の取得結果から判断する。ContentDirectory のページングと音源解決は #105、AVTransport / RenderingControl の再生操作と音量は #106、OpenHome Playlist / Volume の操作は #107 が担当する。

検証対象・結果・未実施ケースは[検証台帳](../traceability/results.json)に記録する。画面とエミュレーターでの確認手順は[検証記録](../evidence/issue-75/README.md)を参照。
