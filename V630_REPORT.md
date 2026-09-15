# V630_REPORT

## 開工基線

HEAD: b29acc8b798f4af3ca7a14398a74f8c6673dd411

已追蹤檔乾淨；未追蹤檔保留。build.gradle 與 src/test 為工單 D/E 明定例外。

```text
app/src/main/java/com/simon/voiceime/SimonIMEService.java 4044 lines md5=6c8a658d50ec5d1011c8a98feb5b0875
app/src/main/java/com/simon/voiceime/ClipboardHelper.java 194 lines md5=e7ca933c29abe038c93aafdd157b5501
app/build.gradle 81 lines md5=e6e11180a252890b3ecbcdbae284067e
app/src/main/java/com/simon/voiceime/SilenceWatchdog.java NEW (absent)
app/src/main/java/com/simon/voiceime/TextLossGuard.java NEW (absent)
app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java NEW (absent)
app/src/test/java/com/simon/voiceime/TextLossGuardTest.java NEW (absent)
```

## 實作與接點

- 世代完成與結果長度：`app/src/main/java/com/simon/voiceime/SimonIMEService.java` 舊 L628 → 新 L652。
- 錄音迴圈：`app/src/main/java/com/simon/voiceime/SimonIMEService.java` 舊 L1458 → 新 L1609。
- WS final：`app/src/main/java/com/simon/voiceime/SimonIMEService.java` 舊 L1731 → 新 L1916。
- 停止錄音與實錄時長：`app/src/main/java/com/simon/voiceime/SimonIMEService.java` 舊 L1855 → 新 L2058。
- 端上預覽的世代綁定：`app/src/main/java/com/simon/voiceime/SimonIMEService.java` 舊 L2098 → 新 L2307。
- HTTP 失敗救援：`app/src/main/java/com/simon/voiceime/SimonIMEService.java` 舊 L2279 → 新 L2492。
- 伺服器賽跑輸家：`app/src/main/java/com/simon/voiceime/SimonIMEService.java` 舊 L2412 → 新 L2631。
- 端上賽跑輸家：`app/src/main/java/com/simon/voiceime/SimonIMEService.java` 舊 L2530 → 新 L2758。
- 換字文字請求失敗：`app/src/main/java/com/simon/voiceime/SimonIMEService.java` 舊 L2636 → 新 L2870。
- REPLACE 刪除前備份與短結果救援：`app/src/main/java/com/simon/voiceime/SimonIMEService.java` 舊 L2680 → 新 L2917。

- 新增 `SilenceWatchdog.java`：純 JVM 數位靜音判斷；錯誤累計、警示、單次靜音重建；不是一般說話停頓判斷。
- 新增 `TextLossGuard.java`：長度差及倍率門檻；不選擇提交版本。
- 新增兩個對應測試檔，測試數與結果見斷言 3 的原始 XML。
- `app/build.gradle` 舊／新 L13–14：phone 預設版本 66／6.29 → 67／6.30；watch 區塊未改。
- `ClipboardHelper.java` 原有 public `addToHistory` 只寫內建歷史，故不需修改。
- 保存候選與寫剪貼簿均在主緒；WS 預覽先比較後清空。晚到 WS final、兩個 process-text 賽跑輸家均補保存及長度／來源日誌。
- 新增世代結果長度、停止時預覽快照、實錄時長，沿既有最多八世代的清理方式。REPLACE 救援不清 PCM，不覆蓋伺服器插入結果。
- 麥克風重建與停止／銷毀共用鎖；重建失敗留警示並抑制同次錄音繼續重建，不自動停止錄音。

### 可覆蓋門檻

SharedPreferences 檔 `simon_ime_prefs`：`silent_rms` (float)、`silent_warn_ms` (long)、`silent_restart_ms` (long)、`read_error_limit` (int)、`text_loss_min_extra_chars` (int)、`text_loss_min_ratio` (float)、`replace_short_chars` (int)、`replace_long_audio_ms` (long)。預設分別為 2/32768、3000、6000、3、10、1.5、6、5000。看門狗每次錄音讀取；文字與 REPLACE 門檻於 Service 啟動時讀取。

### 內部唯讀檢查與修正對帳

同家族 `quality_inspector` 指出並採納：晚到 WS final 輸家救援、WS 候選擷取移入主緒、以實錄時間判斷五秒門檻、端上預覽回呼綁定原始世代。最後一次唯讀複查的 Service SHA256：`22afb0d5aa61f3d1296c29f5c2a42012f58270dd3a0929cc3981b11834612792`。此為同家族靜態檢查；未做跨家族複核，也不是裝置競態重現。

## 第 4 節驗收斷言：指令與原文輸出

### 1. 變更範圍

```bash
git diff --stat
```

```text
 app/build.gradle                                   |   4 +-
 .../java/com/simon/voiceime/SimonIMEService.java   | 316 +++++++++++++++++++--
 2 files changed, 287 insertions(+), 33 deletions(-)
```

退出碼：`0`。

新增檔未加入 index，`git diff --stat` 不會列出；以下逐檔補原始 stat（no-index 的退出碼 1 表示有差異）。未執行 git add。

```bash
git diff --no-index --stat /dev/null app/src/main/java/com/simon/voiceime/SilenceWatchdog.java
```

```text
 .../java/com/simon/voiceime/SilenceWatchdog.java   | 56 ++++++++++++++++++++++
 1 file changed, 56 insertions(+)
```

退出碼：`1`。

```bash
git diff --no-index --stat /dev/null app/src/main/java/com/simon/voiceime/TextLossGuard.java
```

```text
 .../main/java/com/simon/voiceime/TextLossGuard.java  | 20 ++++++++++++++++++++
 1 file changed, 20 insertions(+)
```

退出碼：`1`。

```bash
git diff --no-index --stat /dev/null app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java
```

```text
 .../com/simon/voiceime/SilenceWatchdogTest.java    | 63 ++++++++++++++++++++++
 1 file changed, 63 insertions(+)
```

退出碼：`1`。

```bash
git diff --no-index --stat /dev/null app/src/test/java/com/simon/voiceime/TextLossGuardTest.java
```

```text
 .../java/com/simon/voiceime/TextLossGuardTest.java | 27 ++++++++++++++++++++++
 1 file changed, 27 insertions(+)
```

退出碼：`1`。

```bash
git diff --check
```

```text
```

退出碼：`0`。

### 2. 接線位置

```bash
grep -n "SilenceWatchdog\|TextLossGuard.shouldRescue\|addToHistory" app/src/main/java/com/simon/voiceime/SimonIMEService.java
```

```text
1490:    private boolean restartMicrophone(int bufferSize, int gen, SilenceWatchdog.Verdict reason,
1494:            Log.w(TAG, "[SilenceWatchdog] restart reason=" + reason + " readResult=" + readResult);
1500:                    Log.w(TAG, "[SilenceWatchdog] stop failed", e);
1503:                if (reason == SilenceWatchdog.Verdict.SILENT_RESTART) {
1515:                        Log.w(TAG, "[SilenceWatchdog] VOICE_RECOGNITION unavailable", e);
1538:                        Log.w(TAG, "[SilenceWatchdog] replacement release failed", releaseError);
1541:                Log.w(TAG, "[SilenceWatchdog] rebuild failed readResult=" + readResult, e);
1567:        if (!isWatchService() && TextLossGuard.shouldRescue(
1569:            clipboardHelper.addToHistory(candidate);
1598:                    clipboardHelper.addToHistory(rescued);
1724:            SilenceWatchdog watchdog = new SilenceWatchdog(recordingStartedMs,
1725:                    prefs.getFloat("silent_rms", (float) SilenceWatchdog.SILENT_RMS),
1726:                    prefs.getLong("silent_warn_ms", SilenceWatchdog.SILENT_WARN_MS),
1727:                    prefs.getLong("silent_restart_ms", SilenceWatchdog.SILENT_RESTART_MS),
1728:                    prefs.getInt("read_error_limit", SilenceWatchdog.READ_ERROR_LIMIT));
1747:                    SilenceWatchdog.Verdict verdict = watchdog.feed(read, normalizedRms, observedMs);
1748:                    if (verdict == SilenceWatchdog.Verdict.SILENT_WARN) {
1751:                    } else if (!rebuildFailed && (verdict == SilenceWatchdog.Verdict.READ_ERROR
1752:                            || verdict == SilenceWatchdog.Verdict.SILENT_RESTART)) {
1921:                            final boolean rescue = !isWatchService() && TextLossGuard.shouldRescue(
1936:                                    if (!isWatchService() && TextLossGuard.shouldRescue(
1938:                                        clipboardHelper.addToHistory(discarded);
1953:                                clipboardHelper.addToHistory(candidate);
2702:                                    if (!isWatchService() && TextLossGuard.shouldRescue(
2704:                                        clipboardHelper.addToHistory(text);
2956:                                clipboardHelper.addToHistory((before == null ? "" : before.toString())
```

退出碼：`0`。

### 3. 單元測試

```bash
grep '<testsuite ' app/build/test-results/testPhoneReleaseUnitTest/TEST-*.xml
```

