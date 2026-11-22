# Now Playing・共通ミニプレイヤー v1（Issue #85）

対象は [Issue #85](https://github.com/4rtoflow/Audipp/issues/85) の FR-UI-01 / FR-UI-02 / FR-UI-06 / FR-AUD-01 → DD-UI-02 → TEST-UI-01 のうち、現在曲の表示、ミニから Now Playing への展開、出力先の表示です。以下の名前・寸法・並びは実装提案で、採用済みの製品条件は変えていません。画面の戻り規則と通知の起動先は[ナビゲーション案 §2](../specifications/12_navigation_proposal.md)に従います。

## 状態の持ち主

再生状態は `PlaybackService` の `PlaybackSnapshot` だけが持ちます。ミニ・Now Playing・通知は同じセッションを読むだけで、独自の再生状態を持ちません。UI は `NavigationPlayback` が組み立てる `NavigationPlaybackState` を見ます。

| 表示 | 出どころ | 値が取れないとき |
|---|---|---|
| 曲名・アーティスト | セッションの `MediaMetadata`（ファイルのタグ）。起動時に仮置きされるアプリ名は曲名にしない | 「曲名不明」 |
| 状態 | `PlaybackSnapshot.phase`、確認待ち | 下表 |
| 再生位置・曲の長さ | `PlaybackSnapshot.positionMs / durationMs` | `--:--`。シーク中はサービスが位置を返さないため、要求した位置を表示し続ける |
| 提供元 | `effectiveSource` の種別（端末のファイル / サービス名 / ネットワーク） | 行を出さない |
| ソース形式 | 選んだ `TrackVariant.format`（ライブラリが記録した値）。デコーダーや出力の形式ではない | 取れた項目だけを並べ、1つもなければ「不明」 |
| 出力先 | `AudioTrack` が再生中に報告した経路の種別（下記） | 「不明（再生中の経路だけ観測できます）」 |
| アート | `ArtworkProvider.load(variantId, edgePx)`。要求辺は 64 の2の冪に切り上げ、最大 1024 | 理由つきの placeholder（下記） |

サンプルレートの一致や出力先の種別から Bit-perfect とは表示しません。機器名、実際の出力形式、DAC 側の値は表示せず、出力のボタンから開く説明文でその旨を示します。

## セッション extras（自アプリのコントローラー向け）

`PlaybackConnection` の既存の extras に次を足しました。文書の URI と機器名は含めません。

| キー | 内容 |
|---|---|
| `preparedVariant` | 準備中の `VariantId`。経路の失効で `ExecutionPlan` が外れてもアートを引き続ける |
| `sourceFormat` | その Variant の `AudioFormat`（JSON） |
| `observedOutputTypes` | 再生中に観測した経路の `AudioDeviceInfo.type` の配列。一時停止・停止中や観測できないときはキー自体がない。要素が2つ以上なら「複数の経路」 |

`outputRoute` の機器名とポート ID は、[出力経路の契約](output-route-v1.md)どおりサービスの外へ出しません。このため出力先は種別（内蔵スピーカー、有線、Bluetooth、USB、HDMI、その他）までを表示します。機器名の表示は出力 Picker（#120）で扱います。

## 状態の区別

| 状態 | 条件 | 表示 |
|---|---|---|
| 停止 | 現在曲なし（`Idle`） | ミニを出さない。Now Playing を開いていれば「再生中の曲はありません」 |
| 準備中 | `RESOLVING` / `BUFFERING` / `ADVANCING` | 「再生を準備中」。再生・シークとも無効で理由を出す |
| 再生中・一時停止中 | `PLAYING` / `PAUSED` | 再生・一時停止、シーク |
| 無音 | 出力先の確認待ち（`OutputConfirmationRequest`）。サービスが音量ゼロで止めている | 「確認待ち（音を出していません）」。確認のボタンだけを出し、再生とシークは無効 |
| 未対応・失敗 | `Failed`（`FORMAT_UNSUPPORTED` など） | ホームと同じ文言で、形式未対応と権限・読み取り失敗を分ける |

## シークと前後

- シークは、確認待ちでなく、`PLAYING` / `PAUSED` で、曲の長さが分かり、セッションが `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` を許すときだけ有効です。無効のときは理由（準備中 / 確認待ち / 曲の長さが不明 / この音源は不可）を表示します。
- 前の曲・次の曲はサービスにキューがなく、セッションも許していないため無効で、理由を表示します。キューの編集は #86 で扱います。
- 「詳細」も曲の詳細画面がないため無効で、理由を付けます。画面は作っていません。

## アートの placeholder

`PlaceholderReason` を次の文言にします。Now Playing では画像の下に理由を書き、ミニでは `contentDescription` に入れます。

| 理由 | 文言（日本語） |
|---|---|
| `NO_ARTWORK` | アートなし |
| `EXPIRED` | 差し替えた画像を読み込めません |
| `UNAVAILABLE` | 音源からアートを読み込めません |
| `DISPLAY_NOT_ALLOWED` | このアートは表示が許可されていません |
| `REJECTED` | アートの画像を表示できません |

## 展開・戻る・通知

- ミニの展開は画面遷移だけで、再生要求を送りません。現在の閲覧画面（タブ・ページ）を残したまま Now Playing を重ね、戻ると同じ画面に戻ります。Now Playing の「キュー」からはキューを重ね、戻ると Now Playing に戻ります。
- Now Playing にミニは重ねません。閉じると展開に使ったミニの展開ボタンにフォーカスを戻します。
- 通知の content intent は `ACTION_OPEN_NOW_PLAYING` を付けて既存の `MainActivity`（`singleTop`）に届き、現在曲があれば Now Playing、なければ Home を開きます。サービスの応答を待ってから判断します。

## 実装提案（寸法・配置）

- ミニ: アート 48 dp、曲名・アーティスト＋状態、一時停止・再開。展開領域は幅 176 dp を下回るとボタンを次の行へ回し、曲名を切り詰めません。
- Now Playing（縦）: 上部に縮小・キュー・出力先・詳細、アート（幅 −48 dp・320 dp・高さの 40 % の最小。文字が大きいほど文字倍率で縮小）、曲名・アーティスト・状態、シーク、前／再生／次、提供元・ソース形式・出力先の順。再生操作を技術情報より先に置いた。
- Now Playing（横）: 幅 600 dp 以上かつ横長のとき、左にアート、右に同じ内容を縦スクロールで並置する。向きだけでは2ペインにしない。
- 確認待ちの案内は、ミニでは従来どおり高さ 168 dp で内側にスクロールし、Now Playing では画面全体がスクロールするため内側のスクロールを持たない。

## 検証と未実施

| 検査 | 試験 |
|---|---|
| 状態の写像（シーク可否・準備中・確認待ち・出力種別）、placeholder の文言、時刻表示、戻る規則 | `NowPlayingPresentationTest`、`NavigationDestinationTest`（JVM / Robolectric） |
| セッション extras の内容と秘匿、通知の content intent | `PlaybackServiceTest`（Robolectric API 23 / 35） |
| ミニの展開で再生要求を送らない、戻りでフォーカス、無効化の理由、確認待ち（320 dp・文字 200 %・日英）、横画面 | `NowPlayingUiTest`（API 23 / 36） |
| 実サービスでの一時停止・再開・展開・シーク・回転、通知との一致 | `NavigationPlaybackIntegrationTest`（API 29 以上。API 36 で実行） |

未実施: 物理端末、TalkBack の読み上げ、実際のアート画像を持つ音源、複数経路・USB・Bluetooth の実経路の表示、`POST_NOTIFICATIONS` を持たない API 33 以上の通知の表示確認（エミュレーターでは通知が見えたが、実機での確認はしていない）、音声出力・DAC の測定。
