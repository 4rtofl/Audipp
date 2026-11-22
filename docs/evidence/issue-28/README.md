# Issue #28 の端末上の検証記録

[秘密保管 v1](../../contracts/secrets-v1.md) の暗号文の読み書き、異常時の再認証への遷移、失効時の削除と取消を、Android Keystore を使って確かめた。2026-11-22 にコミット `7fa63eb43fadddda95fa6dcc41269e38929c0f9a` の `SecretStoreDeviceTest` を API 23 と API 36 のエミュレーターで実行した。両方で通常試験8件と再起動試験2段階が成功した。通常実行では再起動用の試験1件をスキップした。

| 受入条件 | 確かめたこと | API 23 | API 36 |
|---|---|---|---|
| 1 | 別アカウントの暗号文で上書きすると拒否する。元のアカウントでは引き続き復号できる | 成功 | 成功 |
| 1 | 暗号文末尾の認証タグを1バイト変えると拒否する | 成功 | 成功 |
| 1 | 暗号文を残して鍵を削除すると拒否する。読み込み後も暗号文は変わらず、鍵は再生成されない | 成功 | 成功 |
| 1 | 同じエイリアスに新しい鍵を作り、以前の暗号文に戻すと拒否する | 成功 | 成功 |
| 2 | 指定アカウントの鍵と暗号ディレクトリだけを削除する。別アカウントの秘密は引き続き読め、曲を登録した試験用ライブラリ DB と、テーマを保存した試験用設定 DataStore のバイト列は変わらない | 成功 | 成功 |
| 3 | 認可後と更新後にそれぞれ復号できる。鍵の `KeyInfo.isUserAuthenticationRequired` が `false` である | 成功 | 成功 |
| 3 | セッション失効の `requireAuthentication(session)` で、コールバック内で待機中の `use` が取り消される | 成功 | 成功 |
| 3 | ログアウト用の `requireAuthentication(accountId)` で、コールバック内で待機中の `use` が取り消される | 成功 | 成功 |
| 再起動 | `WRITE` で保存と更新を行い、プロセス終了後の `READ_AFTER_RESTART` で更新した値を復号する | 両段階成功 | 両段階成功 |

暗号文の拒否を試す4件では、`SecretStorageException(AUTH_REQUIRED)` が発生し、セッションが `AUTH_REQUIRED` になることを確かめた。鍵削除と置換は、復元した暗号文に元の鍵が使えない状況を再現するために行った。

再起動試験では、各段階の前に `am force-stop` を実行し、API 23 は `ps`、API 36 は `ps -A` で `com.audipp` のプロセスが残っていないことを確認した。新しい試験 ID で `WRITE` を省き、`READ_AFTER_RESTART` だけを実行すると、両方で「書き込み段階が残すはずの暗号文がない」というアサーションで失敗した。

受入条件4の記録は、NFR-SEC-02／NFR-PRIV-01 と TEST-SEC-01 の端末内秘密保管に対応する。実行した範囲と未実施の範囲は、台帳の `E00-06-secrets-device-2026-11-22` と `E00-06-secrets-device-not-run-2026-11-22` に記録した。[検証台帳](../../traceability/results.json)

引き渡し時の Debug ビルドと台帳の整合性検査は成功した。単体試験は372件（成功371件、失敗・エラー0件、SMB の試験1件をスキップ）。Lint はエラー0件、警告17件で、追加した計装試験ファイルへの指摘はなかった。

## 再実行の手順

`SERIAL` を試験対象のエミュレーターの ADB シリアルに置き換える。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

test_class=com.audipp.data.secrets.SecretStoreDeviceTest
runner=com.audipp.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL shell am instrument -w -r -e class "$test_class" "$runner"

run_id=$(cat /proc/sys/kernel/random/uuid)
for phase in WRITE READ_AFTER_RESTART; do
  adb -s SERIAL shell am force-stop com.audipp
  adb -s SERIAL shell am instrument -w -r -e secretsPhase "$phase" \
    -e secretsRunId "$run_id" -e class "$test_class#processRestartPhase" "$runner"
done
```

`force-stop` の直後にプロセスを確認する場合は、API 23 では `adb -s SERIAL shell ps`、API 36 では `adb -s SERIAL shell ps -A` を実行する。再起動試験の各段階は `INSTRUMENTATION_STATUS_CODE: 0` で成功する。同じ試験 ID を2段階に渡し、最後の段階で試験用の鍵と暗号文を削除する。順序を崩す試験では、新しい UUID を指定して `READ_AFTER_RESTART` だけを実行し、暗号文がないというアサーションと `INSTRUMENTATION_STATUS_CODE: -2` を確認する。

## 確かめていない範囲

- 物理端末、StrongBox、ハードウェアで保護された鍵、OS が鍵を無効化する実際の条件、OS のバックアップと復元。
- 再生履歴の保持。現行の Android アプリに再生履歴を保存する実装がないため、削除前後で比較できるのはライブラリ DB と設定 DataStore まで。
- 画面での再認証表示、実サービスの期限監視とログアウト、既に送信した要求の取消、実サービスの利用や外部送信に関する用途別の許可。
