# Last.fm API 2.0の用途条件とローカル契約試験

2026-11-20 JST、[Issue #25 / E09-08](https://github.com/4rtoflow/Audipp/issues/25)。基準は origin/main の `ba8f18b2656d4508cfa381cbd7ded1f94a0a462a`。個人間利用版で承認済みの [FR-USE-05・NFR-LEGAL-01](../../specifications/01_requirements.md) と [DD-HIST-04](../../specifications/03_detailed_design.md#dd-hist-04-lastfm) を対象とする。実施コミットは[結果台帳](../../traceability/results.json)に記録する。

## 利用条件と資格

| 判定軸 | 結果 | 根拠と残る条件 |
| --- | --- | --- |
| 技術成立 | PASS（ローカル参照モデル） | 合成入力で署名、フォーム、応答分類と送信可否が期待値と一致。実サービスとの互換性は BLOCKED |
| Last.fmの利用資格と用途 | BLOCKED | Audipp用APIキー・共有secretは未取得。試験用の専用アカウント・対象環境の利用承認は未用意。サービスでのブラウザー承認・書き込みの実試験は未実施 |
| 音源提供元の送信許諾 | BLOCKED（実データ） | 下表の用途別証拠を取得してから有効化。利用者同意も本人ごとに必要 |
| 移植可能性 | PASS（ホストでのバイト/判定の再現） | UTF-8、フォーム、MD5、XMLで構成。Gateway実装・TLS・Androidからのブラウザー復帰は NOT_RUN |
| 依存ライセンスと配布 | PASS（今回の差分） | 自作の合成データと参照スクリプトのみ。Python標準ライブラリと既設OpenSSLは開発時の検証に使用。APKやサーバーの追加依存・SDK・外部コード・実音源の同梱なし。実サービスの商用・公開配布条件は未確定 |

Last.fm API規約の§2–4では登録ユーザーとAPI利用条件を定め、Last.fmから取得するデータに非商用原則、国外での処理に関する条件、帰属表示、キャッシュ・再配布・レート制限を設けている。有償化など商用利用には別契約が必要。公式API案内は商用および研究・学術目的の場合に事前連絡を求める。今回確認したのは公開資料とローカルモデルまで。実試験前に、運用地域と処理国、取得データの使い方、試験・個人間利用の条件を確定する。[API利用規約](https://www.last.fm/api/tos)・[API案内](https://www.last.fm/api)・[サイト利用規約](https://www.last.fm/legal/terms)・[プライバシーポリシー](https://www.last.fm/legal/privacy)

Audippが送る再生情報は元データの提供元の条件にも従う。送信許諾は `ProviderUse.SCROBBLE` の有効な証拠を提供元・アカウント・アプリ・地域・契約の組ごとに確認する。未確認、拒否、期限切れのいずれでも保留する。本人のLast.fm承認とAudippの外部送信同意を得た場合でも同じ条件を適用する。[設計判断](../../specifications/08_design_decisions.md#lastfmの確定規則)

| 音源提供元 | 今回のSCROBBLE許諾 | 実送信を有効にする条件 |
| --- | --- | --- |
| 合成fixture | NOT_REQUIRED | 自作の架空メタデータをローカルで検査 |
| ローカル（SAF等） | UNKNOWN | 本人が扱える音源・メタデータで外部送信が許されることを確認 |
| LAN（WebDAV、UPnP等） | UNKNOWN | ライブラリへアクセスする権限に加え、管理者・データ提供元の条件で外部送信可と確認 |
| Qobuz | UNKNOWN | Audippのアプリ資格・契約の下で対象メタデータと再生時刻の第三者送信を許す根拠 |
| Spotify | UNKNOWN | 同上。再生手段やライブラリのライセンスの確認とは別に審査 |
| YouTube / YouTube Music | UNKNOWN（両サービス） | サービスと音源ごとの条件で第三者送信を許す根拠。取得経路・保存可否の判定と独立 |

Scrobbleは聴取履歴として本人のLast.fmプロフィールに追加され、履歴表示や推薦などに使用される。Audippでの同意画面には送信項目、Audipp GatewayとLast.fmへの送信、Last.fm側で閲覧され得ること、解除・撤回・履歴削除が今後の未送信項目に及ぼす効果を示す。Last.fmに受理済みの履歴は、Audipp側の削除で消えたと表示しない。Last.fm側の削除は本人のLast.fm上の操作として案内する。[Scrobbling 2.0](https://www.last.fm/api/scrobbling)・[Last.fmでの履歴の案内](https://www.last.fm/about/trackmymusic)・[プライバシーポリシー](https://www.last.fm/legal/privacy)

## ブラウザー承認とGateway

- GatewayをWebアプリとして登録する方式を調査候補とした。ブラウザーを公式承認画面へ送り、利用者承認後に登録先callbackへ届く `token` をGatewayで `auth.getSession` に交換する。Web方式では `auth.getToken` は不要。公式仕様では承認トークンは60分で失効し、セッション発行時に消費される。セッション鍵には通常有効期限がなく、利用者によるアクセス取り消しで失効する。登録先とブラウザー復帰、拒否時の挙動は実環境で確認する。[Web承認](https://www.last.fm/api/webauth)・[認証仕様 §6–8](https://www.last.fm/api/authspec)・[auth.getSession](https://www.last.fm/api/show/auth.getSession)
- Gatewayは承認を始めるたびに推測困難な nonce を発行し、Audipp本人のアカウント・承認試行・有効期限と結び付けて保存する。ブラウザーをLast.fmへ送る際に、`cb` に渡すGatewayのHTTPS callback URLのクエリに nonce を含める案とする。callbackの nonce から元の試行を特定し、本人・有効期限・未処理を照合する。照合できた試行を一回限りで確保してから `token` を交換し、連携結果はその試行にだけ返す。同じ本人でも、試行Aの nonce と `token` が試行Bへ渡った場合はBでの交換を拒否する。nonce がない場合も交換しない。APKでLast.fmパスワードを収集せず、共有secretとセッション鍵はGatewayに置く。
- Last.fmが試行ごとの `cb` を許可し、既存のクエリを保持して `token` を付けて返すかは要確認。Last.fm独自の `state` は前提に含めない。#50 では、実環境でcallbackの照合が成立することを確認してからこの案を採用する。[Web承認](https://www.last.fm/api/webauth)
- 署名は `format`、`callback`、署名自身を除く名前をASCII順に並べ、エンコード前の名前と値を区切りなしで連結して共有secretを末尾に加える。UTF-8でMD5を計算し、16進表記を `api_sig` とする。書き込みでは `method` を含む全パラメーターをUTF-8のフォームPOST本文に置く。公式Scrobbling資料のURL例はHTTP表記のため、実試験でHTTPSの正規エンドポイントを確認し、平文接続への切替は行わない。[認証仕様 §8](https://www.last.fm/api/authspec#8)・[REST](https://www.last.fm/api/rest)・[track.scrobble](https://www.last.fm/api/show/track.scrobble)
- Gatewayは許可リストで項目を取り出す。Scrobbleはアーティスト、曲名、再生開始時刻（UTCのUnix秒）、認証フィールドが必須。Now Playingは再生開始時刻なしの別要求。アルバム、曲長などの任意項目は送信用途が確認されたものだけを使う。構造化タグ等を利用し、ファイル名から曲名を推定しない。Now Playingの補正値をそのままScrobbleに転用しない。音声、ローカルpath、URI、他提供元のトークン、Audipp内部IDはLast.fmへ渡さない。[Scrobbling 2.0](https://www.last.fm/api/scrobbling)・[track.updateNowPlaying](https://www.last.fm/api/show/track.updateNowPlaying)
- ScrobbleのHTTP結果、本文のstatus、各項目の `ignoredMessage.code` を確認する。0は受付、非0は無視として記録し成功件数に含めない。DNS解決の失敗などで未送信と確認できた場合は `RETRYABLE` とし、バックオフ後に再試行する。送信後に応答を失った場合や、送信の有無を確認できず受付を判断できない場合は `UNCERTAIN` で保留する。Now Playingは失敗しても再送しない。Gatewayと端末で `destination + play_event_id` を重複排除に使い、再送時は同じ開始時刻を使う。Last.fm側の冪等性は未確認。[Scrobbling 2.0](https://www.last.fm/api/scrobbling)・[DD-HIST-04](../../specifications/03_detailed_design.md#dd-hist-04-lastfm)
- Scrobbleの失敗応答では、コード11・16を `RETRYABLE`、9を `AUTH_REQUIRED` とする。恒久失敗（`PERMANENT_FAILURE`）とするのは、6（パラメーター不備）、10（無効なAPIキー）、13（無効な署名）、26（APIキー停止）。破損したコードや分類が未定のコードは `UNCERTAIN` で保留する。29は現時点では自動再送を保留する。どの制限を指すか、何を条件に再試行できるかは公式資料で要確認。[Last.fmのエラーコード](https://www.last.fm/api/errorcodes)

## 実試験の準備と後続担当

| 対象 | 申請・確認のために準備する内容 |
| --- | --- |
| [Last.fm APIアカウント](https://www.last.fm/api/account/create) | アプリ名「Audipp」、説明「少人数・非商用のAndroidオーディオプレーヤー。本人のブラウザー承認と任意の同意後に、許諾済み音源のNow PlayingとScrobbleを本人のLast.fmに送信」。自前HTTPS Gatewayの運用者、登録callback、利用地域・見込人数と頻度、送信項目を確定する |
| [Last.fmへの用途確認](https://www.last.fm/api/tos) | 必要時に上記用途、資格試験で行う処理、Gateway・データの所在国、プロフィール表示とエラーの扱い、取得データの保存範囲、撤回・削除を提示。追加許可が必要かを確認する |
| 試験アカウント・資格保管 | 承認後に本人管理の試験専用アカウントと監査可能なアクセス権を準備。実試験には権利確認済みの合成音源を用意。共有secretと本人別セッション鍵は権限制限したGatewayの秘密保管へ置き、リポジトリ・APK・同期・CIログ・診断から除外。callback query、フォーム本文、応答本文の生ログを残さず、運用証跡には秘匿済みの結果コード等を記録 |

[#50](https://github.com/4rtoflow/Audipp/issues/50) へ署名・フォームと応答fixtureを渡す。Gateway実装では本人と承認試行の照合・一回限りの取得を実際の同時リクエストで検証し、別人・別試行・期限切れ・リプレイを拒否し、秘密と送信データのログ漏洩を検査する。資格取得後にHTTPSで承認・失効・結果内訳と実応答を照合する。

[#114](https://github.com/4rtoflow/Audipp/issues/114) へ送信可否・再送分類を渡す。送信直前と各再試行時に許諾・主体・同意・削除・セッションを再評価し、撤回と同時送信、履歴削除、オフライン復帰、クラッシュ後の状態、二重イベント、最大50件のバッチ制約とASCII索引順を実装試験で検証する。試験時のACKとLast.fmでのプロフィール反映を別に観測する。

## 実施証拠と判定

初回の公開資料確認は 2026-11-20 JST。公式サイト直開きが取得エラーとなった資料は検索サービスに収録された公式ページ本文を参照した。外部応答を模したXMLは仕様に沿って作成した合成データ。SHA-256 は `2c16240fa5d2a889006fb1df8debe399418c40e816def0d656c35c8d7f56675c`（[fixtures.json](fixtures.json)、v2）。初回は Linux x86_64、Python 3.15.2、OpenSSL 3.6.5。PR #193 のレビュー対応後は Python 3.14.8 でfixtureを再実行した。

```sh
python3 -I -B docs/evidence/issue-25/test_contract.py -v
sha256sum docs/evidence/issue-25/fixtures.json
printf '%s' 'api_keyfixture-keymethodauth.getSessiontokenfixture-tokenfixture-secret' | openssl dgst -md5
printf '%s' 'api_keyfixture-keyartist試験 & Co.methodtrack.scrobbleskfixture-sessiontimestamp1795136400track曲 + 1fixture-secret' | openssl dgst -md5
printf '%s' 'api_keyfixture-keyartist試験 & Co.methodtrack.updateNowPlayingskfixture-sessiontrack曲 + 1fixture-secret' | openssl dgst -md5
```

| ケース | 判定 | 観測 |
| --- | --- | --- |
| TEST-RESEARCH-01のLast.fm用途条件の調査部分 | PASS | 公式資料と未取得資格、提供元別の送信条件、依存・配布、後続の実試験条件を記録 |
| TEST-LASTFM-01のローカル参照モデル | PASS | 4テスト・50 fixtureケース。署名3件は初回のOpenSSLによる独立計算と一致。UTF-8フォーム、別試行のcallback拒否、セッション結果、本文の混在・無視・失効、送信前失敗・送信後の応答喪失・不明なエラー、30秒超・半分・240秒の境界と送信ゲートを検査 |
| TEST-LASTFM-01の実環境 | BLOCKED | 実試験の資格・アカウント・用途条件が未確定。実サービスの申請・ログイン・送信なし |
| 製品コード・Android API23/API36の実行、実機・音声 | NOT_RUN | 変更は研究証跡のみ。Androidのビルド・Unit・Lintは実行済み。Unit 188件、Lint 0エラー・既存警告13件 |

受入条件4項目は調査範囲で PASS。未取得資格と申請内容、独立した判定軸、後続担当、要求・試験・コミットの追跡を記録した。ローカルで実行したのは参照モデルまでで、テスト対象はGateway実装、永続outbox、実ブラウザー、外部応答への接続を含まない。FR-USE-05の完成判定には #50 / #114 の実装と、資格取得後の実契約試験が残る。fixture 50件はローカルで実行し、現行CIでは未実行。所定のAndroid検証は初回調査時にローカルで実行した。
