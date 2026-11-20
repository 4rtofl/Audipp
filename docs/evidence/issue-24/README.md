# YouTube系の音声中継・保存条件の調査（E06-06 / #24）

2026-11-20 JST・[Issue #24](https://github.com/4rtofl/Audipp/issues/24)。基準の `origin/main` は `ba8f18b2656d4508cfa381cbd7ded1f94a0a462a`。試験対象のコミットは [`159ecc2`](https://github.com/4rtofl/Audipp/commit/159ecc2dec014cd70d62cdbec8b7323b54e4514f)。実行結果は [local.txt](local.txt)。

自作 AAC/fMP4 を使ったループバック試験では、全体取得前に先頭をデコードでき、Range 取得、同じ媒体への再解決、切断位置からの再取得が成立した。YouTube と YouTube Music の実サービス試験は利用資格・取得方法・用途別許諾の確認待ちで、どちらも **BLOCKED**。

## ローカル試験

クライアント、認証付き中継、架空の配信元を同じホストの `127.0.0.1` 上で動かした。配信元の静的 DASH マニフェストには、単一リソースで配信する AAC/fMP4 表現と、取得されたら試験で検出する映像候補を置いた。yt-dlp の Generic extractor が `bestaudio` を解決し、中継が同じホスト側で音声の HTTP body を取得して再圧縮せずに渡す。音源はメモリー上で生成した 48 kHz の正弦波（997 Hz、媒体変更用に 1499 Hz）で、第三者の原盤・楽曲・アカウント・サービス資格を使用していない。試料はローカル実験用で、音源ファイルの同梱はない。

| ケース | 判定 | 観測 |
| --- | --- | --- |
| 音声専用選択・中継 | PASS | 候補に映像があっても `mp4a.40.2 / vcodec=none` を選択。クライアントは `200 audio/mp4` の body を受け取り、`Location` ヘッダーは付かず。配信元に映像要求はなく、中継した全 100,841 bytes は試料と一致。 |
| 全体取得前の開始 | PASS | 配信元を先頭 8,192 bytes の直後で待機させ、残りを送る前にクライアント側 FFmpeg が先頭 PCM frame 2,048 bytes を出力。全データからデコードした先頭 frame と一致。試料の生成と転送はメモリー上で行い、中継・クライアントともに音源を事前にファイルへ保存しない。 |
| Range と位置確認 | PASS | FFprobe で PTS 3.008 秒の AAC packet を特定。`bytes=38316-38559` に対し `206`、該当 `Content-Range`、244 bytes の内容一致。範囲外は `416`、配信元の範囲不一致は `502`。Android プレーヤーでの時刻シークは NOT_RUN。 |
| 上流 URL の失効・再解決 | PASS | 試験で URL 世代を進め、旧 URL に `410`。同一 ID のマニフェストを 1 回だけ再取得し、強い ETag が一致する場合に同じ Range を中継。媒体が変わった場合は `409` で body なし。音声専用形式が消えた場合は `502` で body なし、映像への切替なし。実サービスの URL 寿命・失効応答は未観測。 |
| 配信元切断・再取得 | PASS | 先頭 8,192 bytes で配信元が TCP 接続を終了。クライアントが宣言長に満たない応答を `IncompleteRead` として検出。受信長を開始位置として明示的に Range を再要求し、同一媒体の残りと結合した結果が試料全体と一致。 |
| 中継の本人スコープ・保存希望 | PASS | 固定の架空 bearer を使い、未認証・別主体には `404`。保存希望には保存許可なしとして `403`。いずれも配信元への音声要求なし。既定の中継経路にファイル書込みなし。 |

実験コードの `410/409/502` と固定 bearer は fixture 内の実装案。実サービスの応答コード、製品 API、認証手段としては未決定。再開の同一性は試料から計算した SHA-256 を strong ETag とする fixture 契約で検査した。実配信元で同等の根拠が得られない場合は未完了分との結合を拒否する。読み取り単位は 8 KiB で、通信時の総メモリー、同時取得数、待ち行列、キャッシュ上限は NOT_RUN。クライアントの Stop から worker 停止まで、TLS、認証情報の預託・撤回、server 停止下のローカル継続再生も NOT_RUN。

## 実サービスの資格・用途・権利

| サービスと認証条件 | 技術動作 | 取得・中継の用途許諾 | 端末保存の用途許諾 | 音源の権利・配布 |
| --- | --- | --- | --- | --- |
| YouTube・認証不要素材 | BLOCKED | Audipp の方式による自動アクセス・音声取得・再送信の資格が未確認 | 取得・任意保存の許諾が未確認 | 実サービス試料・権利者の許諾・音源配布可否が未確認 |
| YouTube・本人認証素材 | BLOCKED | 上記に加え、本人資格と預託方法が未確認 | 取得・任意保存の許諾が未確認 | 実サービス試料・権利者の許諾・音源配布可否が未確認 |
| YouTube Music・認証不要素材 | BLOCKED | 認証不要の適格な試料と、Audipp の取得・中継方法の許諾が未確認 | 取得・任意保存の許諾が未確認 | 実サービス試料・権利者の許諾・音源配布可否が未確認 |
| YouTube Music・本人認証素材（Premium 含む） | BLOCKED | 本人契約・預託方法および Audipp のアクセス手段・中継の許諾が未確認 | Audipp の選択フォルダへの書出し許諾が未確認 | 実サービス試料・権利者の許諾・音源配布可否が未確認 |

YouTube の「Permissions and Restrictions」第 1・3 項には、コンテンツの取得・送信等と自動アクセスの制限がある。YouTube Music の有料サービスでは、条項 5・6 の個人利用の範囲と Google が承認したアクセス手段の条件、および Premium の追加条項を別に確認する。API Developer Policies III.I の第 7〜9 項にある音声分離・バックグラウンド再生制限は API Services の利用に関する資料。Audipp 固有の許諾を確認できる文書はこの調査では得られていない。自作音源の権利、本人の加入契約、個人間利用という範囲、保存希望はそれぞれ別に確認する。2026-11-20 JST に公開条項を確認した。[YouTube 利用規約（日本）](https://www.youtube.com/t/terms?gl=JP) / [YouTube 有料サービス規約・Premium 追加条項](https://jp.youtube.com/t/terms_paidservice) / [YouTube API Developer Policies](https://developers.google.com/youtube/terms/developer-policies)

## 実行環境・ソフトウェアライセンス

| 対象 | 固定版・観測 | ライセンス資料と残る確認 |
| --- | --- | --- |
| yt-dlp | 2026.10.26 PyPI wheel。公開タグ `f264a6cbd1f630894da0f368df00f8aae50140e0`。Generic extractor と音声専用選択を実行 | [固定タグの Unlicense](https://github.com/yt-dlp/yt-dlp/blob/2026.10.26/LICENSE)。別の配布物を採用する場合は同梱物を再確認。 |
| CPython | 3.12.14、Linux x86_64 GNU ABI、Omarchy 4.5.0 / glibc 2.44 | [固定版の PSF ライセンスと同梱物の条項](https://github.com/python/cpython/blob/v3.12.14/LICENSE)。配布時は実際に採用するビルドの通知を確認。 |
| Node.js | 24.21.0、Linux x86_64。yt-dlp の Node 検出と EJS ファイルの構文検査 | [固定版の MIT ライセンスと同梱依存の条項](https://github.com/nodejs/node/blob/v24.21.0/LICENSE)。 |
| yt-dlp-ejs | 0.8.0 PyPI wheel。公開タグ `4fb477f4af56880cfd324c48bd4294a2d2294e50`。yt-dlp 2026.10.26 の固定依存と一致。Node で同梱 JS 2 ファイルの構文検査が成功 | [固定版の README とライセンス記載](https://github.com/yt-dlp/ejs/tree/0.8.0#licensing)。Unlicense、wheel に含まれる meriyah は ISC、astring は MIT。 |
| FFmpeg / FFprobe | n9.0.2。試料の生成・解析とホストのデコード比較に使用 | [FFmpeg のライセンス情報](https://ffmpeg.org/legal.html)。条件はビルド構成による。製品の同梱候補としての審査は NOT_RUN。 |

この組合せのローカル起動・検出・中継は PASS。Debian サーバーへの移植、他 ABI、実サービスでの EJS 動作は NOT_RUN または上記の実サービス BLOCKED。実行ファイルや wheel の再配布可否は成果物確定後に依存一覧・各配布物の条項で確認する。ソフトウェアのライセンス調査と、音源やサービスの利用許諾は個別の判定となる。

### 再現

ホストに CPython 3.12.14、PATH 上の Node.js 24.21.0、FFmpeg/FFprobe n9.0.2、uv を用意し、リポジトリのルートで実行する。パッケージの取得後、実験はループバックだけを使う。

```sh
fixture_env="$(mktemp -d)"
cat >"$fixture_env/requirements.txt" <<'PINS'
yt-dlp==2026.10.26 --hash=sha256:a05c5b5d8562f7e8a62f5334348b406af4930d56d3a65f8021d14af0e22339ae
yt-dlp-ejs==0.8.0 --hash=sha256:79300e5fca7f937a1eeede11f0456862c1b41107ce1d726871e0207424f4bdb4
PINS
uv venv --python 3.12.14 "$fixture_env/venv"
uv pip install --python "$fixture_env/venv/bin/python" --only-binary=:all: --no-deps --require-hashes -r "$fixture_env/requirements.txt"
"$fixture_env/venv/bin/python" scripts/research/test_ytdlp_relay.py -v
```

## 要求追跡と後続作業

承認済みの [中継・保存契約](../../specifications/11_ytdlp_delivery.md) と [R-06](../../specifications/06_decisions.md#利用者に技術選定を丸投げせずこちらで調査する事項) に対する部分結果。Android の所定検証 `assembleDebug / testDebugUnitTest / lintDebug` は成功（188 tests、Lint 0 errors / 13 warnings）。研究スクリプトは手動検証対象であり、CI の変更パス判定は Android・server・音声回帰ジョブの起動対象外。Android API 23/36、実機、DAC/可視化、SAF、音質・性能は NOT_RUN。

| Issue #24 受入条件 | 判定と対応 |
| --- | --- |
| 1. 事前全体保存なしの開始、Range、失効、断線 | PASS（ローカル）、BLOCKED（実サービス）。FR-SRC-11 / DD-PROV-04・05 / TEST-YTDLP-01・RESEARCH-01 の部分結果。 |
| 2. 動画 UI、動画付き形式、上流 URL の受渡しを代替にしない | PASS（ローカル）。音声 body 中継と音声専用形式がない場合の失敗を確認。 |
| 3. 保存希望と保存許可を分離 | PASS（ローカル拒否）、BLOCKED（実サービスの保存）。FR-SRC-12 / DD-PROV-08 / TEST-SAVE-01 の部分結果。SAF の保存成功・取消・容量不足・SD 切断・オフライン再読込は NOT_RUN。 |
| 4. 結果・commit・判定を追跡 | PASS（調査証跡）。NFR-LEGAL-01・02 の個別許諾と製品配布可否は BLOCKED。Q-02 / ADR-004 の方針変更はなし。 |

[#47](https://github.com/4rtofl/Audipp/issues/47) は Debian 上の worker、版更新回帰、HTTPS、資源制限、取消、実配信元で検証できる媒体同一性を引き継ぐ。認証不要ケースもサービスと音源双方の資格を確認できた時点で再試験する。[#48](https://github.com/4rtofl/Audipp/issues/48) は本人認証が必要なケースの利用資格と、ユーザー単位の預託・失効・撤回を確認する。端末実装では同じ媒体を確認できるときだけ中断分を再利用し、保存許可、保存先、確定・再読込を別に検査する。製品の再生・保存の完成判定は保留。
