# #43 libusb 1.0.29 の FD 取込・取消調査（E05-04）

調査コードは `1ef6976f7002c3efc9b8efcaba28a5aafe12d308`、Audipp の起点は `ba8f18b2656d4508cfa381cbd7ded1f94a0a462a`。libusb はタグ `v1.0.29` の `15a7ebb4d426c5ce196684347d2b7cafad862626` に固定した。実行値と環境は [local.txt](local.txt) に記録した。

| 判定対象 | 結果 | 観測と制約 |
| --- | --- | --- |
| Linux バックエンドの FD 所有権 | PASS | 非 root の Linux x86_64。合成 descriptor を持つ通常ファイルを使い、usbfs の ioctl を差し替えた。wrap 成功後の `libusb_close` と wrap 失敗の両方で FD が残り、呼び出し元が複製を閉じても元 FD は有効だった。 |
| 非同期 isochronous の取消と回収 | PASS | 同じ模擬 usbfs で、取消成功から `CANCELLED`、完了と競合した `NOT_FOUND` から `COMPLETED`、切断時の `NO_DEVICE` と `POLLERR` から `NO_DEVICE` callback を各1回回収した。競合時のパケット実長は 8/5 byte。切断後の解放は `NO_DEVICE`。単一の URB・転送を直列に処理するケース。 |
| Android API 23・ABI | PASS | NDK 28.2.13676358 で `arm64-v8a` / `armeabi-v7a` / `x86_64` 向け libusb と JNI をリンク。ELF の形式・API 23 ノート・公開シンボル・動的依存を照合した。ARM のロードと実行は NOT_RUN。 |
| Android JNI の失敗復旧 | PASS | x86_64 の API 23 / 36 で検証 APK を非 root のアプリ UID で実行。Java が所有する `/dev/null` の FD を JNI で複製し、実際の libusb で wrap が `LIBUSB_ERROR_IO` になった後、複製だけを閉じた。USB 許可 FD の取込成功は BLOCKED。 |
| 依存ライセンスの特定 | PASS | libusb のソースヘッダは LGPL-2.1-or-later、同梱文書は LGPL 2.1。調査で生成したライブラリと APK はローカルで使用した。製品配布の適合性は NOT_RUN。 |
| root 不要の独自 USB 経路と機器能力 | BLOCKED | 物理端末の指定 ADB シリアルと試験構成が揃っていない。Android が与えた USB FD の wrap、OS 音声の停止、解放可能な interface の取得、isochronous USB 転送・切断時の回収を計測していない。模擬 `EBUSY` は libusb のエラー変換を確認した結果。 |
| QLT-09 の音声検証 | NOT_RUN | USB 上のバイト列、連続転送、Stop・切替・再接続、内蔵スピーカーへの自動出音の有無、DAC ロックとアナログ過渡は未測定。 |

