# 私用サーバーの Compose 検証手順（#53）

`server/compose/compose.yaml` は、空のデータボリュームと試験用証明書で構成を確認するためのものです。配備状況は **NOT_DEPLOYED** です。配備用 URL や既存の HTTPS 終端の設定は、配備先と差分を確認してから決めます。

## 構成と固定する版

| サービス | ローカルで使う版と接続 |
| --- | --- |
| Keycloak | 26.7.5。HTTPS はホストの `127.0.0.1:8443` のみ。管理コンソールもこの経路にあり、ヘルスチェック用の HTTP はコンテナー内のループバックに限定する。 |
| 業務 API | #34 の Gradle 配布物を Eclipse Temurin 17.0.21+9 で実行。HTTPS はホストの `127.0.0.1:9443` のみ。Keycloak の JWKS は専用ネットワーク内で HTTPS とホスト名を検証して取得する。 |
| PostgreSQL | 17.11（bookworm）。専用ボリュームを使う。DB ポートは公開しない。業務用と認証用の DB・ログインロールを分け、相手の DB への接続を拒否する。 |
| メンテナンス worker | PostgreSQL と同じイメージで論理ダンプ・復元を実行。手動実行時だけ起動し、対象 DB の所有者で接続する。 |

Linux amd64 のマニフェスト digest を Compose と Dockerfile に固定します。Ktor と JDBC の版は #34 の Gradle lockfile を使います。DB 接続は専用の内部ネットワークで SCRAM 認証を使います。ここでは DB 通信の暗号化を省いているため、配備先でもこのネットワーク境界が成り立つかを確認します。[Keycloak の対応 DB](https://www.keycloak.org/server/db) · [Keycloak の管理インターフェース](https://www.keycloak.org/server/management-interface)

既知の制約として、両方の `CREATE DATABASE` が完了した後、`REVOKE CONNECT` が完了する前に初期化が中断すると、同じボリュームでの再起動時に初期化スクリプトは再実行されません。DB 間の接続権限が残っていても PostgreSQL は healthy になり得ます。API は起動時の DB 分離検査で起動を拒否します。初期化が中断した場合は、空のボリュームで初期化からやり直します。

## ローカルで確かめる