```text
app/build/test-results/testPhoneReleaseUnitTest/TEST-com.simon.voiceime.KeyboardPagerTest.xml:<testsuite name="com.simon.voiceime.KeyboardPagerTest" tests="6" skipped="0" failures="0" errors="0" timestamp="2026-09-15T11:11:18" hostname="Simon" time="0.003">
app/build/test-results/testPhoneReleaseUnitTest/TEST-com.simon.voiceime.SilenceWatchdogTest.xml:<testsuite name="com.simon.voiceime.SilenceWatchdogTest" tests="9" skipped="0" failures="0" errors="0" timestamp="2026-09-15T11:11:18" hostname="Simon" time="0.004">
app/build/test-results/testPhoneReleaseUnitTest/TEST-com.simon.voiceime.SwipeGestureJudgeTest.xml:<testsuite name="com.simon.voiceime.SwipeGestureJudgeTest" tests="10" skipped="0" failures="0" errors="0" timestamp="2026-09-15T11:11:18" hostname="Simon" time="0.003">
app/build/test-results/testPhoneReleaseUnitTest/TEST-com.simon.voiceime.TextLossGuardTest.xml:<testsuite name="com.simon.voiceime.TextLossGuardTest" tests="6" skipped="0" failures="0" errors="0" timestamp="2026-09-15T11:11:18" hostname="Simon" time="0.001">
```

退出碼：`0`。

### 4. 建置與產物版本

最後一次實跑建置指令（stdout/stderr 原文保存在 `out/v630-build.log`）：

```bash
JAVA_HOME=/home/simon/.local/jdk PATH="/home/simon/.local/jdk/bin:/usr/bin:/bin:$PATH" GRADLE_USER_HOME=/home/simon/.gradle_clean ANDROID_HOME=/home/simon/android-sdk ./gradlew --no-daemon --project-cache-dir out/v630-gradle-cache testPhoneReleaseUnitTest assemblePhoneRelease compileWatchReleaseJavaWithJavac > out/v630-build.log 2>&1
```

建置行程退出碼：`0`。

```bash
cat out/v630-build.log
```

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.5/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build 
> Task :app:preBuild UP-TO-DATE
> Task :app:prePhoneReleaseBuild UP-TO-DATE
> Task :app:generatePhoneReleaseBuildConfig UP-TO-DATE
> Task :app:javaPreCompilePhoneRelease UP-TO-DATE
> Task :app:checkPhoneReleaseAarMetadata UP-TO-DATE
> Task :app:generatePhoneReleaseResValues UP-TO-DATE
> Task :app:mapPhoneReleaseSourceSetPaths UP-TO-DATE
> Task :app:generatePhoneReleaseResources UP-TO-DATE
> Task :app:mergePhoneReleaseResources UP-TO-DATE
> Task :app:packagePhoneReleaseResources UP-TO-DATE
> Task :app:parsePhoneReleaseLocalResources UP-TO-DATE
> Task :app:createPhoneReleaseCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksPhoneRelease UP-TO-DATE
> Task :app:processPhoneReleaseMainManifest UP-TO-DATE
> Task :app:processPhoneReleaseManifest UP-TO-DATE
> Task :app:processPhoneReleaseManifestForPackage UP-TO-DATE
> Task :app:processPhoneReleaseResources UP-TO-DATE
> Task :app:compilePhoneReleaseJavaWithJavac
> Task :app:prePhoneReleaseUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompilePhoneReleaseUnitTest UP-TO-DATE
> Task :app:processPhoneReleaseJavaRes NO-SOURCE
> Task :app:processPhoneReleaseUnitTestJavaRes NO-SOURCE
> Task :app:extractProguardFiles UP-TO-DATE
> Task :app:bundlePhoneReleaseClassesToCompileJar
> Task :app:bundlePhoneReleaseClassesToRuntimeJar
> Task :app:generatePhoneReleaseLintVitalReportModel
> Task :app:compilePhoneReleaseUnitTestJavaWithJavac UP-TO-DATE
> Task :app:testPhoneReleaseUnitTest
> Task :app:mergePhoneReleaseJniLibFolders UP-TO-DATE
> Task :app:mergePhoneReleaseNativeLibs UP-TO-DATE
> Task :app:stripPhoneReleaseDebugSymbols UP-TO-DATE
> Task :app:extractPhoneReleaseNativeSymbolTables UP-TO-DATE
> Task :app:mergePhoneReleaseNativeDebugMetadata UP-TO-DATE
> Task :app:checkPhoneReleaseDuplicateClasses UP-TO-DATE
> Task :app:dexBuilderPhoneRelease
> Task :app:desugarPhoneReleaseFileDependencies UP-TO-DATE
> Task :app:mergeExtDexPhoneRelease UP-TO-DATE
> Task :app:mergePhoneReleaseArtProfile UP-TO-DATE
> Task :app:mergePhoneReleaseGlobalSynthetics UP-TO-DATE
> Task :app:mergePhoneReleaseShaders UP-TO-DATE
> Task :app:compilePhoneReleaseShaders NO-SOURCE
> Task :app:generatePhoneReleaseAssets UP-TO-DATE
> Task :app:mergePhoneReleaseAssets UP-TO-DATE
> Task :app:compressPhoneReleaseAssets UP-TO-DATE
> Task :app:mergePhoneReleaseJavaResource UP-TO-DATE
> Task :app:optimizePhoneReleaseResources UP-TO-DATE
> Task :app:collectPhoneReleaseDependencies UP-TO-DATE
> Task :app:sdkPhoneReleaseDependencyData UP-TO-DATE
> Task :app:validateSigningPhoneRelease UP-TO-DATE
> Task :app:writePhoneReleaseAppMetadata UP-TO-DATE
> Task :app:writePhoneReleaseSigningConfigVersions UP-TO-DATE
> Task :app:preWatchReleaseBuild UP-TO-DATE
> Task :app:generateWatchReleaseBuildConfig UP-TO-DATE
> Task :app:javaPreCompileWatchRelease UP-TO-DATE
> Task :app:checkWatchReleaseAarMetadata UP-TO-DATE
> Task :app:generateWatchReleaseResValues UP-TO-DATE
> Task :app:mapWatchReleaseSourceSetPaths UP-TO-DATE
> Task :app:generateWatchReleaseResources UP-TO-DATE
> Task :app:mergeWatchReleaseResources UP-TO-DATE
> Task :app:packageWatchReleaseResources UP-TO-DATE
> Task :app:parseWatchReleaseLocalResources UP-TO-DATE
> Task :app:createWatchReleaseCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksWatchRelease UP-TO-DATE
> Task :app:processWatchReleaseMainManifest UP-TO-DATE
> Task :app:processWatchReleaseManifest UP-TO-DATE
> Task :app:processWatchReleaseManifestForPackage UP-TO-DATE
> Task :app:processWatchReleaseResources UP-TO-DATE

> Task :app:compileWatchReleaseJavaWithJavac
Note: /home/simon/simon-voice-ime/app/src/watch/java/com/simon/voiceime/WatchIMEService.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.

> Task :app:mergeDexPhoneRelease
> Task :app:compilePhoneReleaseArtProfile
> Task :app:packagePhoneRelease
> Task :app:createPhoneReleaseApkListingFileRedirect UP-TO-DATE
> Task :app:lintVitalAnalyzePhoneRelease
> Task :app:lintVitalReportPhoneRelease UP-TO-DATE
> Task :app:lintVitalPhoneRelease
> Task :app:assemblePhoneRelease

