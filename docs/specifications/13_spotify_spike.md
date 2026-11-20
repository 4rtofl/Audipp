# Spotify / librespot v0.8.0 の採用調査

2026-11-20 JST。[Issue #21](https://github.com/4rtofl/Audipp/issues/21)（E06-03）。
Audippの検証commitは `27219bdf7b1608f45e15693b208a571b1a51dab1`、librespotの対象は [v0.8.0 / d36f9f1](https://github.com/librespot-org/librespot/tree/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d)。試料のSHA-256、環境、試験出力は[検証記録](../evidence/issue-21/local.txt)に保存した。

| 観点 | 判定 | 根拠と範囲 |
| --- | --- | --- |
| 直接再生技術 | ローカルPCM経路は **PASS**。Spotifyのフル音源は **BLOCKED** | 上流Playerが試験用FLACのPCMを独自Sinkへ渡した。本人認可、Premium資格、Audippが使用できるアプリ登録・鍵取得経路、試料の利用権が未確認 |
| Android移植 | API 23向けのリンクは **PASS**。API 23 / 36の端末動作は **NOT_RUN** | arm64-v8aとx86_64の試験用ELFをリンクした。JNI、APK組み込み、ブラウザー復帰、Serviceの寿命、音声出力の検証は後続 |
| ソフトウェアライセンス | 上流MITと候補の推移依存の確認は **PASS**。配布物の審査は **NOT_RUN** | 上流Cargo.lockと試験の機能設定を固定し、両ABIの依存グラフを記録した。配布物は未作成 |
| サービス用途条件 | 公開条件での混在・解析は **FAIL**。Audippの個別許諾は **BLOCKED** | 公開条件と要求が衝突する。Audippの契約、地域、対象利用者、配布範囲に対する許諾の証跡なし |

**採用判断の提案**。現時点でlibrespot v0.8.0を製品のSpotify直接再生経路として採用しない。ローカルPCM境界とAndroidリンクには再現可能な証拠がある。本人認可の安全性、アカウントによる鍵取得可否、用途別許諾が採用判断に残る。Spotify直接再生は引き続き必須要件として追跡する。

## 本人認可・鍵取得・直接再生

- 上流のOAuth実装はブラウザーで認可コードを受け、SHA-256のPKCEでトークン交換する。v0.8.0の `set_auth_url` は生成した `state` を保持せず、`get_code` はcallbackから `code` を取り出すだけで `state` を照合しない。ループバック受信は最初の接続のリクエスト行を読み、期限やアプリ寿命による取消の入口がない。URLやトークン応答を出力する箇所もある。Androidではそのまま使わず、登録済みのcallback完全一致・一度限りのstate照合・PKCE・明示的な期限と取消・秘密の保護を満たすフローを確認する。 [librespot OAuthコード](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/oauth/src/lib.rs#L140-L306) [Spotify Redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri) [Spotifyのモバイル向け推奨](https://developer.spotify.com/blog/2025-02-12-increasing-the-security-requirements-for-integrating-with-spotify)
- 上流はPremiumを利用条件とし、v0.8.0のセッション実装は非Premiumと判明した場合に `exit(1)` を呼ぶ。アプリに組み込むにはプロセスを終了せず、型付き失敗を返せる境界が要る。本人のPremium加入、Audipp用の利用資格、実音源取得はそれぞれ未確認。 [librespot README](https://github.com/librespot-org/librespot) [v0.8.0のアカウント種別判定](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/core/src/session.rs#L364-L373)
- 上流の現行READMEは、一部アカウントで `audio key error 0 1` が発生し、v0.8.0を含め再生が失敗すると報告している。適用境界の日付とAudippのアカウントでの結果は未確認。v0.8.0は非暗号化ファイルを想定し、鍵取得エラー時もデコーダーを試す分岐を持つ。Audippの採用条件として、保護音源の鍵取得失敗は取得側で停止し、PCMを公開する前に失敗を通知する。SinkへPCMが到達しなかったという事実だけでは原因を識別できない。Issue #21では公開ソースと上流報告を確認し、実サービスへの接続、音源取得、保護の迂回は行っていない。 [上流の障害報告 #1649](https://github.com/librespot-org/librespot/issues/1649) [v0.8.0の鍵取得分岐](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/playback/src/player.rs#L1069-L1093)
- App Remoteはインストール済みSpotifyアプリへの操作と状態取得であり、Audippでは `REMOTE` に該当する。Audio Preview Clipsは30秒の販促用試聴素材で、フル音源の判定対象外。カタログ検索の公式Web APIは掲載されている。Audippでの検索は登録、適用クォータ、用途許諾が未確認のため実行結果は **BLOCKED**。 [App Remote](https://developer.spotify.com/documentation/android/tutorials/getting-started) [Developer Terms §II](https://developer.spotify.com/terms) [Developer Policy §V](https://developer.spotify.com/policy) [Search](https://developer.spotify.com/documentation/web-api/reference/search) [Quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes)

## PCM契約と再現

`Sink::write` は `AudioPacket` を値で受け取る。通常デコーダーの `Samples(Vec<f64>)` は44,100 Hz・2 chのインターリーブPCM。時刻、世代、レート、チャンネル数は `write` の引数に含まれない。独自Sinkではチャンネル数で割った値をフレーム数として扱う。passthroughの `Raw(Vec<u8>)` はOggバイト列であり、PCM入口では拒否する。Playerのストリーミング候補一覧はOgg VorbisとMP3で、自作FLACのPCM一致はSpotifyのlossless供給の証拠にはならない。 [Sink](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/playback/src/audio_backend/mod.rs#L26-L36) [AudioPacket](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/playback/src/decoder/mod.rs#L33-L75) [デコーダーの形式検査](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/playback/src/decoder/symphonia_decoder.rs#L64-L87) [Playerの候補形式](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/playback/src/player.rs#L1010-L1039)

- 自作FLACは前半がL=+8192/R=−16384、後半が逆極性の16-bit PCM。デコーダー解放後も全44,100フレームが独立した期待値L=±0.25/R=∓0.5と一致した。48 kHz、mono、Ogg `Raw`、空、奇数要素、非有限値、上限超過を拒否した。
- 1ブロックの試験キューは満杯時にProducerを待たせ、空きができると同世代の待機書き込みを再開する。実際のPlayerのStopは、制御側が世代を切り替えてキューを空にし、待機を解除した後に処理された。750 msのseek試験では試験用の制御側が旧世代を破棄し、実デコーダーが返した743 msの位置から後半の波形が届いた。上限16,384 samplesは試験で選んだ値。
- PlayerのStop/seekはコマンドキューを経由し、`Sink::write` は同じPlayerスレッド上で同期実行される。上流seekはSinkを切り替えない。#71では、制御側からの取消と、シーク適用後のProducerからの世代確定・位置通知を備えた契約が必要。ワーカーが古いpacketを新世代に誤って付け替えないようにし、書き込みの待機中でもStop/破棄へ進めることを確認する。 [Stop/seekのコマンド投入](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/playback/src/player.rs#L543-L570) [同期write](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/playback/src/player.rs#L1839-L1843) [seekの適用](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/playback/src/player.rs#L2202-L2254)
- Fixtureの比較位置はデコーダー/Sink境界。実際のPlayerでは音量係数と設定による正規化がSinkより前に掛かるため、試験では `NoOpVolume` と正規化無効を使った。DSP後のTap、Androidの音声デバイス、DAC出力の値は未測定。 [Sink直前のPlayer処理](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/playback/src/player.rs#L1738-L1843)

Linux x86_64でRust 1.91.1、Python 3、`unshare`、FLAC/Vorbisエンコーダーを備えたFFmpegを使う。Cargoは上流のlockfileを使う。ホスト試験はネットワーク名前空間を分離して実行する。Androidのリンクとライセンス一覧を再現する場合は、NDK r28cの絶対パスを `AUDIPP_SPOTIFY_NDK` に指定し、Rustの `aarch64-linux-android` と `x86_64-linux-android` を用意する。

```sh
git clone --depth 1 --branch v0.8.0 https://github.com/librespot-org/librespot.git librespot-v0.8.0
bash scripts/research/librespot/run.sh librespot-v0.8.0 new-issue-21-output
```

## ライセンスと用途条件

| 確認対象 | 判定と配布前に残る条件 |
| --- | --- |
| librespot v0.8.0 | MIT。複製・実質的部分の配布時に著作権表示と許可文を含める。 [固定版のLICENSE](https://github.com/librespot-org/librespot/blob/d36f9f1907e8cc9d68a93f8ebc6b627b1bf7267d/LICENSE) |
| Android候補の推移依存 | lockfile固定、既定機能なし、WebPKI rootsと試験用passthroughあり。通常・ビルド依存の一覧は両ABIで一致（259 package/version）。ビルド依存を含むため最終APKの同梱一覧は別途確定する。Symphonia系はMPL-2.0、`priority-queue` はLGPL-3.0-or-laterまたはMPL-2.0。MPL対象の実行形式を配布するときは、対象ソースの提供と取得方法の通知が必要。 [MPL 2.0 §3](https://www.mozilla.org/en-US/MPL/2.0/) |
| TLSと証明書 | `ring` の宣言はApache-2.0 AND ISCで同梱のBoringSSL等の通知も確認対象。`webpki-roots` 2版はCDLA-Permissive-2.0。データを共有する場合は同契約文を提供する。`webpki` 0.22.4はSPDX式の代わりにLICENSEを持ち、ISC系本文と同梱Chromium分の例外を指定している。配布前に各配布物の推移依存と通知を再照合する。 [CDLA-Permissive-2.0 §2](https://cdla.dev/permissive-2-0/) |
| 本人の直接再生と配布 | Spotifyの日本向け一般規約は個人・非商業向けの限定された利用を許諾し、再配布・転送等を制限する。開発者向け公開条件のPremium要件を満たす場合も、Audippで利用できる経路およびアプリ・成果物の配布条件は未確認。 [日本向け利用規約](https://www.spotify.com/jp/legal/end-user-agreement/) [ユーザーガイドライン](https://www.spotify.com/jp/legal/user-guidelines/) [Developer Policy §IV・VII](https://developer.spotify.com/policy) |
| 混在キュー・DSP・Tap | 公開Developer Policy §III.5は他サービスのストリームやコンテンツとの統合を、§III.7は他音源とのミックスや重ね合わせを制限する。統合プレイヤーの混在キューには個別確認が必要。公式SDKの案内はSpotifyコンテンツの改変を禁止し、§III.13は解析を制限するため、EQ等のPCM加工や可視化に許可ありとは扱わない。 [Developer Policy §III](https://developer.spotify.com/policy#iii-some-prohibited-applications) [Android SDKの用途条件](https://developer.spotify.com/documentation/android/tutorials/getting-started) |
| 履歴・統計・外部送信 | 派生指標や聴取統計は§III.13に抵触する。履歴保存、同期、Last.fm送信も、§III.9のデータ転送制限およびDeveloper Termsの保存・転送条件と照合し、用途・項目・保存期限ごとの許可が確認できるまで処理を拒否する。 [Developer Policy §III](https://developer.spotify.com/policy#iii-some-prohibited-applications) [Developer Terms](https://developer.spotify.com/terms) |

既存の #33 の `ProviderAccessGateTest.playbackOnlyProviderCannotFeedAnyOtherPurpose` は、架空の再生専用許諾では他用途の処理が一度も呼ばれないことを確認した。#71の統合では、再生許可とPCM加工・Tap等の許可を別々に照合し、許諾失効時に出力を止めて旧世代のブロックを破棄する。Spotify用Adapterに接続した試験は **NOT_RUN**。

## 受入条件と引き継ぎ

| Issue #21 の受入条件 | 判定 | 確認結果 |
| --- | --- | --- |
| App Remote/previewを直接フル再生の成功にしない | **PASS** | 公開資料の操作・試聴の範囲を確認し、実曲の直接再生は **BLOCKED** と記録 |
| 候補の存在からlosslessやPCM加工許可を断定しない | **PASS** | 自作FLACのPCM検査と、公開用途条件・個別許諾を別の証跡で扱った |
| 鍵取得失敗後に保護を迂回せず、採否根拠を引き継ぐ | **PASS** | 上流報告を根拠とした。資格確認後も鍵取得失敗は停止・失敗通知とし、製品採用の条件を記録 |
| 関連要求・試験・commitを記録し、未達を成功表示しない | **PASS** | [結果台帳](../traceability/results.json)にローカル試験、Androidリンク、実サービスの保留を記録 |

- **FR-SRC-08/09・DD-PROV-04/07・TEST-PROV-01 / TEST-RESEARCH-01**。Spotifyのフル音源、本人認可・取消、鍵取得、検索、失効・通信切断の実サービス試験は **BLOCKED**。正規の経路で利用できるアプリ資格、本人契約、試料・用途別許諾を確認した後に試験する。
- **FR-OBS-07・TEST-TAP-01**。音源側PCMの既知信号検査のみ **PASS**。SpotifyのDSP/Tap、Android API 23/36のinstrumented試験およびGalaxy S25・FiiO M21・S20・DACの測定は **NOT_RUN**。JNI実装と指定ADBシリアルが揃った後に #71 で追跡する。
- **NFR-LEGAL-01/02・R-06・ADR-004**。公開資料とリンク可能性は確認済み。個別用途許諾と最終配布物の依存監査は保留。要件・ADRの変更は提案していない。承認済み仕様本文は参照し、結果を台帳と本報告書に追記した。
