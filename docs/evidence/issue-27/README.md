# Issue #27 の端末上の検証記録

対象は [設定保存 v1](../../contracts/preferences-v1.md) の DataStore で、アプリのプロセスを終了してから読み直したときの動作を確かめた。JVM の `PreferenceStoreTest` は DataStore を閉じて開き直す試験なので、Android のプロセス終了や、アプリ専用領域の実ファイルへの読み書きは確かめられない。この記録はその差を埋める。

## 2026-11-22 の実行結果

試験したコミットは `9fa8a9319e666f2899b9192a99fd7347dd4b9677`。x86_64 のエミュレーター2台（API 23 と API 36、イメージは `system-images;android-*;default;x86_64`）で、`PreferenceStoreRestartTest` の6段階を順に実行し、どちらも全段階が成功した。各段階の前に `am force-stop` でアプリを終了し、`com.audipp` のプロセスが残っていないことを確かめてから、新しい instrumentation プロセスで実行した。台帳の記録は `E00-03-preferences-process-restart-2026-11-22`。[検証台帳](../../traceability/results.json)

| 段階 | 確かめたこと |
|---|---|
| `FRESH` | 設定ファイルを消した状態で、テーマ System・Visualizer 無効 30fps・公開範囲 PRIVATE・同意なしを読む。全用途で外部送信が許可されない |
| `DEFAULTS_AFTER_RESTART` | 再起動後も同じ初回値と外部送信無効を読む |
| `WRITE` | テーマ DARK、Visualizer 有効 60fps、FRIENDS、SCROBBLING の同意 v2、CLOUD_SYNC の撤回 v1 を保存する |
| `PERSISTED_AFTER_RESTART` | 再起動後に保存値を読む。SCROBBLING は v2 でだけ許可され、v1 と CLOUD_SYNC では許可されない |
| `CORRUPT` | 設定ファイルを壊れたバイト列に置き換える |
| `CORRUPT_AFTER_RESTART` | 再起動後の読取と書込が、どちらも `CORRUPT` の `PreferenceStorageException` になる。ファイルの中身は置き換えたときのまま残る |

試験が成功を誤って返していないことも、順序を崩した実行で確かめた。`FRESH` の直後に `PERSISTED_AFTER_RESTART` を実行すると `expected:<DARK> but was:<SYSTEM>` で失敗し、壊す前に `CORRUPT_AFTER_RESTART` を実行すると「READ が失敗しない」ことで失敗した。プロセスの有無の確認は、アプリを起動した状態で1件、`force-stop` の後で0件と数えられることを両 API で確かめてある。API 26 以降は `ps -A`、API 23 は `ps` を使う。

## 再実行の手順

Debug APK とテスト APK を入れた端末で、`SERIAL` を対象の ADB シリアルに置き換えて実行する。段階の順序は変えない。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for phase in FRESH DEFAULTS_AFTER_RESTART WRITE PERSISTED_AFTER_RESTART CORRUPT CORRUPT_AFTER_RESTART; do
  adb -s SERIAL shell am force-stop com.audipp
  adb -s SERIAL shell am instrument -w -r -e preferencesPhase "$phase" \
    -e class com.audipp.data.preferences.PreferenceStoreRestartTest \
    com.audipp.test/androidx.test.runner.AndroidJUnitRunner
done
```

引数 `preferencesPhase` がない実行（`connectedDebugAndroidTest` など）では、この試験は skip になる。最後の段階で設定ファイルを消すので、端末のアプリ設定は初回の状態に戻る。

## 確かめていない範囲

- 物理端末。ADB シリアルの指定がないため、エミュレーターだけで実行した
- 書込中の電源断と、ストレージが満杯のときの書込失敗。書込失敗は JVM の障害注入試験だけで確かめている
- 画面からの設定変更と保存失敗の表示。#40 の [検証記録](../issue-40/README.md) で扱う
- Visualizer の処理、外部送信、Room、秘密保管との接続。各担当 Issue で扱う