BUILD SUCCESSFUL in 23s
65 actionable tasks: 12 executed, 53 up-to-date
```

退出碼：`0`。

```bash
/home/simon/android-sdk/build-tools/34.0.0/aapt dump badging dist/simon-voice-ime-phone-6.30-release-20260915.apk
```

```text
package: name='com.simon.voiceime' versionCode='67' versionName='6.30' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='34' compileSdkVersionCodename='14'
sdkVersion:'26'
targetSdkVersion:'34'
uses-permission: name='android.permission.RECORD_AUDIO'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.WAKE_LOCK'
application-label:'Simon Voice IME'
application-label-af:'Simon Voice IME'
application-label-am:'Simon Voice IME'
application-label-ar:'Simon Voice IME'
application-label-as:'Simon Voice IME'
application-label-az:'Simon Voice IME'
application-label-be:'Simon Voice IME'
application-label-bg:'Simon Voice IME'
application-label-bn:'Simon Voice IME'
application-label-bs:'Simon Voice IME'
application-label-ca:'Simon Voice IME'
application-label-cs:'Simon Voice IME'
application-label-da:'Simon Voice IME'
application-label-de:'Simon Voice IME'
application-label-el:'Simon Voice IME'
application-label-en-AU:'Simon Voice IME'
application-label-en-CA:'Simon Voice IME'
application-label-en-GB:'Simon Voice IME'
application-label-en-IN:'Simon Voice IME'
application-label-en-XC:'Simon Voice IME'
application-label-es:'Simon Voice IME'
application-label-es-US:'Simon Voice IME'
application-label-et:'Simon Voice IME'
application-label-eu:'Simon Voice IME'
application-label-fa:'Simon Voice IME'
application-label-fi:'Simon Voice IME'
application-label-fr:'Simon Voice IME'
application-label-fr-CA:'Simon Voice IME'
application-label-gl:'Simon Voice IME'
application-label-gu:'Simon Voice IME'
application-label-hi:'Simon Voice IME'
application-label-hr:'Simon Voice IME'
application-label-hu:'Simon Voice IME'
application-label-hy:'Simon Voice IME'
application-label-in:'Simon Voice IME'
application-label-is:'Simon Voice IME'
application-label-it:'Simon Voice IME'
application-label-iw:'Simon Voice IME'
application-label-ja:'Simon Voice IME'
application-label-ka:'Simon Voice IME'
application-label-kk:'Simon Voice IME'
application-label-km:'Simon Voice IME'
application-label-kn:'Simon Voice IME'
application-label-ko:'Simon Voice IME'
application-label-ky:'Simon Voice IME'
application-label-lo:'Simon Voice IME'
application-label-lt:'Simon Voice IME'
application-label-lv:'Simon Voice IME'
application-label-mk:'Simon Voice IME'
application-label-ml:'Simon Voice IME'
application-label-mn:'Simon Voice IME'
application-label-mr:'Simon Voice IME'
application-label-ms:'Simon Voice IME'
application-label-my:'Simon Voice IME'
application-label-nb:'Simon Voice IME'
application-label-ne:'Simon Voice IME'
application-label-nl:'Simon Voice IME'
application-label-or:'Simon Voice IME'
application-label-pa:'Simon Voice IME'
application-label-pl:'Simon Voice IME'
application-label-pt:'Simon Voice IME'
application-label-pt-BR:'Simon Voice IME'
application-label-pt-PT:'Simon Voice IME'
application-label-ro:'Simon Voice IME'
application-label-ru:'Simon Voice IME'
application-label-si:'Simon Voice IME'
application-label-sk:'Simon Voice IME'
application-label-sl:'Simon Voice IME'
application-label-sq:'Simon Voice IME'
application-label-sr:'Simon Voice IME'
application-label-sr-Latn:'Simon Voice IME'
application-label-sv:'Simon Voice IME'
application-label-sw:'Simon Voice IME'
application-label-ta:'Simon Voice IME'
application-label-te:'Simon Voice IME'
application-label-th:'Simon Voice IME'
application-label-tl:'Simon Voice IME'
application-label-tr:'Simon Voice IME'
application-label-uk:'Simon Voice IME'
application-label-ur:'Simon Voice IME'
application-label-uz:'Simon Voice IME'
application-label-vi:'Simon Voice IME'
application-label-zh-CN:'Simon Voice IME'
application-label-zh-HK:'Simon Voice IME'
application-label-zh-TW:'Simon Voice IME'
application-label-zu:'Simon Voice IME'
application-icon-160:'res/1g.xml'
application-icon-240:'res/1g.xml'
application-icon-320:'res/1g.xml'
application: label='Simon Voice IME' icon='res/1g.xml'
feature-group: label=''
  uses-feature: name='android.hardware.faketouch'
  uses-implied-feature: name='android.hardware.faketouch' reason='default feature for all apps'
  uses-feature: name='android.hardware.microphone'
  uses-implied-feature: name='android.hardware.microphone' reason='requested android.permission.RECORD_AUDIO permission'
provides-component:'ime'
main
other-activities
supports-screens: 'small' 'normal' 'large' 'xlarge'
supports-any-density: 'true'
locales: '--_--' 'af' 'am' 'ar' 'as' 'az' 'be' 'bg' 'bn' 'bs' 'ca' 'cs' 'da' 'de' 'el' 'en-AU' 'en-CA' 'en-GB' 'en-IN' 'en-XC' 'es' 'es-US' 'et' 'eu' 'fa' 'fi' 'fr' 'fr-CA' 'gl' 'gu' 'hi' 'hr' 'hu' 'hy' 'in' 'is' 'it' 'iw' 'ja' 'ka' 'kk' 'km' 'kn' 'ko' 'ky' 'lo' 'lt' 'lv' 'mk' 'ml' 'mn' 'mr' 'ms' 'my' 'nb' 'ne' 'nl' 'or' 'pa' 'pl' 'pt' 'pt-BR' 'pt-PT' 'ro' 'ru' 'si' 'sk' 'sl' 'sq' 'sr' 'sr-Latn' 'sv' 'sw' 'ta' 'te' 'th' 'tl' 'tr' 'uk' 'ur' 'uz' 'vi' 'zh-CN' 'zh-HK' 'zh-TW' 'zu'
densities: '160' '240' '320'
native-code: 'arm64-v8a' 'armeabi-v7a'
```

退出碼：`0`。

### 5. 未做／未驗證

實機靜音警示、藍牙情境、換鍵逾時救援未在裝置驗證。也未做實機 InputConnection 刪字備份、快速切換兩段錄音的競態測試、端上模型救援可用性或 APK 安裝測試。JVM 測試只驗兩個純邏輯類，不能取代 Android 音訊、IME 與網路整合驗證。

## 產物與雜湊

產物從 `app/build/outputs/apk/phone/release/app-phone-release.apk` 複製到工單指定 dist 路徑；複製前目標不存在。

```bash
sha256sum app/build/outputs/apk/phone/release/app-phone-release.apk dist/simon-voice-ime-phone-6.30-release-20260915.apk
```

```text
b0beef387b3893feb9929baa7694964b93c7fafc0dac850c193d49b7e7dcae31  app/build/outputs/apk/phone/release/app-phone-release.apk
b0beef387b3893feb9929baa7694964b93c7fafc0dac850c193d49b7e7dcae31  dist/simon-voice-ime-phone-6.30-release-20260915.apk
```

退出碼：`0`。

## 收工行數與 MD5

```bash
wc -l app/src/main/java/com/simon/voiceime/SimonIMEService.java app/src/main/java/com/simon/voiceime/ClipboardHelper.java app/build.gradle app/src/main/java/com/simon/voiceime/SilenceWatchdog.java app/src/main/java/com/simon/voiceime/TextLossGuard.java app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java app/src/test/java/com/simon/voiceime/TextLossGuardTest.java
```

```text
  4298 app/src/main/java/com/simon/voiceime/SimonIMEService.java
   194 app/src/main/java/com/simon/voiceime/ClipboardHelper.java
    81 app/build.gradle
    56 app/src/main/java/com/simon/voiceime/SilenceWatchdog.java
    20 app/src/main/java/com/simon/voiceime/TextLossGuard.java
    63 app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java
    27 app/src/test/java/com/simon/voiceime/TextLossGuardTest.java
  4739 total
```

退出碼：`0`。

```bash
md5sum app/src/main/java/com/simon/voiceime/SimonIMEService.java app/src/main/java/com/simon/voiceime/ClipboardHelper.java app/build.gradle app/src/main/java/com/simon/voiceime/SilenceWatchdog.java app/src/main/java/com/simon/voiceime/TextLossGuard.java app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java app/src/test/java/com/simon/voiceime/TextLossGuardTest.java
```

```text
74c8e520d1575dd759f44bedfa0518a7  app/src/main/java/com/simon/voiceime/SimonIMEService.java
e7ca933c29abe038c93aafdd157b5501  app/src/main/java/com/simon/voiceime/ClipboardHelper.java
a4fc368efa4b6f61a0d1d67f833de5fa  app/build.gradle
379cf6c831dd210a1ebf21fd1cf1f94e  app/src/main/java/com/simon/voiceime/SilenceWatchdog.java
8e81d4b6da2709da5c7b205774be5c8e  app/src/main/java/com/simon/voiceime/TextLossGuard.java
7681dd43dd389c97afaae0bccbd20bea  app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java
7e16518ee3645114c9feb4ce6ddfd672  app/src/test/java/com/simon/voiceime/TextLossGuardTest.java
```

退出碼：`0`。

```bash
sha256sum app/src/main/java/com/simon/voiceime/SimonIMEService.java
```

```text
22afb0d5aa61f3d1296c29f5c2a42012f58270dd3a0929cc3981b11834612792  app/src/main/java/com/simon/voiceime/SimonIMEService.java
```

退出碼：`0`。

```bash
git rev-parse HEAD
```

```text
b29acc8b798f4af3ca7a14398a74f8c6673dd411
```

退出碼：`0`。

```bash
git status --short --untracked-files=no
```

```text
 M app/build.gradle
 M app/src/main/java/com/simon/voiceime/SimonIMEService.java
