# Issue #84 の検証記録

## 2026-11-25 JST の検証

対象は `1d4a5b7417ad79ec84c81e678bf316fb80dbc8be`（起点は main `ed7d4e9`）。レビューの指摘（選曲の取消・失敗で Main に例外を投げる、項目を閉じると位置・検索語・並びが失われる、失敗した走査に前回の結果が出る、アルバムが降順を引き継ぐ、登録処理中の取消）を直したあとの結果。FR-UI-01、FR-SRC-02、FR-SRC-03、FR-SRC-05 に対応する TEST-UI-01 と TEST-LIB-02 の個別ケースを確認した。契約は [library-ui-v1.md](../../contracts/library-ui-v1.md)。

| ケース | 操作と確認結果 |
| --- | --- |
| ビルド・単体・Lint | `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` が成功。単体試験は552件、失敗・エラー0件、スキップ1件（既存の Samba 試験）。Lint はエラー0件、警告18件で、追加・変更したファイルへの指摘はなかった。 |
| 一覧の問い合わせ（Robolectric API 35） | `LibraryQueryDaoTest` 6件。同じ題名で ID の違うアルバムと、同じ名前で走査ルートの違うフォルダが別の行になること、不明な値が1つの行にまとまり最後に並ぶこと、アーティストの大文字小文字、降順・検索語・窓の位置と総数、アルバム ID・フォルダの絞込み、ディスク・曲番号の順（番号不明は後ろ）を確認した。 |
| 状態の写像・古い応答（JVM） | `LibraryBrowserTest` 7件、`LibraryNoticeTest` 1件。項目を開くと曲の問い合わせがその項目に絞られること、閉じると開く前の検索語・並び（降順を含む）と窓の位置に戻り「読み込み中」を挟まないこと、アルバムは降順の一覧からでも昇順で開くこと、登録処理中は取消を出さないこと、検索語を変えると「読み込み中」を出して前の問い合わせを取り消し、遅れて届いた前の応答を出さないこと、スクロールでは「読み込み中」に戻らず窓だけが動くこと、読み取り失敗と再試行、読み込み中・空・失敗・権限の拒否と失効・取消・未完了の区別を確認した。 |
| 選曲から再生入口（JVM） | `LibrarySelectionTest` 5件。選んだ行の Variant をそのまま再生入口へ送ること、準備中は前の曲の位置・曲長・形式・出力・操作を出さないこと、曲がない・接続なし・拒否・受付後の失敗で失敗になり再試行で同じ Variant を送り直すこと、別の entry の失敗を巻き込まないこと、新しい選曲で前の要求の遅い応答を捨てることを確認した。`PlayRequestTest` 3件（Robolectric）で、待ちの取消と要求の失敗でリスナーが例外を投げず、失敗は接続なしとして返ることを確認した。 |
| 登録進捗・戻り先（Robolectric） | `FolderRegistrationTest` 3件で、未完了と完了の走査結果（状態・理由・件数）が残ること、新しい走査を始めると前回の結果が消え、取消した走査だけが取消として表示されること、`NavigationDestinationTest` で登録進捗から戻ると Library に戻ることを確認した。 |
| API 36（`emulator-5582`） | `com.audipp.ui` の計装試験24件が成功（撮影専用の2件はスキップ）。`LibraryUiTest` だけを続けて3回実行し、毎回成功した。本変更の `LibraryUiTest` は4件。600 件のアルバム一覧で検索語を入れて窓の外までスクロールし、アルバムを開いて戻ると、同じ行の付近と検索語が残ること。端末の Room に3,000曲を置いて窓の外までスクロールし、選んだ行の Variant が再生入口へ渡ること、拒否から再試行して Now Playing が開き、前の曲名を出さずに選んだ曲を準備中として出すこと。実 Activity で、表示の選択が回転と登録進捗からの戻りで保たれること、権限を失ったフォルダで「フォルダを選び直す」が OS のフォルダ選択を開き、取消しても同じ案内が残ること。実サービスで、ライブラリの WAV を選ぶと再生され、Activity の再生成後も同じ entry の Now Playing が開いていること。 |
| API 23（`emulator-5580`） | 未実施。2026-11-24 から共有のエミュレーターで `adb shell` が応答せず（30 秒で時間切れ）、アプリを導入できなかった。共有のため再起動していない。 |
| 表示 | `ac9dea5` の API 36 で日英・ライト・ダーク、320 dp 幅に制限した文字200%、横画面、長い名前・タグ不明・アートなし、読み込み中・空・選曲の失敗・権限の失効、登録進捗（走査中・一部だけ読んだ結果）を撮影した。 |

### API 36 のスクリーンショット

曲: [日本語・ライト](songs-ja-light.png)、[英語・ダーク](songs-en-dark.png)、[日本語・ダーク・320 dp・文字200%](songs-ja-dark-320dp-200pct.png)（[行まで送った後](songs-ja-dark-320dp-200pct-rows.png)）、[英語・ライト・320 dp・文字200%](songs-en-light-320dp-200pct.png)（[行まで送った後](songs-en-light-320dp-200pct-rows.png)）、[英語・ライト・横画面](songs-en-light-landscape.png)、[日本語・ダーク・横画面・文字200%](songs-ja-dark-landscape-200pct.png)

アルバム: [日本語・ライト](albums-ja-light.png)、[英語・ダーク](albums-en-dark.png)、[開いたアルバム（英語・ライト）](album-open-en-light.png)

状態: [読み込み中](state-loading-ja-light.png)、[選曲の失敗と再試行](state-selection-failed-ja-light.png)、[フォルダへのアクセス不可](state-access-lost-ja-light.png)、[空のライブラリ（英語・ダーク）](state-empty-en-dark.png)

登録進捗: [走査中](source-scanning-ja-light.png)（[フォルダ](source-scanning-ja-light-folders.png)）、[一部だけ読んだ結果（英語・ダーク）](source-partial-en-dark.png)（[フォルダ](source-partial-en-dark-folders.png)）、[同（日本語・320 dp・文字200%）](source-partial-ja-light-320dp-200pct.png)（[フォルダ](source-partial-ja-light-320dp-200pct-folders.png)）

スクリーンショットは `ac9dea5` で撮った。その後の修正は戻り先・走査結果・登録処理中の取消の動作で、撮影した画面の配置は変えていない。固定のライブラリ・再生状態（曲名 "Fixture title" など）をアプリの画面へ与えたもので、実在のライブラリや端末の音声出力を示さない。320 dp は表示幅の制限で、エミュレーターの画面サイズは変えていない。

## 実装上の判断と未実施

- 320 dp・文字200%では、見出し・フィルター・検索欄・並び替えで画面の大半を使い、曲の行はスクロールして表示する。
- アルバムの結合・分割の画面は作っていない。範囲外と判断した。
- 未実施: API 23 の計装試験、物理端末（Galaxy S25・FiiO M21・S20）、TalkBack の読み上げ、実在の大規模ライブラリでのスクロールの速度とメモリ、実際のアート画像を持つ音源での一覧、実際の文書プロバイダーでのフォルダの再選択の成功。
