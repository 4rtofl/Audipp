# Issue #75 の検証記録

## 現行版の権限確認

Android 16 では、ソケットが `EPERM` または `EACCES` を返した場合に限り、画面から権限を再要求できる。手動 HTTP のタイムアウトや一般的な I/O エラーでは、通信の再試行を案内する。決定27を反映した版の権限画面は、実機・エミュレーターともに未検証。[UPnP 契約](../../contracts/upnp-v1.md)

## 2026-11-20 の実行結果（決定27の反映前）

実装コミット `1a07f58f7f86e65c5f21c3b1d424324febcec4f8` を、JVM の固定データとローカル通信、Android API 23・36 のエミュレーターで確認した。エミュレーターの実行結果は `E07-04-upnp-android-boundary-2026-11-20` に対応する。[検証台帳](../../traceability/results.json)

### JVM とビルド

- 単体テスト204件が成功した。UPnP の16件では、SSDP の送信元と LOCATION の照合、同一オリジン、選択 LAN の変更と名前解決の固定、HTTP のリダイレクト拒否とボディ境界、XML の上限、SCPD の宣言と SOAP 応答の照合を確認した。
- 共通 `RendererDiscovery` と `NetworkRendererClient` に固定データを接続し、拒否・タイムアウト・取消・遅延応答・明示再試行を確認した。操作後の状態再取得と、切断前後のポーリングも確認した。ループバック試験では、読込中の取消が TCP ソケットを閉じることを確認した。
- Debug・AndroidTest・未署名 Release をビルドできた。Lint はエラー0件、警告13件。UPnP 追加ファイルへの警告は0件だった。
- PR #184 の先端 `d71bab846eb2f60207b332d70ddac9e3d0cabdc7` と差分を仮に合わせた検証用ツリーで `:app:compileDebugKotlin` が成功した。共有 LAN 契約の失敗種別追加を含めて確認した。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:assembleRelease
python3 scripts/traceability/check.py
```

### Android の動作

- API 23 と API 36 で `UpnpAndroidTest` が各2件成功した。Android の XML パーサーによる DTD 拒否と、LAN 利用を取り消した状態での探索・手動入力の拒否を確認した。
- API 36 の Wi-Fi で探索の開始と候補なしを確認した。説明 URL を入力すると試験用 HTTP の `GET /device.xml`、`GET /transport.xml` と、`GetTransportInfo` の SOAP が届いた。機器名と応答の `STOPPED` が表示され、切断とバックグラウンド移行のそれぞれで SOAP の回数が増えなくなった。
- API 36 は `RESTRICT_LOCAL_NETWORK` を試験用に有効化して再起動した。未許可での SSDP 拒否、OS ダイアログを一度拒否した後の再要求、手動 HTTP のタイムアウト表示から権限を要求できること、許可後に手動取得へ復帰することを確認した。TCP タイムアウトの画面では原因を確定表示しない。

| 状態 | 画面 |
|---|---|
| 接続・申告された状態 | [Light](connected-light.png) / [Dark](connected-dark.png) |
| 探索中 | [Light](discovering-light.png) |
| 候補なし | [Light](empty-light.png) |
| OS の LAN アクセス拒否 | [Dark](permission-denied-dark.png) |

スクリーンショットは API 36 のテスト用エミュレーターと架空機器のもの。新規画面のため、同じ画面の変更前画像はない。既存のテーマと画面コンテナを使い、ネットワーク選択にはラジオボタンのセマンティクスと48 dp以上の行を使う。音声出力の測定値や判定は表示しない。文字倍率1.3の端末で長い案内文が折り返されることを確認した。System 選択時は OS の配色切り替えに追従し、明示的な Dark は OS が Light のときもアプリ再起動後に保持された。

指定された物理端末・実機レンダラーが用意されていないため、Galaxy、FiiO、有線 LAN、実 LAN の SSDP 機器応答、BubbleUPnP/upmpdcli との相互運用と音声出力は NOT_RUN。エミュレーター上の候補なしと JVM での固定 SSDP 応答を、実機の発見成功として記録しない。