```

退出碼：`0`。

開工／收工雜湊、tracked diff 與唯讀檢查錨相互核對；這些是端點證據，不能單靠 MD5 證明期間絕無其他程序寫入。未 commit、tag、push 或建立 release；未改 app/src/watch、.worktrees、伺服器與 ~/.claude。工單、報告、dist 及 out 是交付／建置紀錄，不加入 source diff。

## 首輪 red 指令與範圍

新測試先於新類建立。此輪失敗是缺少兩個新介面的編譯錯誤，只證明新介面當時不存在，未重現實機靜音或文字遺失事故。

```bash
JAVA_HOME=/home/simon/.local/jdk PATH="/home/simon/.local/jdk/bin:/usr/bin:/bin:$PATH" GRADLE_USER_HOME=/home/simon/.gradle_clean ANDROID_HOME=/home/simon/android-sdk ./gradlew --no-daemon --project-cache-dir out/v630-gradle-cache testPhoneReleaseUnitTest > out/v630-red.log 2>&1
```

首輪退出碼：`1`。原文：

```bash
cat out/v630-red.log
```

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.5/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build 
> Task :app:preBuild UP-TO-DATE
> Task :app:prePhoneReleaseBuild UP-TO-DATE
> Task :app:generatePhoneReleaseBuildConfig
> Task :app:javaPreCompilePhoneRelease
> Task :app:generatePhoneReleaseResValues
> Task :app:checkPhoneReleaseAarMetadata
> Task :app:mapPhoneReleaseSourceSetPaths
> Task :app:generatePhoneReleaseResources
> Task :app:packagePhoneReleaseResources
> Task :app:createPhoneReleaseCompatibleScreenManifests
> Task :app:extractDeepLinksPhoneRelease
> Task :app:mergePhoneReleaseResources
> Task :app:parsePhoneReleaseLocalResources
> Task :app:processPhoneReleaseMainManifest
> Task :app:processPhoneReleaseManifest
> Task :app:prePhoneReleaseUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompilePhoneReleaseUnitTest
> Task :app:processPhoneReleaseJavaRes NO-SOURCE
> Task :app:processPhoneReleaseUnitTestJavaRes NO-SOURCE
> Task :app:processPhoneReleaseManifestForPackage
> Task :app:processPhoneReleaseResources

> Task :app:compilePhoneReleaseJavaWithJavac
Note: /home/simon/simon-voice-ime/app/src/main/java/com/simon/voiceime/CommandsEditorActivity.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.

> Task :app:bundlePhoneReleaseClassesToCompileJar
> Task :app:bundlePhoneReleaseClassesToRuntimeJar

> Task :app:compilePhoneReleaseUnitTestJavaWithJavac FAILED
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:8: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
        ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:8: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
                                ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:10: error: package SilenceWatchdog does not exist
            assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0.1, t));
                                        ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:13: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
        ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:13: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
                                ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:14: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(0, 0, 0));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:15: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(-3, 0, 10));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:16: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.READ_ERROR, w.feed(-3, 0, 20));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:19: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
        ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:19: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
                                ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:22: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(-1, 0, 3));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:25: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
        ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:25: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
                                ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:26: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 2900));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:27: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.SILENT_WARN, w.feed(320, 0, 3000));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:28: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 4000));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:29: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.SILENT_RESTART, w.feed(320, 0, 6000));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:30: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 12000));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:33: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
        ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:33: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
                                ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:35: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 3000));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:36: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 5900));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:37: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.SILENT_WARN, w.feed(320, 0, 6000));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:40: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
        ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:40: error: cannot find symbol
        SilenceWatchdog w = new SilenceWatchdog(0);
                                ^
  symbol:   class SilenceWatchdog
  location: class SilenceWatchdogTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/SilenceWatchdogTest.java:41: error: package SilenceWatchdog does not exist
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 30.0 / 32768, 10000));
                                    ^
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/TextLossGuardTest.java:8: error: cannot find symbol
        assertFalse(TextLossGuard.shouldRescue(2, 11));
                    ^
  symbol:   variable TextLossGuard
  location: class TextLossGuardTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/TextLossGuardTest.java:11: error: cannot find symbol
        assertFalse(TextLossGuard.shouldRescue(21, 31));
                    ^
  symbol:   variable TextLossGuard
  location: class TextLossGuardTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/TextLossGuardTest.java:14: error: cannot find symbol
        assertTrue(TextLossGuard.shouldRescue(20, 30));
                   ^
  symbol:   variable TextLossGuard
  location: class TextLossGuardTest
/home/simon/simon-voice-ime/app/src/test/java/com/simon/voiceime/TextLossGuardTest.java:17: error: cannot find symbol
        assertFalse(TextLossGuard.shouldRescue(30, 20));
                    ^
  symbol:   variable TextLossGuard
  location: class TextLossGuardTest
30 errors

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compilePhoneReleaseUnitTestJavaWithJavac'.
> Compilation failed; see the compiler error output for details.

* Try:
> Run with --info option to get more log output.
> Run with --scan to get full insights.

BUILD FAILED in 17s
19 actionable tasks: 19 executed
```

退出碼：`0`。

## 接手狀態

- current_state：程式、JVM 測試、APK 與本報告已產出；裝置驗證未做。
- last_completed：最後版本的指定 Gradle 三項任務、badging 與 APK 來源／副本 sha256 比對。
- files_touched：`SimonIMEService.java`、`app/build.gradle`、兩個新純邏輯類、兩個新測試、`V630_REPORT.md`、指定 dist APK 及 out 建置紀錄。
- commands_run：上列驗收指令及首輪 red 指令；未執行版本控制發布操作。
- blockers：沒有建置阻礙；實機行為與跨家族複核尚未驗證。
- next_action：由接手者在手機驗靜音／藍牙／換字失敗及兩段錄音快速切換，再決定後續發布。本輪未發布。
- handoff_id：null；依工單 ~/.claude 禁區，以本報告接手，不寫共用記憶與工作臺。

## 白話摘要

已加入麥克風異常提示與重啟處理。
較完整的辨識版本會另存 App 內建剪貼簿，不覆蓋真正提交的系統剪貼簿內容。
換字刪除前先存原文；失敗或結果偏短時，嘗試端上辨識保存原話。
手機 6.30 APK 已產出，建置與測試原始輸出列於上方。
靜音、藍牙與換字救援仍需實機確認。
尚未 commit、tag、push 或發布。

## fix-up

```bash
JAVA_HOME=/home/simon/.local/jdk PATH="/home/simon/.local/jdk/bin:/usr/bin:/bin:$PATH" GRADLE_USER_HOME=/home/simon/.gradle_clean ANDROID_HOME=/home/simon/android-sdk ./gradlew --no-daemon --project-cache-dir out/v630-gradle-cache testPhoneReleaseUnitTest --tests com.simon.voiceime.TextLossGuardTest > out/v630-fixup-red.log 2>&1
```

```text
```

```text
exit_code=1
```

```bash
cat out/v630-fixup-red.log
```

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.5/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build 
> Task :app:preBuild UP-TO-DATE
> Task :app:prePhoneReleaseBuild UP-TO-DATE
> Task :app:generatePhoneReleaseBuildConfig UP-TO-DATE
> Task :app:javaPreCompilePhoneRelease UP-TO-DATE
> Task :app:checkPhoneReleaseAarMetadata UP-TO-DATE
> Task :app:generatePhoneReleaseResValues UP-TO-DATE
> Task :app:mapPhoneReleaseSourceSetPaths UP-TO-DATE
> Task :app:generatePhoneReleaseResources UP-TO-DATE
> Task :app:mergePhoneReleaseResources UP-TO-DATE
> Task :app:packagePhoneReleaseResources UP-TO-DATE
> Task :app:parsePhoneReleaseLocalResources UP-TO-DATE
> Task :app:createPhoneReleaseCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksPhoneRelease UP-TO-DATE
> Task :app:processPhoneReleaseMainManifest UP-TO-DATE
> Task :app:processPhoneReleaseManifest UP-TO-DATE
> Task :app:processPhoneReleaseManifestForPackage UP-TO-DATE
> Task :app:processPhoneReleaseResources UP-TO-DATE
> Task :app:compilePhoneReleaseJavaWithJavac
> Task :app:prePhoneReleaseUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompilePhoneReleaseUnitTest UP-TO-DATE
> Task :app:processPhoneReleaseJavaRes NO-SOURCE
> Task :app:processPhoneReleaseUnitTestJavaRes NO-SOURCE
> Task :app:bundlePhoneReleaseClassesToCompileJar
> Task :app:bundlePhoneReleaseClassesToRuntimeJar
> Task :app:compilePhoneReleaseUnitTestJavaWithJavac

> Task :app:testPhoneReleaseUnitTest

com.simon.voiceime.TextLossGuardTest > threeSegmentsWithThreeCharacterOverlaps FAILED
    org.junit.ComparisonFailure at TextLossGuardTest.java:10

8 tests completed, 1 failed