専用のローカル Docker Engine 28.0 以降、Docker Compose v2 以降の CLI、JDK 17、OpenSSL 3、curl、Python 3 を使います。Docker Engine 28.0 未満では、ループバックに限定した公開ポートに同じ L2 セグメントから到達できる場合があるため、この構成を起動しないでください。[Docker のポート公開](https://docs.docker.com/engine/network/port-publishing/)

リポジトリルートで以下を実行します。試験は Unix ソケットを明示してローカル Docker だけに接続します。既定は `/var/run/docker.sock` で、別のローカルソケットを使う場合は `AUDIPP_DOCKER_SOCKET` を指定します。

```sh
./gradlew -p server test installDist distTar
server/compose/smoke-test.sh
```

試験は一時 CA、認証用のサービスアカウントを持つ試験専用 realm、2 つの一時 Compose プロジェクトを作り、終了時に削除します。試験専用 realm で得たトークンを業務 API に送り、HTTPS、issuer、本人 ID、認証 DB の分離を確かめます。DB 停止中と Keycloak の鍵を取得できない場合の HTTP 503、再起動後の再試行、TLS 設定失敗後の切り戻し、停止、両 DB のダンプと新規ボリュームへの復元を検査します。Keycloak 停止中でも、API のキャッシュに署名鍵があれば、有効な発行済みトークンを検証できます。招待制 realm は #51 の PR #186 で管理します。

### 停止・バックアップ・復元を手動で試す

作業中は同じシェルで実行します。検証のたびに作る秘密ディレクトリの識別子から起動元と復元先のプロジェクト名を決めます。ホストポートが使用中の場合は、空いているポートを `AUDIPP_AUTH_PORT` と `AUDIPP_API_PORT` で選びます。curl は `--connect-to api.audipp.test:9443:127.0.0.1:<公開ポート>` で接続先だけを変更できます。

```sh
AUDIPP_SECRETS_DIR=$(server/compose/create-local-secrets.sh)
export AUDIPP_SECRETS_DIR
project="audipp-local-$(printf '%s' "${AUDIPP_SECRETS_DIR##*.}" | tr 'A-Z' 'a-z')"
restore_project="$project-restore"
export AUDIPP_API_IMAGE="audipp-api:$(git rev-parse HEAD)"
docker build --platform linux/amd64 -f server/compose/Dockerfile \
  -t "$AUDIPP_API_IMAGE" server/build/install/audipp-server
docker image inspect --format '{{.Id}}' "$AUDIPP_API_IMAGE"

compose() { docker compose -p "$project" -f server/compose/compose.yaml "$@"; }
restore() { docker compose -p "$restore_project" -f server/compose/compose.yaml "$@"; }
compose config --quiet
compose up -d --wait --wait-timeout 210
compose ps
curl --noproxy '*' --cacert "$AUDIPP_SECRETS_DIR/ca.crt" \
  --resolve api.audipp.test:9443:127.0.0.1 -i https://api.audipp.test:9443/v1/me
```

`401 {"error":"unauthorized"}` は TLS と認証必須の応答を確認した結果です。realm、JWKS、DB まで含む確認には上のスモーク試験を使います。この手動手順で初回に作る Keycloak は空の構成です。管理者の初期パスワードは専用ファイルにあり、他の管理者を作成した後は初期管理者のアカウントを削除し、次回起動前にファイルを空にします。試験用の秘密ファイルは一時ディレクトリに作ります。親ディレクトリを `0700` のままにし、ファイルを Git やイメージのビルドコンテキストに置かないでください。[Compose の secrets とファイル権限](https://docs.docker.com/reference/compose-file/services/#secrets)

```sh
umask 077
backup_dir=$(mktemp -d)
compose stop api keycloak
compose run --rm -T --no-deps worker dump api > "$backup_dir/api.dump"
compose run --rm -T --no-deps worker dump auth > "$backup_dir/auth.dump"
(cd "$backup_dir" && sha256sum api.dump auth.dump > SHA256SUMS)
compose down

(cd "$backup_dir" && sha256sum --check SHA256SUMS)
restore up -d --wait db
restore run --rm -T --no-deps worker restore api < "$backup_dir/api.dump"
restore run --rm -T --no-deps worker restore auth < "$backup_dir/auth.dump"
restore up -d --wait --wait-timeout 210
```

各コマンドが成功したことを確かめてから次に進みます。ダンプの対象は各 DB の中身です。DB とロールの作成は復元先の初期化処理で行います。2 つの DB を同じ時点の状態にするため、ダンプ中は認証と API を停止します。復元はサービス停止中の新規ボリュームに対して行います。片方でも復元に失敗したらサービスを起動せず、新しい復元用プロジェクトで両方をやり直します。起動元はダンプ後の `compose down` で停止済みです。試験を終えたら `restore down` で復元先を停止します。通常の `down` はデータボリュームを保持します。

設定だけを戻すときは、停止後に更新前の Compose・設定・API イメージ ID に戻し、同じボリュームで起動します。DB migration を伴った場合は、更新前の構成と更新前のダンプを新規ボリュームに復元し、認証と API の両方を確かめてから切り替えます。今回の試験は設定の切り戻しと同じ版での復元を扱います。

## 実配備前に確定する入力（P-02）

| 入力 | 確認する差分 |
| --- | --- |
| 対象 Debian の版、CPU アーキテクチャ、Docker Engine / Compose の版 | Linux amd64 と Engine 28.0 以降への適合、実際に使うサービスのイメージ digest。API は承認したコミットからビルドし、レジストリへ登録したイメージの digest を固定する。 |
| 稼働中のサービス、ポート、ネットワーク、ボリューム、空き CPU・メモリ・ディスク | 衝突しない専用プロジェクト、バックアップと復元用の空き容量、実際の資源上限。 |
| 私用の API URL・issuer、既存 HTTPS 終端、証明書・DNS・管理経路 | 既存終端に加えるルートと ACL の差分。外向きルートは必要な realm/API パスに限定し、Keycloak 管理 UI・管理 API・メトリクスは管理経路に閉じる。上流でも証明書を検証し、API からの JWKS 取得と端末の認証で同じ issuer を確認する。 |
| 保存場所、暗号化、運用担当、復旧先と通知先 | 別の障害領域に暗号化した日次 7 世代の保存、削除記録の保管と復元後の再適用、失敗通知と復元確認。秘密の保管先はダンプと分ける。 |

対象と変更差分を示し、権限を持つ担当者の承認を得てから環境に触れます。配備版の Compose、既存 HTTPS 終端への接続、暗号化と定期バックアップ、削除記録の再適用、Android 上の障害表示は未実施です。[私用運用の保持・復旧条件](../specifications/09_private_completion_review.md#L145)

## 要求と検証の対応

| 要求・試験 | 証跡 |
| --- | --- |
| NFR-SEC-03 / DD-CLOUD-01 / TEST-SEC-01 | ループバック限定、DB ポート非公開、API コンテナーから Keycloak の管理 HTTP への接続拒否、信頼していない証明書の拒否、HTTPS で得た署名鍵と issuer の一致、認証 DB への接続拒否をスモーク試験で確認する。 |
| NFR-OPS-01 / DD-RELEASE-01 / TEST-GOV-01 | 起動、障害時の API 応答、再試行、停止、設定の切り戻し、両 DB の復元をスモーク試験で確認する。検証対象コミットと結果は[試験結果の台帳](../traceability/results.json)に記録する。 |
| FR-USE-03 | #34 の本人 ID が復元前後で変わらないことを確認する。実 Google、招待運用、Android 接続はこの試験の対象外。 |
