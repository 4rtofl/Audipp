# SMBライブラリ v1

[Issue #73](https://github.com/4rtoflow/Audipp/issues/73) の実装提案。FR-NET-01、DD-NET-01/02と[共通LAN契約](network-v1.md)に従う。

## 一覧と読込

- `SmbLibrary` に許可判定と利用者が指定した `smb://host[:port]/share[/path]` を渡す。既定ポートは445。対象は指定ディレクトリと、その一覧から選択した子孫に限定する。SMB1交渉、DFS referral、SMBシンボリックリンク応答の追跡を行わない。reparse属性付きの一覧項目を除外し、開いたファイルとディレクトリの属性も確認する。サーバーが透明に転送するローカルパスの実体はクライアントから確認できない。
- 直下のディレクトリと拡張子で候補と判断した音源をページで返す。`NetworkMedia.name` はPR #184と同じ任意項目を使う。ディレクトリIDと音源IDはendpointと相対パスから導出する。内部のパス対応表は各4,096件、選択ハンドルは128件、一覧の走査は20,000項目、相対パスは2,048文字まで。応答の順序はサーバーに従い、ページ間のスナップショットは提供しない。
- `SmbPassword.encode` で作ったNTLM資格を `verifyCredential` で確認し、本人の認証成功時に既存の `NetworkCredentials.authorize` で暗号保管する。同一endpointに限って `resume` した資格を使える。認証拒否で資格が失効した場合は再入力して選択し直す。ユーザー名・パスワード・share/pathは解決済み記述子やエラーの本文に入らない。
- `NetworkLibrarySourceAdapter` で端末出力用の `DeviceResource` を解決する。`readRange` は指定オフセットから最大256 KiBを返す。SMBJのファイルAPIで読取専用に開き、SMB側の書込・削除共有を許さない。サーバーGUID、ファイル番号、最終書込・変更時刻、サイズを読取前後で比較する。識別に必要な値を得られないファイルは選択できない。識別情報はサーバーの申告に基づく。
- 途中で切断した場合は部分データを破棄し、同じ位置で再試行する。再接続でも識別情報を照合する。変更・削除・再試行上限に達した場合は失敗を返して選択を失効させる。呼出側は旧バッファを破棄して再選択できる。キューとローカル音源はそのまま使える。選択の取消時は呼出元のJobを取り消し、`release` でハンドルを破棄する。
- 共通の既定値はTCP接続10秒、各操作30秒、オフライン・I/O・タイムアウトの最大3試行。各試行がソケットを所有し、取消時に閉じる。認証不成立、明示的な署名エラー、アクセス拒否は別の失敗を返す。SMBJ 0.13.0が署名のない応答や署名不一致の応答を内部で破棄した場合、ライブラリの呼出元にタイムアウトとして届くことがある。

再生所有者は読込の失敗を扱う。Media3への接続、LAN利用許諾の画面、実行時の権限要求、コーデックによる再生とリモートレンダラーへの登録は後続の統合範囲。範囲ごとに新規接続するため、長時間再生の往復回数やスループットは測定が必要。

## 依存とローカル試験

[SMBJ 0.13.0](https://github.com/hierynomus/smbj/tree/v0.13.0) の既定 `BCSecurityProvider` はBouncy Castleの軽量APIを使う。AndroidのJCAグローバルproviderを追加・置換せず、NTLMとSMB署名を行う。実行時推移依存は `bcprov-jdk18on` 1.75、`slf4j-api` 2.0.9、`mbassador` 1.3.0、`asn-one` 0.6.0。JVMテストは既存のRobolectricの依存解決でBouncy Castle 1.81を使用する。SLF4Jのログ出力実装は追加しない。API 23でNTLMが呼ぶ `Collection.removeIf` と `java.util.function.Predicate` を解決するため、`desugar_jdk_libs` 2.1.5をビルドに使用する。 [AndroidのJava API desugaring](https://developer.android.com/studio/write/java8-support)API 23/36の計測テストではアプリのAPKに含まれる依存とAndroid Keystoreを使う。

### ループバックfixture

ローカルのSambaを `127.0.0.1` と試験用ポートにのみbindし、読み取り専用の `music` 共有と同じ試験利用者を拒否する `denied` 共有を用意する。署名を必須にし、SMB2.1とSMB3.1.1の試験ではそれぞれ対応するプロトコルに制限する。試験用DBと共有は独立した一時ディレクトリに置く。試験用NTLM資格は `WORKGROUP` の `root` / `audipp-fixture-test`。

`music/album` の試験用WAVを次のように作成する。コーデックの動作確認には使わない。

```sh
python3 - '<一時共有>/album/track.wav' <<'PY'
import sys
import wave

with wave.open(sys.argv[1], 'wb') as wav:
    wav.setparams((1, 2, 44100, 0, 'NONE', 'not compressed'))
    wav.writeframes(b'0123456789abcdefghijklmnopqrstuvwxyz' * 2048)
PY
```

```sh
AUDIPP_SMB_FIXTURE_PORTS=29445,29446 ./gradlew :app:testDebugUnitTest --tests 'com.audipp.integrations.network.smb.*'
```

ポート指定がないときはSambaとの相互運用ケースだけスキップする。自動テストの残りは固定の応答とループバックソケットを使う。Androidの再現ではテスト用エミュレーターのシリアルを指定し、`adb -s SERIAL reverse tcp:PORT tcp:PORT` でループバックfixtureを転送する。`SmbAndroidTest` を実行するときは `am instrument` に `-e smbFixturePort PORT` を渡す。

実行結果と検証commitは[試験台帳](../traceability/results.json)を参照。実NAS、指定の実機、利用許諾、製品のUIからの再生、音声出力の実測は別途確認が必要。

## 2026年11月20日の検証結果

実装commit `e8f1936bb9d5101ce098d16c4bc53a718c7943bd` を検証した。

| 環境 | 結果 |
|---|---|
| JDK 17、Samba 4.25.0（ループバック） | SMB2.1とSMB3.1.1で必須署名付き認証、一覧、WAVの先頭・非ゼロ位置・64 KiBを超える範囲・終端の読込、認証失敗と共有拒否の区別を確認 |
| 固定応答とループバックソケット | SMB2からの交渉、署名エラーの分類、タイムアウトと取消時のソケット閉鎖、途中切断後の同一offset、媒体差し替えと読込中の変更の拒否、再接続失敗後の再選択、資格のスコープと読込終了後の消去を確認 |
| Androidエミュレーター API 23（SMB2.1）、API 36（SMB3.1.1） | それぞれ1件成功。実APKで署名付き接続、Android Keystoreによる資格保管・再取得、非ゼロ位置の読込、許可取消後の読込拒否を確認 |
| 引き渡し検証 | Debug、未署名Release、AndroidテストAPKの組み立て成功。JVM 199件成功（SMB 11件を含む）。Lintエラー0件、警告14件 |

最初のAPI 23試験（`e5091081a111f29ac5a767dfa3440b392955f49f`）は `NtlmAuthenticator.java:120` の `removeIf` が参照するクラスをロードできず失敗した。Java API desugaringを追加した実装commitで再実行した。Sambaではローカルfixture用のユーザー名前空間内でグループ切替を代替しているため、ホストのOS権限の検証は結果に含まれない。

PR #184と共通になる `NetworkMedia.name` は同じ形を採用し、SMBJとWebDAV依存のlockは別の座標を使う。PR #187と共有するManifestの `INTERNET` は既存宣言を使用する。両PRのheadを各々重ねたGitの試算に内容衝突はなかった。

IPv6の追検証は `a0a9789c1636c285c507e3332012e8a213f73269` で行った。SMBJが `[::1]` の表記を展開しても、指定したループバックでSMB2交渉を開始し、応答を待つ操作のタイムアウトでソケットを閉じることを確認した。JVMは200件（SMB 12件）成功し、API 23/36で署名付きSambaの同じ試験も各1件成功した。