> Task :app:testPhoneReleaseUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testPhoneReleaseUnitTest'.
> There were failing tests. See the report at: file:///home/simon/simon-voice-ime/app/build/reports/tests/testPhoneReleaseUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 44s
20 actionable tasks: 5 executed, 15 up-to-date
```

```text
exit_code=0
```

```bash
JAVA_HOME=/home/simon/.local/jdk PATH="/home/simon/.local/jdk/bin:/usr/bin:/bin:$PATH" GRADLE_USER_HOME=/home/simon/.gradle_clean ANDROID_HOME=/home/simon/android-sdk ./gradlew --no-daemon --project-cache-dir out/v630-gradle-cache testPhoneReleaseUnitTest assemblePhoneRelease compileWatchReleaseJavaWithJavac > out/v630-fixup-build.log 2>&1
```

```text
```

```text
exit_code=0
```

```bash
cat out/v630-fixup-build.log
```

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.5/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build 
> Task :app:preBuild UP-TO-DATE
> Task :app:prePhoneReleaseBuild UP-TO-DATE
> Task :app:generatePhoneReleaseBuildConfig UP-TO-DATE
> Task :app:javaPreCompilePhoneRelease UP-TO-DATE
> Task :app:checkPhoneReleaseAarMetadata UP-TO-DATE
> Task :app:generatePhoneReleaseResValues UP-TO-DATE
> Task :app:mapPhoneReleaseSourceSetPaths UP-TO-DATE
> Task :app:generatePhoneReleaseResources UP-TO-DATE
> Task :app:mergePhoneReleaseResources UP-TO-DATE
> Task :app:packagePhoneReleaseResources UP-TO-DATE
> Task :app:parsePhoneReleaseLocalResources UP-TO-DATE
> Task :app:createPhoneReleaseCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksPhoneRelease UP-TO-DATE
> Task :app:processPhoneReleaseMainManifest UP-TO-DATE
> Task :app:processPhoneReleaseManifest UP-TO-DATE
> Task :app:processPhoneReleaseManifestForPackage UP-TO-DATE
> Task :app:processPhoneReleaseResources UP-TO-DATE
> Task :app:compilePhoneReleaseJavaWithJavac
> Task :app:prePhoneReleaseUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompilePhoneReleaseUnitTest UP-TO-DATE
> Task :app:bundlePhoneReleaseClassesToCompileJar
> Task :app:processPhoneReleaseJavaRes NO-SOURCE
> Task :app:bundlePhoneReleaseClassesToRuntimeJar
> Task :app:compilePhoneReleaseUnitTestJavaWithJavac
> Task :app:processPhoneReleaseUnitTestJavaRes NO-SOURCE
> Task :app:testPhoneReleaseUnitTest
> Task :app:extractProguardFiles UP-TO-DATE
> Task :app:generatePhoneReleaseLintVitalReportModel
> Task :app:mergePhoneReleaseJniLibFolders UP-TO-DATE
> Task :app:mergePhoneReleaseNativeLibs UP-TO-DATE
> Task :app:stripPhoneReleaseDebugSymbols UP-TO-DATE
> Task :app:extractPhoneReleaseNativeSymbolTables UP-TO-DATE
> Task :app:mergePhoneReleaseNativeDebugMetadata UP-TO-DATE
> Task :app:checkPhoneReleaseDuplicateClasses UP-TO-DATE
> Task :app:dexBuilderPhoneRelease
> Task :app:desugarPhoneReleaseFileDependencies UP-TO-DATE
> Task :app:mergeExtDexPhoneRelease UP-TO-DATE
> Task :app:mergePhoneReleaseArtProfile UP-TO-DATE
> Task :app:mergePhoneReleaseGlobalSynthetics UP-TO-DATE
> Task :app:mergePhoneReleaseShaders UP-TO-DATE
> Task :app:compilePhoneReleaseShaders NO-SOURCE
> Task :app:generatePhoneReleaseAssets UP-TO-DATE
> Task :app:mergePhoneReleaseAssets UP-TO-DATE
> Task :app:compressPhoneReleaseAssets UP-TO-DATE
> Task :app:mergePhoneReleaseJavaResource UP-TO-DATE
> Task :app:optimizePhoneReleaseResources UP-TO-DATE
> Task :app:collectPhoneReleaseDependencies UP-TO-DATE
> Task :app:sdkPhoneReleaseDependencyData UP-TO-DATE
> Task :app:validateSigningPhoneRelease UP-TO-DATE
> Task :app:writePhoneReleaseAppMetadata UP-TO-DATE
> Task :app:writePhoneReleaseSigningConfigVersions UP-TO-DATE
> Task :app:preWatchReleaseBuild UP-TO-DATE
> Task :app:generateWatchReleaseBuildConfig UP-TO-DATE
> Task :app:javaPreCompileWatchRelease UP-TO-DATE
> Task :app:checkWatchReleaseAarMetadata UP-TO-DATE
> Task :app:generateWatchReleaseResValues UP-TO-DATE
> Task :app:mapWatchReleaseSourceSetPaths UP-TO-DATE
> Task :app:generateWatchReleaseResources UP-TO-DATE
> Task :app:mergeWatchReleaseResources UP-TO-DATE
> Task :app:packageWatchReleaseResources UP-TO-DATE
> Task :app:parseWatchReleaseLocalResources UP-TO-DATE
> Task :app:createWatchReleaseCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksWatchRelease UP-TO-DATE
> Task :app:processWatchReleaseMainManifest UP-TO-DATE
> Task :app:processWatchReleaseManifest UP-TO-DATE
> Task :app:processWatchReleaseManifestForPackage UP-TO-DATE
> Task :app:processWatchReleaseResources UP-TO-DATE

> Task :app:compileWatchReleaseJavaWithJavac
Note: /home/simon/simon-voice-ime/app/src/watch/java/com/simon/voiceime/WatchIMEService.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.

> Task :app:mergeDexPhoneRelease
> Task :app:compilePhoneReleaseArtProfile
> Task :app:packagePhoneRelease
> Task :app:createPhoneReleaseApkListingFileRedirect UP-TO-DATE
> Task :app:lintVitalAnalyzePhoneRelease
> Task :app:lintVitalReportPhoneRelease UP-TO-DATE
> Task :app:lintVitalPhoneRelease
> Task :app:assemblePhoneRelease

BUILD SUCCESSFUL in 50s
65 actionable tasks: 13 executed, 52 up-to-date
```

```text
exit_code=0
```

```bash
python3 out/v630-fixup-evidence.py
```

