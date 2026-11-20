# 招待制の認証と復旧

対象は Issue #51 の FR-USE-03、NFR-SEC-02、DD-CLOUD-04、ADR-005、Q-07、TEST-ACC-01、TEST-SEC-01。Android の認証・更新・解除は #79、同意撤回とアカウント削除はそれぞれの担当 Issue で扱う。

## Realm と業務 API

実装提案として [Keycloak 26.7.5 の realm](../../server/keycloak/audipp-realm.json) を用意した。新規の専用 realm を作る際に取り込む。既存 realm に同名ファイルを置いても起動時インポートは設定を更新しない。既存環境への適用は差分確認と承認を経て行う。

- `audipp` は HTTPS 必須。自己登録、パスワード再設定メール、メール自動確認を無効にし、SMTP を使用しない。
- 認証イベントの標準ログ出力を止め、ログにユーザー情報や OAuth の redirect URI を残さない。
- `audipp-android` は Authorization Code と PKCE S256 を使う公開クライアント。シークレットを持たず、直接のパスワードグラント・implicit flow・オフラインスコープは提供しない。#79 の接続用 callback は `com.audipp:/oauth2redirect` を提案し、完全一致で登録する。
- 業務 API は `AUDIPP_ISSUER=https://<認証ホスト>/realms/audipp`、`AUDIPP_AUDIENCE=audipp-api` で [既存の契約](backend-v1.md) に接続する。メールを変更しても、Google を追加しても、所有権は同じ Keycloak ユーザーの `issuer/sub` に紐づく。
- 結合時にブラウザーの SSO Cookie だけで認証を済ませないよう、専用ブラウザーフローから Cookie 認証を外した。新しいブラウザー認可を開始するたびに認証が必要になる。設定済みのローカル OTP はパスワード認証時に引き続き使える。

## 管理者の招待と復旧

1. **招待**は、管理者が招待相手本人と確認できる連絡経路を先に確立する。Keycloak の `Users` でメールを持つユーザーを作成し、`Email verified` はオフのままにする。復旧時に照合できるよう、管理側の招待記録に Keycloak のユーザー ID と本人確認に使える経路を残す。
2. `Credentials` でランダムな一時パスワードを設定し、`Temporary` をオンにする。本人確認に使った別経路でパスワードを本人に渡す。初回のログインではパスワード更新後に認可コードを発行する。
3. **復旧**は、招待時に記録した別経路で再度本人を確認してから、同じユーザー ID を対象にする。申告されたメール文字列の一致だけでは受付を進めない。本人確認できない場合は操作を打ち切る。
4. 対象を一時的に無効化し、新しい一時パスワードを設定する。対象ユーザーの `Sessions` からすべてのセッションをサインアウトする。もし他クライアントのオフラインアクセスがあれば `Consents` から取り消す。Google 側の侵害を含む復旧では、侵害された ID のリンクを外してから再開する。
5. セッション失効を確認してからアカウントを再度有効にし、確認済み経路で一時パスワードを本人に渡す。設定や失効が中断した場合は、そのアカウントを無効のままにし、ユーザー ID と操作結果を確認する。必要なら新しい一時パスワードを再設定し、失効からやり直す。作業記録にはパスワードやトークンを残さない。

Keycloak のセッション失効後は旧 refresh token で更新できない。#34 の API は署名検証方式のため、発行済み access token は有効期限まで受理され得る。この realm で新規発行する Android 向け access token の寿命は 60 秒。管理 API の対応操作は [Keycloak 26.7.5 Admin REST API](https://www.keycloak.org/docs-api/26.7.5/rest-api/) を参照。

## Google 結合

実 Google の確認は **BLOCKED**。利用資格のある OAuth クライアント、対象 tester、登録済み redirect URI が揃った環境で、サーバー側から `google` にクライアント ID とシークレットを設定する。Google 側には Keycloak が表示する HTTPS の broker callback (`https://<認証ホスト>/realms/audipp/broker/google/endpoint`) を完全一致で登録する。準備が揃うまでは `google` を無効のままにする。シークレットは保護された設定から渡し、realm JSON、Android、ログへ保存しない。

1. 招待済みの利用者が [Account Console の Linked accounts](https://www.keycloak.org/docs/26.7.5/server_admin/index.html#_account-service) で Google の結合を選ぶ。
2. 既存アカウントへ再認証し、Keycloak の確認を承認してから Google で認証する。中止した場合は結合しない。
3. 結合後は Google でも同じ Keycloak ユーザーへログインできる。未結合の broker 認証は、メールが一致しても first-broker-login フローが拒否する。メールを信用済みに変更する設定と upstream token の保存は無効とする。

Google 未設定でも、招待済みアカウントのメールログインを利用できる。Android のローカル再生には認証が不要。

## ローカル検証

JDK 17 で `./gradlew -p server test installDist distTar` を実行する。次に、Linux、JDK 21 以上、Python 3、OpenSSL、curl、PostgreSQL の `initdb`・`pg_ctl`・`psql` を使い、[Keycloak 26.7.5 の公式アーカイブ](https://github.com/keycloak/keycloak/releases/tag/26.7.5) を展開して実行する。公式の `keycloak-26.7.5.tar.gz` の SHA-256 は `87d680d13cf4600d232ca0433a2553c57ebf03e56f7718fc0d03a1a8705497c8`。

```sh
server/keycloak/run-local-contract.sh /path/to/keycloak-26.7.5
```

`initdb` と `pg_ctl` が `pg_config --bindir` にない場合は `PG_BIN` で場所を指定する。一時 Keycloak (H2)、業務 PostgreSQL、#34 の実 API をループバックで起動し、一時証明書を明示的に信頼する。テスト失敗時は一時ディレクトリを表示する。

| 要求と試験 | 確認する範囲 |
| --- | --- |
| FR-USE-03、DD-CLOUD-04、TEST-ACC-01 | 自己登録と公開パスワード再設定の拒否、初回変更、招待済みメールログイン、別ユーザーによる復旧拒否、パスワード再設定の応答喪失を注入して無効状態から再試行、旧 refresh token の拒否 |
| FR-USE-03、TEST-ACC-01 | 別のローカル realm を OIDC プロバイダーに使い、メール一致での結合拒否、再認証、明示承認、取消、消費済み callback の拒否、結合後の同じ業務ユーザー ID を確認 |
| NFR-SEC-02、TEST-SEC-01 | メール未確認状態、SMTP 未設定、PKCE 必須と不一致拒否、実 Keycloak 署名付き JWT の API 受理、ID token の拒否、別所有者の端末へのアクセス拒否、試験パスワード・JWT・OAuth redirect URI のログ非出力 |
| BLOCKED / NOT_RUN | 実 Google の OAuth・tester・利用資格は BLOCKED。配備、実環境の DB と TLS 終端、Android 端末での認証・再生は NOT_RUN |
