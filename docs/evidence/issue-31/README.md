# Issue #31 の検証記録

対象は [再生計画の解決 v1](../../contracts/source-resolution-v1.md) の `SourceResolver`。2026-11-22 に、受入条件ごとの JVM 試験を照合し、`PlaybackService` と同じ `AndroidDocumentAccess` を実際の ContentResolver に接続した端末試験を追加した。試験したコミットは `dca0437d996679d46d7abecece8f7fe714a4b2f4`。[検証台帳](../../traceability/results.json)

## 受入条件と試験

| 受入条件 | JVM（`SourceResolverTest`） | 端末（`SourceResolverDeviceTest`、API 36） |
|---|---|---|
| 同じ NAS 音源で、端末再生と Renderer 制御の Plan を区別する | `sameNasVariantHasDifferentDeviceAndRendererPlans` | NAS の実アダプターがないため対象外 |
| content URI を Renderer の到達先とみなさず、未許諾を許可しない | `contentUriCannotBeSentToRendererEvenByRegisteredAdapter`、`rendererRejectsUnsafeUrlsAndRequiresCurrentReachabilityEvidence`、`deniedUnreviewedAndMissingProviderPermissionsBlockBeforeAdapterIo`、`permissionRevokedWhileResolvingRejectsDelayedStream` | `contentUriIsNeverHandedToARenderer`：未登録なら `ADAPTER_UNAVAILABLE`、ローカル用アダプターを Renderer に登録しても `INVALID_DESCRIPTOR` で、提供元を一度も開かない |
| 指定版が使えないとき別版を勝手に再生せず、Provider の REMOTE／HANDOFF を直接再生の達成にしない | `unavailableSourceOnlyReturnsCandidateIdsAndNeverResolvesAnotherVariant`、`adapterCannotReturnAnotherVariantOrAnotherSource`、`remoteAndHandoffAreAuxiliaryResultsAndUseControlPermission` | `missingDocumentOffersOtherVersionsWithoutOpeningThem`：消失した指定版は `SOURCE_MISSING` になり、別版は ID を返すだけで開かない |

端末試験では、ほかに次の2件も確かめた。

- `readableDocumentResolvesToTheSelectedVersionAndClosesTheProbe`：読める文書は指定版のまま `LOCAL_DIRECT` の Plan になる。確認のために開いた FD は閉じられている
- `revokedGrantAsksForPermissionAgain`：提供元が `SecurityException` を返すと `PERMISSION_REQUIRED` になる

## 実行結果

- API 36 の x86_64 エミュレーターで `SourceResolverDeviceTest` 4件が成功した
- API 23 では実行していない。試験用の提供元を ContentResolver につなぐ `ContentResolver.wrap` が API 29 からしかないため、`@SdkSuppress(minSdkVersion = 29)` で対象外になる（実行結果は0件）
- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` で `SourceResolverTest` を含む単体試験が成功した。件数は対応 PR に記録する

```sh
adb -s SERIAL shell am instrument -w -r -e class com.audipp.playback.source.SourceResolverDeviceTest \
  com.audipp.test/androidx.test.runner.AndroidJUnitRunner
```

## 確かめていない範囲

- API 23 での実 ContentResolver 経由の解決、物理端末（ADB シリアルの指定なし）
- システムの文書提供元（SAF ピッカーで許可した実フォルダ）を経由した解決。試験用の提供元だけを使った
- Provider・NETWORK・Renderer の実アダプター。本 Issue では未登録時に `ADAPTER_UNAVAILABLE` を返すところまでを扱い、接続は #49・#73・#74・#105・#106 などで行う