```text
F1/F2 fix-up diff (old = pre-fix-up snapshot; new = working tree)
before out/v630-fixup-before/SimonIMEService.java lines=4298 md5=74c8e520d1575dd759f44bedfa0518a7
after app/src/main/java/com/simon/voiceime/SimonIMEService.java lines=4283 md5=185474e3f8e0814b6106f64c883999cf
--- out/v630-fixup-before/SimonIMEService.java
+++ app/src/main/java/com/simon/voiceime/SimonIMEService.java
@@ -1552,12 +1552,12 @@
         synchronized (streamedChunks) {
             for (String chunk : streamedChunks) chunks.append(chunk);
         }
-        StringBuilder local = new StringBuilder();
+        String local;
         synchronized (onDeviceAppendPreviewLock) {
-            for (String segment : onDeviceAppendPreviewSegments) local.append(segment);
+            local = TextLossGuard.joinDedup(onDeviceAppendPreviewSegments, 6);
         }
         if (chunks.length() > candidate.length()) candidate = chunks.toString();
-        if (local.length() > candidate.length()) candidate = local.toString();
+        if (local.length() > candidate.length()) candidate = local;
         return candidate;
     }
 
@@ -2325,14 +2325,7 @@
                     synchronized (onDeviceAppendPreviewLock) {
                         if (gen != activeUtteranceGeneration) return;
                         onDeviceAppendPreviewSegments.add(mapped);
-                        StringBuilder sb = new StringBuilder();
-                        String prev = "";
-                        for (String segment : onDeviceAppendPreviewSegments) {
-                            String seg = dedupOverlapHead(prev, segment, 6);
-                            sb.append(seg);
-                            prev = sb.toString();
-                        }
-                        live = sb.toString();
+                        live = TextLossGuard.joinDedup(onDeviceAppendPreviewSegments, 6);
                     }
 
                     String tail = live.length() > 28 ? "…" + live.substring(live.length() - 28) : live;
@@ -2354,14 +2347,6 @@
         }
     }
 
-    private static String dedupOverlapHead(String prev, String seg, int maxWindow) {
-        if (prev == null || prev.isEmpty() || seg == null || seg.isEmpty()) return seg == null ? "" : seg;
-        int max = Math.min(maxWindow, Math.min(prev.length(), seg.length()));
-        for (int k = max; k > 0; k--) {
-            if (prev.regionMatches(prev.length() - k, seg, 0, k)) return seg.substring(k);
-        }
-        return seg;
-    }
 
     /**
      * 串流模式收尾：
before out/v630-fixup-before/ClipboardHelper.java lines=194 md5=e7ca933c29abe038c93aafdd157b5501
after app/src/main/java/com/simon/voiceime/ClipboardHelper.java lines=194 md5=aca81381994c6a66c797f714e13670d4
--- out/v630-fixup-before/ClipboardHelper.java
+++ app/src/main/java/com/simon/voiceime/ClipboardHelper.java
@@ -76,7 +76,7 @@
         }
     }
 
-    public void addToHistory(String text) {
+    public synchronized void addToHistory(String text) {
         if (text == null || text.trim().isEmpty()) return;
         // Remove if already exists (move to top)
         history.remove(text);
before out/v630-fixup-before/TextLossGuard.java lines=20 md5=8e81d4b6da2709da5c7b205774be5c8e
after app/src/main/java/com/simon/voiceime/TextLossGuard.java lines=38 md5=5db38daab2a5c89d9a190d29d2dd8ff6
--- out/v630-fixup-before/TextLossGuard.java
+++ app/src/main/java/com/simon/voiceime/TextLossGuard.java
@@ -5,6 +5,24 @@
     public static final int MIN_EXTRA_CHARS = 10;
     public static final double MIN_RATIO = 1.5;
     private TextLossGuard() {}
+
+    /** Same cumulative overlap removal used by the on-device preview. */
+    public static String joinDedup(java.util.List<String> segments, int maxOverlap) {
+        StringBuilder joined = new StringBuilder();
+        for (String segment : segments) {
+            joined.append(dedupOverlapHead(joined.toString(), segment, maxOverlap));
+        }
+        return joined.toString();
+    }
+
+    public static String dedupOverlapHead(String prev, String seg, int maxWindow) {
+        if (prev == null || prev.isEmpty() || seg == null || seg.isEmpty()) return seg == null ? "" : seg;
+        int max = Math.min(maxWindow, Math.min(prev.length(), seg.length()));
+        for (int k = max; k > 0; k--) {
+            if (prev.regionMatches(prev.length() - k, seg, 0, k)) return seg.substring(k);
+        }
+        return seg;
+    }
 
     public static boolean shouldRescue(int committedLen, int candidateLen) {
         return shouldRescue(committedLen, candidateLen, MIN_EXTRA_CHARS, MIN_RATIO);
before out/v630-fixup-before/TextLossGuardTest.java lines=27 md5=7e16518ee3645114c9feb4ce6ddfd672
after app/src/test/java/com/simon/voiceime/TextLossGuardTest.java lines=39 md5=6afe56bf4eac843bda76563ae344fcf6
--- out/v630-fixup-before/TextLossGuardTest.java
+++ app/src/test/java/com/simon/voiceime/TextLossGuardTest.java
@@ -4,6 +4,18 @@
 import static org.junit.Assert.*;
 
 public class TextLossGuardTest {
+    @Test public void threeSegmentsWithThreeCharacterOverlaps() {
+        String joined = TextLossGuard.joinDedup(
+                java.util.Arrays.asList("今天去圖書館", "圖書館借故事書", "故事書很好看"), 6);
+        assertEquals("今天去圖書館借故事書很好看", joined);
+        assertEquals(13, joined.length());
+    }
+    @Test public void segmentsWithoutOverlapAreConcatenated() {
+        assertEquals("今天天氣很好適合散步", TextLossGuard.joinDedup(
+                java.util.Arrays.asList("今天", "天氣很好", "適合散步"), 0));
+        assertEquals("甲乙丙丁戊己", TextLossGuard.joinDedup(
+                java.util.Arrays.asList("甲乙", "丙丁", "戊己"), 6));
+    }
     @Test public void nineExtraCharactersDoNotRescue() {
         assertFalse(TextLossGuard.shouldRescue(2, 11));
     }

Handler = Android main looper [static source inspection; not a device thread trace]
before SimonIMEService.java:274: mainHandler = new Handler(Looper.getMainLooper());
after SimonIMEService.java:274: mainHandler = new Handler(Looper.getMainLooper());

WS final / late WS loser: MAIN via mainHandler.post [static source inspection; not a device thread trace]
before SimonIMEService.java:1916: } else if ("final".equals(type)) {
before SimonIMEService.java:1918: mainHandler.post(() -> {
before SimonIMEService.java:1938: clipboardHelper.addToHistory(discarded);
before SimonIMEService.java:1953: clipboardHelper.addToHistory(candidate);
after SimonIMEService.java:1916: } else if ("final".equals(type)) {
after SimonIMEService.java:1918: mainHandler.post(() -> {
after SimonIMEService.java:1938: clipboardHelper.addToHistory(discarded);
after SimonIMEService.java:1953: clipboardHelper.addToHistory(candidate);

Server race loser: MAIN via mainHandler.post [static source inspection; not a device thread trace]
before SimonIMEService.java:2593: JSONObject json = new JSONObject(responseBody);
before SimonIMEService.java:2696: String text = json.optString("text", "").trim();
before SimonIMEService.java:2697: mainHandler.post(() -> {
before SimonIMEService.java:2704: clipboardHelper.addToHistory(text);
after SimonIMEService.java:2578: JSONObject json = new JSONObject(responseBody);
after SimonIMEService.java:2681: String text = json.optString("text", "").trim();
after SimonIMEService.java:2682: mainHandler.post(() -> {
after SimonIMEService.java:2689: clipboardHelper.addToHistory(text);

Local race loser: MAIN via posted late callback and posted budget callback [static source inspection; not a device thread trace]
before SimonIMEService.java:1565: private void rescueDiscardedLocal(int gen, String candidate) {
before SimonIMEService.java:1569: clipboardHelper.addToHistory(candidate);
before SimonIMEService.java:2758: private void startAppendProcessTextRace(String spokenText, int gen) {
before SimonIMEService.java:2771: mainHandler.post(() -> {
before SimonIMEService.java:2772: if (committedGenerations.containsKey(gen)) rescueDiscardedLocal(gen, corrected.trim());
before SimonIMEService.java:2784: Runnable budgetCallback = () -> {
before SimonIMEService.java:2789: rescueDiscardedLocal(gen, fastText);
before SimonIMEService.java:2796: mainHandler.postDelayed(budgetCallback, SERVER_WAIT_BUDGET_MS);
after SimonIMEService.java:1565: private void rescueDiscardedLocal(int gen, String candidate) {
after SimonIMEService.java:1569: clipboardHelper.addToHistory(candidate);
after SimonIMEService.java:2743: private void startAppendProcessTextRace(String spokenText, int gen) {
after SimonIMEService.java:2756: mainHandler.post(() -> {
after SimonIMEService.java:2757: if (committedGenerations.containsKey(gen)) rescueDiscardedLocal(gen, corrected.trim());
after SimonIMEService.java:2769: Runnable budgetCallback = () -> {
after SimonIMEService.java:2774: rescueDiscardedLocal(gen, fastText);
after SimonIMEService.java:2781: mainHandler.postDelayed(budgetCallback, SERVER_WAIT_BUDGET_MS);

REPLACE timeout / short-result rescue: worker recognizes, MAIN writes history [static source inspection; not a device thread trace]
before SimonIMEService.java:1577: private void rescueReplaceAudio(int gen, boolean shortResult) {
before SimonIMEService.java:1589: new Thread(() -> {
before SimonIMEService.java:1591: String recognized = recognizer.recognize(pcm, SAMPLE_RATE);
before SimonIMEService.java:1597: mainHandler.post(() -> {
before SimonIMEService.java:1598: clipboardHelper.addToHistory(rescued);
after SimonIMEService.java:1577: private void rescueReplaceAudio(int gen, boolean shortResult) {
after SimonIMEService.java:1589: new Thread(() -> {
after SimonIMEService.java:1591: String recognized = recognizer.recognize(pcm, SAMPLE_RATE);
after SimonIMEService.java:1597: mainHandler.post(() -> {
after SimonIMEService.java:1598: clipboardHelper.addToHistory(rescued);

REPLACE pre-delete: ALREADY MAIN before fix-up; outer post retained; no nested post added [static source inspection; not a device thread trace]
before SimonIMEService.java:2917: private void handleWTIResponse(JSONObject json, Mode mode, int gen) {
before SimonIMEService.java:2918: mainHandler.post(() -> {
before SimonIMEService.java:2938: case REPLACE: {
before SimonIMEService.java:2954: CharSequence before = deleteBefore > 0 ? ic.getTextBeforeCursor(deleteBefore, 0) : "";
before SimonIMEService.java:2955: CharSequence after = deleteAfter > 0 ? ic.getTextAfterCursor(deleteAfter, 0) : "";
before SimonIMEService.java:2956: clipboardHelper.addToHistory((before == null ? "" : before.toString())
before SimonIMEService.java:2959: deleteSurroundingTextProgrammatically(ic, deleteBefore, deleteAfter);
after SimonIMEService.java:2902: private void handleWTIResponse(JSONObject json, Mode mode, int gen) {
after SimonIMEService.java:2903: mainHandler.post(() -> {
after SimonIMEService.java:2923: case REPLACE: {
after SimonIMEService.java:2939: CharSequence before = deleteBefore > 0 ? ic.getTextBeforeCursor(deleteBefore, 0) : "";
after SimonIMEService.java:2940: CharSequence after = deleteAfter > 0 ? ic.getTextAfterCursor(deleteAfter, 0) : "";
after SimonIMEService.java:2941: clipboardHelper.addToHistory((before == null ? "" : before.toString())
after SimonIMEService.java:2944: deleteSurroundingTextProgrammatically(ic, deleteBefore, deleteAfter);

ClipboardHelper.addToHistory monitor covers history mutation and saveHistory (including preferences apply):
ClipboardHelper.java:78: 
ClipboardHelper.java:79:     public synchronized void addToHistory(String text) {
ClipboardHelper.java:80:         if (text == null || text.trim().isEmpty()) return;
ClipboardHelper.java:81:         // Remove if already exists (move to top)
ClipboardHelper.java:82:         history.remove(text);
ClipboardHelper.java:83:         history.add(0, text);
ClipboardHelper.java:84:         // Trim to max
ClipboardHelper.java:85:         while (history.size() > MAX_ITEMS) {
ClipboardHelper.java:86:             history.remove(history.size() - 1);
ClipboardHelper.java:87:         }
ClipboardHelper.java:88:         saveHistory();
ClipboardHelper.java:89:     }
ClipboardHelper.java:90: 
ClipboardHelper.java:126: 
ClipboardHelper.java:127:     private void saveHistory() {
ClipboardHelper.java:128:         JSONArray arr = new JSONArray();
ClipboardHelper.java:129:         for (String s : history) {
ClipboardHelper.java:130:             arr.put(s);
ClipboardHelper.java:131:         }
ClipboardHelper.java:132:         String json = arr.toString();
ClipboardHelper.java:133:         context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
ClipboardHelper.java:134:                 .edit()
ClipboardHelper.java:135:                 .putString(KEY_HISTORY, json)
ClipboardHelper.java:136:                 .apply();
ClipboardHelper.java:137:         saveToFileBackup(json);
ClipboardHelper.java:138:     }
ClipboardHelper.java:139: 
ClipboardHelper.java:140:     private void saveToFileBackup(String json) {

WS preview and rescue both concatenate chunks without overlap removal; retained [static source inspection; not a device thread trace]
before SimonIMEService.java:1553: for (String chunk : streamedChunks) chunks.append(chunk);
before SimonIMEService.java:1903: for (String c : streamedChunks) composing.append(c);
after SimonIMEService.java:1553: for (String chunk : streamedChunks) chunks.append(chunk);
after SimonIMEService.java:1903: for (String c : streamedChunks) composing.append(c);

Optional section 2: not implemented; recording-loop source unchanged by fix-up.
Unchanged rebuildFailed/read loop excerpt (before lines 1722..1786):
1722:             boolean rebuildFailed = false;
1723:             SharedPreferences prefs = getSharedPreferences("simon_ime_prefs", MODE_PRIVATE);
1724:             SilenceWatchdog watchdog = new SilenceWatchdog(recordingStartedMs,
1725:                     prefs.getFloat("silent_rms", (float) SilenceWatchdog.SILENT_RMS),
1726:                     prefs.getLong("silent_warn_ms", SilenceWatchdog.SILENT_WARN_MS),
1727:                     prefs.getLong("silent_restart_ms", SilenceWatchdog.SILENT_RESTART_MS),
1728:                     prefs.getInt("read_error_limit", SilenceWatchdog.READ_ERROR_LIMIT));
1729: 
1730:             while (isRecording) {
1731:                 int read;
1732:                 try {
1733:                     read = audioRecord.read(buffer, 0, buffer.length);
1734:                 } catch (IllegalStateException e) {
1735:                     read = AudioRecord.ERROR_INVALID_OPERATION;
1736:                 }
1737:                 if (!isRecording) break;
1738:                 if (!isWatchService()) {
1739:                     long sum = 0;
1740:                     for (int i = 0; i + 1 < read; i += 2) {
1741:                         short sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
1742:                         sum += (long) sample * sample;
1743:                     }
1744:                     double normalizedRms = read >= 2
1745:                             ? Math.sqrt(sum / (double) (read / 2)) / 32768.0 : Double.NaN;
1746:                     long observedMs = android.os.SystemClock.elapsedRealtime();
1747:                     SilenceWatchdog.Verdict verdict = watchdog.feed(read, normalizedRms, observedMs);
1748:                     if (verdict == SilenceWatchdog.Verdict.SILENT_WARN) {
1749:                         postMicrophoneWarning(myGen, observedMs,
1750:                                 "⚠️ 沒收到聲音（藍牙耳機或別的 App 占用麥克風？）");
1751:                     } else if (!rebuildFailed && (verdict == SilenceWatchdog.Verdict.READ_ERROR
1752:                             || verdict == SilenceWatchdog.Verdict.SILENT_RESTART)) {
1753:                         rebuildFailed = !restartMicrophone(bufferSize, myGen, verdict, read, observedMs);
1754:                     }
1755:                     if (read <= 0) android.os.SystemClock.sleep(20); // avoid a hot error loop
1756:                 }
1757:                 if (read > 0) {
1758:                     totalBytesRead += read;
1759:                     // v5.4: 前 400ms 不寫入 buffer（丟掉點擊聲）
1760:                     if (totalBytesRead <= SKIP_INITIAL_BYTES) {
1761:                         continue;
1762:                     }
1763:                     pcmBuffer.write(buffer, 0, read);
1764:                     // v6.1: APPEND 串流模式下，pcmBuffer 會每送一個 chunk 就 reset()，
1765:                     //       fullPcmBuffer 不 reset → 保留整段音訊供失敗時乾淨重轉錄。
1766:                     //       封頂 ~10 分鐘防無界成長（超過則停止累積，fallback 退化為前 10 分鐘，極端罕見）。
1767:                     if (currentMode == Mode.APPEND && fullPcmBuffer != null
1768:                             && fullPcmBuffer.size() < MAX_FULL_PCM_BYTES) {
1769:                         fullPcmBuffer.write(buffer, 0, read);
1770:                     }
1771: 
1772:                     if (currentMode == Mode.APPEND && onDeviceAppendPreviewEnabled) {
1773:                         feedOnDeviceAppendPreview(buffer, read, myGen);
1774:                     }
1775: 
1776:                     // v4.3: 音量偵測 — 依語音停頓分段，不依固定秒數
1777:                     if (currentMode == Mode.APPEND && audioStreamActive && audioStreamWs != null) {
1778:                         // 計算 RMS 音量
1779:                         long sumSq = 0;
1780:                         for (int i = 0; i < read - 1; i += 2) {
1781:                             short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
1782:                             sumSq += (long) sample * sample;
1783:                         }
1784:                         double rms = Math.sqrt(sumSq / (double) (read / 2));
1785: 
1786:                         if (rms < SILENCE_THRESHOLD) {

Device checks not executed: silence alert, Bluetooth, REPLACE timeout rescue, concurrency stress.
Cross-family re-review not executed in this fix-up; build/test output is self-check evidence only.

JUnit XML suites:
app/build/test-results/testPhoneReleaseUnitTest/TEST-com.simon.voiceime.KeyboardPagerTest.xml:<testsuite name="com.simon.voiceime.KeyboardPagerTest" tests="6" skipped="0" failures="0" errors="0" timestamp="2026-09-15T11:26:16" hostname="Simon" time="0.003">
app/build/test-results/testPhoneReleaseUnitTest/TEST-com.simon.voiceime.SilenceWatchdogTest.xml:<testsuite name="com.simon.voiceime.SilenceWatchdogTest" tests="9" skipped="0" failures="0" errors="0" timestamp="2026-09-15T11:26:16" hostname="Simon" time="0.006">
app/build/test-results/testPhoneReleaseUnitTest/TEST-com.simon.voiceime.SwipeGestureJudgeTest.xml:<testsuite name="com.simon.voiceime.SwipeGestureJudgeTest" tests="10" skipped="0" failures="0" errors="0" timestamp="2026-09-15T11:26:16" hostname="Simon" time="0.003">
app/build/test-results/testPhoneReleaseUnitTest/TEST-com.simon.voiceime.TextLossGuardTest.xml:<testsuite name="com.simon.voiceime.TextLossGuardTest" tests="8" skipped="0" failures="0" errors="0" timestamp="2026-09-15T11:26:16" hostname="Simon" time="0.006">
TOTAL tests=33 failures=0 errors=0 skipped=0
```

