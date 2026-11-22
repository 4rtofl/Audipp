# Issue #85 の検証記録

## 2026-11-23 JST の検証

対象は `9c104895809aeb0cb46c9833e9701fbbc85fad08`（起点は main `290be8e`）。FR-UI-01、FR-UI-02、FR-UI-06、FR-AUD-01 に対応する TEST-UI-01 の個別ケースを確認した。契約は [now-playing-v1.md](../../contracts/now-playing-v1.md)。

| ケース | 操作と確認結果 |
| --- | --- |
| ビルド・単体・Lint | `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` が成功。単体試験は487件、失敗・エラー0件、スキップ1件（既存の Samba 試験）。Lint はエラー0件、警告18件で、追加・変更したファイルへの指摘はなかった。 |
| 状態の写像・戻る規則（JVM / Robolectric） | `NowPlayingPresentationTest` 7件、`NavigationDestinationTest` 4件が成功。シーク可否（曲長不明・シーク不可・準備中・確認待ち）、停止／準備中／確認待ち（無音）の区別、出力種別の写像、placeholder 理由ごとの文言、時刻とサンプルレートの表示、ミニ展開後と Now Playing 内キューからの戻り先を確認した。 |
| セッション（Robolectric API 23 / 35） | `PlaybackServiceTest` で、Variant・申告形式・観測した出力種別が extras に載り、URI と機器名が載らないこと、一時停止で観測が外れること、通知の content intent が Now Playing を開く `MainActivity` を指すことを確認した。 |
| API 23（`emulator-5580`） | `com.audipp.ui` の計装試験15件が成功（撮影専用の1件はスキップ）。固定の再生状態を `AudippApp` に与え、ミニの展開で再生・シーク・確認の要求が送られないこと、戻りでミニの展開ボタンにフォーカスが戻ること、キューからの往復、曲長不明・シーク不可で操作を無効にして理由を出すこと、前後・詳細の無効と理由、ソース形式・提供元・出力先の表示、placeholder の理由、確認待ち（320 dp・文字200%・日英）、横画面でのすべての操作への到達を確認した。 |
| API 36（`emulator-5582`） | `com.audipp.ui` の計装試験19件が成功（撮影専用の1件はスキップ）。API 23 と同じ試験に加え、実サービスで WAV を再生し、ミニからの一時停止・再開、一時停止中に展開しても再開しないこと（遷移 ID と位置が変わらない）、Now Playing からのシークが再生位置に反映されること、回転後も Now Playing と同じ再生項目が保たれること、通知の曲名と一時停止の操作が表示と一致することを確認した。 |
| 表示 | API 36 で 320 dp 幅に制限した文字200%（日英、ライト・ダーク）、横画面（英語ライト、日本語ダーク文字200%）、確認待ち、曲長不明、シーク不可、アートの placeholder（差替え失効）を撮影した。 |

### API 36 のスクリーンショット

ミニ: [日本語・ライト](mini-ja-light.png)、[英語・ダーク](mini-en-dark.png)、[日本語・ダーク・320 dp・文字200%](mini-ja-dark-320dp-200pct.png)、[英語・ライト・320 dp・文字200%](mini-en-light-320dp-200pct.png)、[英語・ライト・横画面](mini-en-light-landscape.png)、[日本語・ダーク・横画面・文字200%](mini-ja-dark-landscape-200pct.png)

Now Playing: [日本語・ライト](now-ja-light.png)、[英語・ダーク](now-en-dark.png)、[日本語・ダーク・320 dp・文字200%](now-ja-dark-320dp-200pct.png)（[スクロール後](now-ja-dark-320dp-200pct-scrolled.png)）、[英語・ライト・320 dp・文字200%](now-en-light-320dp-200pct.png)（[スクロール後](now-en-light-320dp-200pct-scrolled.png)）、[英語・ライト・横画面](now-en-light-landscape.png)、[日本語・ダーク・横画面・文字200%](now-ja-dark-landscape-200pct.png)

状態: [確認待ち（日本語）](now-ja-light-confirmation.png)、[確認待ち・320 dp・文字200%（日本語）](now-ja-light-confirmation-320dp-200pct.png)、[同（英語・ダーク）](now-en-dark-confirmation-320dp-200pct.png)、[曲長不明](now-ja-light-unknown-duration.png)、[シーク不可](now-en-light-not-seekable.png)、[形式・出力先が不明（placeholder 付き）](now-en-light-unobserved-output-placeholder.png)

スクリーンショットは固定の再生状態（曲名 "Fixture title"、FLAC 96 kHz 24 bit、USB 機器）をアプリの画面へ与えたもので、端末の音声出力や実際の経路を示さない。320 dp は表示幅の制限で、エミュレーターの画面サイズは変えていない。

## 実装上の判断と未実施

- 出力先は、観測した経路の種別までを表示する。機器名は出力経路の契約によりサービスの外へ出さないため表示しない。選択の画面は #120。
- 前後の曲・曲の詳細は対応する機能がないため無効にし、理由を表示した。キューの編集は #86。
- 横画面・文字200%の初期表示では再開ボタンが下端で切れる場合があり、縦スクロールで到達できる。
- 未実施: 物理端末（Galaxy S25・FiiO M21・S20）、TalkBack の読み上げ、実際のアート画像を持つ音源での表示、複数経路・USB・Bluetooth の実経路の表示、実機の通知の表示、音声出力と DAC の測定。API 33 以上の通知はエミュレーターで確認できたが、`POST_NOTIFICATIONS` を持たない実機での表示は確認していない。