根拠は [libusb の Android 手順](https://github.com/libusb/libusb/blob/v1.0.29/android/README)、[wrap の API 契約](https://github.com/libusb/libusb/blob/v1.0.29/libusb/core.c#L1311-L1399)、[Linux バックエンド](https://github.com/libusb/libusb/blob/v1.0.29/libusb/os/linux_usbfs.c)、[イベント・取消の実装](https://github.com/libusb/libusb/blob/v1.0.29/libusb/io.c)、[Android の UsbDeviceConnection](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)、[上流の COPYING](https://github.com/libusb/libusb/blob/v1.0.29/COPYING)。

## 機器と未測定項目

| Android 端末 | OS・端末 firmware | DAC 候補（各組み合わせ） | root 不要 FD 取込、排他、isochronous、切断回収 |
| --- | --- | --- | --- |
| Galaxy S25 | Android 16（仕様の対象）、ビルド・firmware 未観測 | ZEN DAC V2 / ECHO-B / ECHO-A | BLOCKED |
| FiiO M21 | 未観測 | ZEN DAC V2 / ECHO-B / ECHO-A | BLOCKED |
| Galaxy S20（追加） | 未観測 | ZEN DAC V2 / ECHO-B / ECHO-A | BLOCKED |

全 DAC の個体・firmware・対応 alternate setting、clock・feedback、転送能力は未観測。実機では権限拒否、許可後の open/wrap 失敗、標準経路の停止を確認できない場合、`BUSY`、転送中の抜去、解放後の再接続も記録する。利用できない構成は失敗した段階と実際のエラーを記録し、別構成で再検証する。

## E05-05（#119）への引き継ぎ

- 既存の許可・descriptor 用 `AndroidUsbHost.inspect` は接続をメソッド内で閉じる。再生用は別に開き、`UsbDeviceConnection` をセッションが所有する案とする。JNI は `F_DUPFD_CLOEXEC` で複製を所有し、明示的な `libusb_context` を `LIBUSB_OPTION_NO_DEVICE_DISCOVERY` 付きで初期化する。wrap 失敗時は JNI が複製を閉じ、Java 側は自身の接続を閉じる。複製と元 FD は同一の open file description を共有するため、セッション中は Java 側で並行 USB I/O をしない。
- OS 出力の停止を観測し、interface を取得できた場合に転送へ進む。Stop・切断では新規 submit と callback からの再 submit を止め、受理済み転送を取り消し、イベント処理を続けて terminal callback が戻るまで待つ。`NOT_FOUND` と `NO_DEVICE` が返った場合にも未回収転送を早期解放しない。回収後に転送・バッファを解放し、interface の解放を試み、`libusb_close` → 複製 FD close → Java 接続 close の順にする。context は全 handle の終了後に破棄する。切断による release の `NO_DEVICE` は記録して残りの解放へ進む。イベント停止・複数転送・世代更新・切断と再接続の競合は実装側で検証する。
- 機器ごとに OS 出力の停止と interface claim を個別に観測する。排他不成立や root が必要と判明した構成では開始を拒否する。実機記録に端末 OS/build/ABI、DAC VID/PID/firmware、対象 interface/alternate/endpoint、各操作の結果と時間、各パケットの status/actual length、最後の callback と解放の順を残す。未知 DAC に vendor 固有制御を送らない。
- 配布前に、対象 APK と対応する libusb のソース・ビルド手順、著作権表示とライセンス文の提供、改変版への差し替えまたは再リンクを可能にする方法を確定する。共有ライブラリの分離は今回の検証 APK で確認済み。製品の署名・配布方式で改変版を利用できる手順と適用する LGPL §6 の条件は導入時に確認する。[上流のライセンス本文](https://github.com/libusb/libusb/blob/v1.0.29/COPYING)

## 再現

Linux x86_64 の非 root ユーザーで、Git、C コンパイラ、GNU リンカー、Make、Autotools、zip、JDK 17、Android SDK Platform 36 / Build Tools 35.0.0 / NDK 28.2.13676358 を用意する。SDK と JDK を環境変数 `ANDROID_HOME` / `JAVA_HOME` に設定する。リポジトリの Linux 補助環境を使う場合は先に `source scripts/linux/android-env.sh` を実行する。

```sh
bash scripts/research/issue-43/build-probes.sh
```

スクリプトは上流ソースを固定してホストの契約試験を実行し、一時ディレクトリに ELF 検査結果とローカル試験用 `probe.apk` を出す。自作 fixture の descriptor は `usbfs_contract.c` 内の 43 byte。外部音源・機器 descriptor は使用していない。

API 23 と 36 の x86_64 エミュレーターを用意し、自分で起動したエミュレーターのシリアルを指定して各 API で実行する。

```sh
adb -s "$serial" install "$build_dir/probe.apk"
adb -s "$serial" shell am instrument -w -r com.audipp.research.usbfd/.UsbFdProbe
```

`INSTRUMENTATION_RESULT: stream=PASS API ...` と `INSTRUMENTATION_CODE: -1` を照合する。製品アプリの Debug build / unit test / lint も別途実行した。この変更のパスは既存 CI の Android・Server・Audio ジョブの起動条件外で、ホスト試験と検証 APK はローカルの証跡で追跡する。

## 受入条件と追跡

| Issue #43 の受入条件 | 判定 |
| --- | --- |
| 技術成立・API23/ABI・依存ライセンス・機器能力を別判定 | PASS。上表にケース別の判定を記録。 |
| root 必須または排他不能を記録し、推測した制御で補わない | BLOCKED。実機での root 要否と排他の可否は未判定。 |
| E05-05 へ結果を渡し、研究と機能完成を区別 | PASS。上記の引き継ぎに所有権・エラー時の復旧・実機で必要な証跡を記録。独自 USB の製品判定は保留。 |
| 要求・試験・commit の追跡と未実施の扱い | PASS。[結果台帳](../../traceability/results.json)に FR-AUD-03 / FR-AUD-05 / FR-PLT-01 と関連試験の個別結果、検証コミット、制約を記録。Issue が参照する TEST-RESEARCH-01 の DAC・USB 調査分と NFR-LEGAL-01 の依存ライセンスの判定は本書に記録。台帳には現行 DD-TRACE にある要件と試験の対応を記載。 |

関連設計は DD-AUD-01 / DD-AUD-04 / ADR-002 / R-03。実機の未判定項目は #43 に残し、#119 では clock / feedback・転送実装と併せて検証する。