```text
exit_code=0
```

```bash
sha256sum dist/simon-voice-ime-phone-6.30-release-20260915.apk
```

```text
b0beef387b3893feb9929baa7694964b93c7fafc0dac850c193d49b7e7dcae31  dist/simon-voice-ime-phone-6.30-release-20260915.apk
```

```text
exit_code=0
```

```bash
cp -v app/build/outputs/apk/phone/release/app-phone-release.apk dist/simon-voice-ime-phone-6.30-release-20260915.apk
```

```text
'app/build/outputs/apk/phone/release/app-phone-release.apk' -> 'dist/simon-voice-ime-phone-6.30-release-20260915.apk'
```

```text
exit_code=0
```

```bash
/home/simon/android-sdk/build-tools/34.0.0/aapt dump badging dist/simon-voice-ime-phone-6.30-release-20260915.apk
```

```text
package: name='com.simon.voiceime' versionCode='67' versionName='6.30' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='34' compileSdkVersionCodename='14'
sdkVersion:'26'
targetSdkVersion:'34'
uses-permission: name='android.permission.RECORD_AUDIO'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.WAKE_LOCK'
application-label:'Simon Voice IME'
application-label-af:'Simon Voice IME'
application-label-am:'Simon Voice IME'
application-label-ar:'Simon Voice IME'
application-label-as:'Simon Voice IME'
application-label-az:'Simon Voice IME'
application-label-be:'Simon Voice IME'
application-label-bg:'Simon Voice IME'
application-label-bn:'Simon Voice IME'
application-label-bs:'Simon Voice IME'
application-label-ca:'Simon Voice IME'
application-label-cs:'Simon Voice IME'
application-label-da:'Simon Voice IME'
application-label-de:'Simon Voice IME'
application-label-el:'Simon Voice IME'
application-label-en-AU:'Simon Voice IME'
application-label-en-CA:'Simon Voice IME'
application-label-en-GB:'Simon Voice IME'
application-label-en-IN:'Simon Voice IME'
application-label-en-XC:'Simon Voice IME'
application-label-es:'Simon Voice IME'
application-label-es-US:'Simon Voice IME'
application-label-et:'Simon Voice IME'
application-label-eu:'Simon Voice IME'
application-label-fa:'Simon Voice IME'
application-label-fi:'Simon Voice IME'
application-label-fr:'Simon Voice IME'
application-label-fr-CA:'Simon Voice IME'
application-label-gl:'Simon Voice IME'
application-label-gu:'Simon Voice IME'
application-label-hi:'Simon Voice IME'
application-label-hr:'Simon Voice IME'
application-label-hu:'Simon Voice IME'
application-label-hy:'Simon Voice IME'
application-label-in:'Simon Voice IME'
application-label-is:'Simon Voice IME'
application-label-it:'Simon Voice IME'
application-label-iw:'Simon Voice IME'
application-label-ja:'Simon Voice IME'
application-label-ka:'Simon Voice IME'
application-label-kk:'Simon Voice IME'
application-label-km:'Simon Voice IME'
application-label-kn:'Simon Voice IME'
application-label-ko:'Simon Voice IME'
application-label-ky:'Simon Voice IME'
application-label-lo:'Simon Voice IME'
application-label-lt:'Simon Voice IME'
application-label-lv:'Simon Voice IME'
application-label-mk:'Simon Voice IME'
application-label-ml:'Simon Voice IME'
application-label-mn:'Simon Voice IME'
application-label-mr:'Simon Voice IME'
application-label-ms:'Simon Voice IME'
application-label-my:'Simon Voice IME'
application-label-nb:'Simon Voice IME'
application-label-ne:'Simon Voice IME'
application-label-nl:'Simon Voice IME'
application-label-or:'Simon Voice IME'
application-label-pa:'Simon Voice IME'
application-label-pl:'Simon Voice IME'
application-label-pt:'Simon Voice IME'
application-label-pt-BR:'Simon Voice IME'
application-label-pt-PT:'Simon Voice IME'
application-label-ro:'Simon Voice IME'
application-label-ru:'Simon Voice IME'
application-label-si:'Simon Voice IME'
application-label-sk:'Simon Voice IME'
application-label-sl:'Simon Voice IME'
application-label-sq:'Simon Voice IME'
application-label-sr:'Simon Voice IME'
application-label-sr-Latn:'Simon Voice IME'
application-label-sv:'Simon Voice IME'
application-label-sw:'Simon Voice IME'
application-label-ta:'Simon Voice IME'
application-label-te:'Simon Voice IME'
application-label-th:'Simon Voice IME'
application-label-tl:'Simon Voice IME'
application-label-tr:'Simon Voice IME'
application-label-uk:'Simon Voice IME'
application-label-ur:'Simon Voice IME'
application-label-uz:'Simon Voice IME'
application-label-vi:'Simon Voice IME'
application-label-zh-CN:'Simon Voice IME'
application-label-zh-HK:'Simon Voice IME'
application-label-zh-TW:'Simon Voice IME'
application-label-zu:'Simon Voice IME'
application-icon-160:'res/1g.xml'
application-icon-240:'res/1g.xml'
application-icon-320:'res/1g.xml'
application: label='Simon Voice IME' icon='res/1g.xml'
feature-group: label=''
  uses-feature: name='android.hardware.faketouch'
  uses-implied-feature: name='android.hardware.faketouch' reason='default feature for all apps'
  uses-feature: name='android.hardware.microphone'
  uses-implied-feature: name='android.hardware.microphone' reason='requested android.permission.RECORD_AUDIO permission'
provides-component:'ime'
main
other-activities
supports-screens: 'small' 'normal' 'large' 'xlarge'
supports-any-density: 'true'
locales: '--_--' 'af' 'am' 'ar' 'as' 'az' 'be' 'bg' 'bn' 'bs' 'ca' 'cs' 'da' 'de' 'el' 'en-AU' 'en-CA' 'en-GB' 'en-IN' 'en-XC' 'es' 'es-US' 'et' 'eu' 'fa' 'fi' 'fr' 'fr-CA' 'gl' 'gu' 'hi' 'hr' 'hu' 'hy' 'in' 'is' 'it' 'iw' 'ja' 'ka' 'kk' 'km' 'kn' 'ko' 'ky' 'lo' 'lt' 'lv' 'mk' 'ml' 'mn' 'mr' 'ms' 'my' 'nb' 'ne' 'nl' 'or' 'pa' 'pl' 'pt' 'pt-BR' 'pt-PT' 'ro' 'ru' 'si' 'sk' 'sl' 'sq' 'sr' 'sr-Latn' 'sv' 'sw' 'ta' 'te' 'th' 'tl' 'tr' 'uk' 'ur' 'uz' 'vi' 'zh-CN' 'zh-HK' 'zh-TW' 'zu'
densities: '160' '240' '320'
native-code: 'arm64-v8a' 'armeabi-v7a'
```

```text
exit_code=0
```

```bash
sha256sum app/build/outputs/apk/phone/release/app-phone-release.apk dist/simon-voice-ime-phone-6.30-release-20260915.apk
```

```text
ea8b6194405eea9bdf6f448e1a8bb47c8a68c923e3a3b09d85eb8e1e987fd945  app/build/outputs/apk/phone/release/app-phone-release.apk
ea8b6194405eea9bdf6f448e1a8bb47c8a68c923e3a3b09d85eb8e1e987fd945  dist/simon-voice-ime-phone-6.30-release-20260915.apk
```

```text
exit_code=0
```

```bash
cmp app/build/outputs/apk/phone/release/app-phone-release.apk dist/simon-voice-ime-phone-6.30-release-20260915.apk
```

```text
```

```text
exit_code=0
```

```bash
git rev-parse HEAD
```

```text
b29acc8b798f4af3ca7a14398a74f8c6673dd411
```

```text
exit_code=0
```

```bash
git diff --stat
```

```text
 app/build.gradle                                   |   4 +-
 .../java/com/simon/voiceime/ClipboardHelper.java   |   2 +-
 .../java/com/simon/voiceime/SimonIMEService.java   | 333 ++++++++++++++++++---
 3 files changed, 289 insertions(+), 50 deletions(-)
```

```text
exit_code=0
```

```bash
git diff --check
```

```text
```

```text
exit_code=0
```

```bash
git diff --cached --stat
```

```text
```

```text
exit_code=0
```

```bash
git status --short --untracked-files=no
```

```text
 M app/build.gradle
 M app/src/main/java/com/simon/voiceime/ClipboardHelper.java
 M app/src/main/java/com/simon/voiceime/SimonIMEService.java
```

```text
exit_code=0
```

```bash
rg -n "SilenceWatchdog|TextLossGuard.shouldRescue|addToHistory|joinDedup" app/src/main/java/com/simon/voiceime/SimonIMEService.java
```

```text
1490:    private boolean restartMicrophone(int bufferSize, int gen, SilenceWatchdog.Verdict reason,
1494:            Log.w(TAG, "[SilenceWatchdog] restart reason=" + reason + " readResult=" + readResult);
1500:                    Log.w(TAG, "[SilenceWatchdog] stop failed", e);
1503:                if (reason == SilenceWatchdog.Verdict.SILENT_RESTART) {
1515:                        Log.w(TAG, "[SilenceWatchdog] VOICE_RECOGNITION unavailable", e);
1538:                        Log.w(TAG, "[SilenceWatchdog] replacement release failed", releaseError);
1541:                Log.w(TAG, "[SilenceWatchdog] rebuild failed readResult=" + readResult, e);
1557:            local = TextLossGuard.joinDedup(onDeviceAppendPreviewSegments, 6);
1567:        if (!isWatchService() && TextLossGuard.shouldRescue(
1569:            clipboardHelper.addToHistory(candidate);
1598:                    clipboardHelper.addToHistory(rescued);
1724:            SilenceWatchdog watchdog = new SilenceWatchdog(recordingStartedMs,
1725:                    prefs.getFloat("silent_rms", (float) SilenceWatchdog.SILENT_RMS),
1726:                    prefs.getLong("silent_warn_ms", SilenceWatchdog.SILENT_WARN_MS),
1727:                    prefs.getLong("silent_restart_ms", SilenceWatchdog.SILENT_RESTART_MS),
1728:                    prefs.getInt("read_error_limit", SilenceWatchdog.READ_ERROR_LIMIT));
1747:                    SilenceWatchdog.Verdict verdict = watchdog.feed(read, normalizedRms, observedMs);
1748:                    if (verdict == SilenceWatchdog.Verdict.SILENT_WARN) {
1751:                    } else if (!rebuildFailed && (verdict == SilenceWatchdog.Verdict.READ_ERROR
1752:                            || verdict == SilenceWatchdog.Verdict.SILENT_RESTART)) {
1921:                            final boolean rescue = !isWatchService() && TextLossGuard.shouldRescue(
1936:                                    if (!isWatchService() && TextLossGuard.shouldRescue(
1938:                                        clipboardHelper.addToHistory(discarded);
1953:                                clipboardHelper.addToHistory(candidate);
2328:                        live = TextLossGuard.joinDedup(onDeviceAppendPreviewSegments, 6);
2687:                                    if (!isWatchService() && TextLossGuard.shouldRescue(
2689:                                        clipboardHelper.addToHistory(text);
2941:                                clipboardHelper.addToHistory((before == null ? "" : before.toString())
```

```text
exit_code=0
```
