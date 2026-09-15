# V629_REPORT

工單：WORKORDER_v629_swipe_pages.md；日期：2026-09-15。狀態：程式與 APK 已產出，交派工者複核及裝置驗收。

## 第 0 節指紋

動手前 HEAD 為 `6c2b8fb99df9935adf9207f352097fa9154be6c9`；`git status --short` 僅有 `??` 未追蹤檔。五份檔案行數／MD5 與工單逐項一致：

```text
4129 233e483ac082d7b3ee410189a68711c9 SimonIMEService.java
244 184a3e97429ab7a83cea7dc053a46d0a keyboard_view.xml
182 1eef1302ff46ce013a087f6f14dc005e keyboard_numbers.xml
234 acd8684606a31c5e621734fe2c4bef8d keyboard_english.xml
80 52264d92db0fd243b1a3abe46fb08bb6 app/build.gradle
```

## 修改檔案與關鍵行號

舊行號以指定 HEAD 為準；新增類別以新檔行號標示。

### app/build.gradle

`-` 為舊行號，`+` 為新行號。

```diff
@@ -13,2 +13,2 @@ android {
@@ -74,0 +75 @@ dependencies {
```

### app/src/main/java/com/simon/voiceime/SimonIMEService.java

`-` 為舊行號，`+` 為新行號。

```diff
@@ -2,0 +3,2 @@ package com.simon.voiceime;
@@ -27 +28,0 @@ import android.widget.FrameLayout;
@@ -29 +29,0 @@ import android.widget.LinearLayout;
@@ -35 +34,0 @@ import android.graphics.Paint;
@@ -96 +94,0 @@ public class SimonIMEService extends InputMethodService {
@@ -212 +209,0 @@ public class SimonIMEService extends InputMethodService {
@@ -243,10 +239,0 @@ public class SimonIMEService extends InputMethodService {
@@ -393 +380 @@ public class SimonIMEService extends InputMethodService {
@@ -397 +384 @@ public class SimonIMEService extends InputMethodService {
@@ -484,0 +472,3 @@ public class SimonIMEService extends InputMethodService {
@@ -503,3 +493 @@ public class SimonIMEService extends InputMethodService {
@@ -507 +495 @@ public class SimonIMEService extends InputMethodService {
@@ -509,2 +497 @@ public class SimonIMEService extends InputMethodService {
@@ -515,97 +502,4 @@ public class SimonIMEService extends InputMethodService {
@@ -1030,12 +923,0 @@ public class SimonIMEService extends InputMethodService {
@@ -2872,0 +2755,33 @@ public class SimonIMEService extends InputMethodService {
@@ -2874 +2789,3 @@ public class SimonIMEService extends InputMethodService {
@@ -4082 +3998,0 @@ public class SimonIMEService extends InputMethodService {
@@ -4090 +4005,0 @@ public class SimonIMEService extends InputMethodService {
```

### app/src/main/res/layout/keyboard_view.xml

`-` 為舊行號，`+` 為新行號。

```diff
@@ -41 +41 @@
@@ -163 +163 @@
@@ -177 +177 @@
@@ -228 +228 @@
```

### app/src/main/res/layout/keyboard_numbers.xml

`-` 為舊行號，`+` 為新行號。

```diff
@@ -2 +2 @@
@@ -153,0 +154,49 @@
@@ -182 +231 @@
```

### app/src/main/res/layout/keyboard_english.xml

`-` 為舊行號，`+` 為新行號。

```diff
@@ -2 +2 @@
@@ -234 +234 @@
```

- `app/src/main/java/com/simon/voiceime/KeyboardPager.java`：新增第 1–22 行。

- `app/src/main/java/com/simon/voiceime/SwipeGestureJudge.java`：新增第 1–17 行。

- `app/src/main/java/com/simon/voiceime/SwipeInterceptLayout.java`：新增第 1–92 行。

- `app/src/test/java/com/simon/voiceime/KeyboardPagerTest.java`：新增第 1–33 行。

- `app/src/test/java/com/simon/voiceime/SwipeGestureJudgeTest.java`：新增第 1–48 行。


A：KeyboardMode 移至 KeyboardPager 內，兩個純邏輯類別不依賴 Android。三頁容器接入滑動；DOWN 麥克風 hit-test 排除整段手勢，多指手勢亦排除。僅 phone service 掛上 swipe listener。切頁沿用 switchKeyboard，保留英文緩衝清理與關閉面板；動畫 150ms，方向相反側進場，切換時重設動畫，動畫呼叫例外時恢復頁面屬性。

B：首頁短按全形、長按半形，走 commitTextProgrammatically；無 InputConnection 時走 commitTextSafely。舊彈窗整組方法、欄位、常數、專用 imports 已刪。

C：符號表新增十鍵 Row 3b；以還原容器標籤並移除新增列的字串比對確認其餘列等同 HEAD，英文頁除容器標籤外亦等同 HEAD。

D–F：phone 版本 66／6.29、JUnit 僅 test scope，新增 16 個測試並執行手機 release 建置及手錶 Java 編譯。

## 八條驗收：指令與原文輸出

以下保留實際輸出與退出碼，不另行標記驗收結論。

### 1. 變更範圍

```bash
git diff --stat
```
```text
 app/build.gradle                                   |   5 +-
 .../java/com/simon/voiceime/SimonIMEService.java   | 185 ++++++---------------
 app/src/main/res/layout/keyboard_english.xml       |   4 +-
 app/src/main/res/layout/keyboard_numbers.xml       |  53 +++++-
 app/src/main/res/layout/keyboard_view.xml          |   8 +-
 5 files changed, 110 insertions(+), 145 deletions(-)
```
退出碼：0


新增檔尚未追蹤，git diff --stat 不列出；未使用 git add。補充各新檔的原始差異統計：

```bash
git diff --no-index --stat /dev/null app/src/main/java/com/simon/voiceime/KeyboardPager.java
```
```text
 .../java/com/simon/voiceime/KeyboardPager.java     | 22 ++++++++++++++++++++++
 1 file changed, 22 insertions(+)
```
退出碼：1


```bash
git diff --no-index --stat /dev/null app/src/main/java/com/simon/voiceime/SwipeGestureJudge.java
```
```text
 .../main/java/com/simon/voiceime/SwipeGestureJudge.java | 17 +++++++++++++++++
 1 file changed, 17 insertions(+)
```
退出碼：1


```bash
git diff --no-index --stat /dev/null app/src/main/java/com/simon/voiceime/SwipeInterceptLayout.java
```
```text
 .../com/simon/voiceime/SwipeInterceptLayout.java   | 92 ++++++++++++++++++++++
 1 file changed, 92 insertions(+)
```
退出碼：1


```bash
git diff --no-index --stat /dev/null app/src/test/java/com/simon/voiceime/KeyboardPagerTest.java
```
```text
 .../java/com/simon/voiceime/KeyboardPagerTest.java | 33 ++++++++++++++++++++++
 1 file changed, 33 insertions(+)
```
退出碼：1


```bash
git diff --no-index --stat /dev/null app/src/test/java/com/simon/voiceime/SwipeGestureJudgeTest.java
```
```text
 .../com/simon/voiceime/SwipeGestureJudgeTest.java  | 48 ++++++++++++++++++++++
 1 file changed, 48 insertions(+)
```
退出碼：1


### 2. 舊符號彈窗名稱

```bash
grep -n "setupSymbolLauncher\|FULL_WIDTH_SYMBOLS\|HALF_WIDTH_SYMBOLS\|showSymbolPopupOrFallback" app/src/main/java -r
```
```text
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_v2_woD2_20260914_111327:245:    private static final String[] FULL_WIDTH_SYMBOLS = {
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_v2_woD2_20260914_111327:249:    private static final String[] HALF_WIDTH_SYMBOLS = {
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_v2_woD2_20260914_111327:393:        setupSymbolLauncher(btnComma, FULL_WIDTH_SYMBOLS, 5, ORIGINAL_COMMA);
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_v2_woD2_20260914_111327:397:        setupSymbolLauncher(btnPeriod, HALF_WIDTH_SYMBOLS, 6, ORIGINAL_PERIOD);
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_v2_woD2_20260914_111327:505:    private void setupSymbolLauncher(View key, String[] symbols, int columns, String fallbackText) {
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_v2_woD2_20260914_111327:507:        key.setOnClickListener(v -> showSymbolPopupOrFallback(v, symbols, columns, fallbackText));
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_v2_woD2_20260914_111327:515:    private void showSymbolPopupOrFallback(View anchor, String[] symbols, int columns, String fallbackText) {
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_resume_woD2_20260914:245:    private static final String[] FULL_WIDTH_SYMBOLS = {
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_resume_woD2_20260914:249:    private static final String[] HALF_WIDTH_SYMBOLS = {
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_resume_woD2_20260914:393:        setupSymbolLauncher(btnComma, FULL_WIDTH_SYMBOLS, 5, ORIGINAL_COMMA);
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_resume_woD2_20260914:397:        setupSymbolLauncher(btnPeriod, HALF_WIDTH_SYMBOLS, 6, ORIGINAL_PERIOD);
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_resume_woD2_20260914:505:    private void setupSymbolLauncher(View key, String[] symbols, int columns, String fallbackText) {
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_resume_woD2_20260914:507:        key.setOnClickListener(v -> showSymbolPopupOrFallback(v, symbols, columns, fallbackText));
app/src/main/java/com/simon/voiceime/SimonIMEService.java.bak_resume_woD2_20260914:515:    private void showSymbolPopupOrFallback(View anchor, String[] symbols, int columns, String fallbackText) {
```
退出碼：0


上述命中均為動手前已有的兩份 `.bak`；因此原斷言要求的「0 筆」未成立。保留備份，未為消除命中修改或刪除它們。補充僅搜尋編譯用 .java 的輸出：

```bash
grep -n --include="*.java" "setupSymbolLauncher\|FULL_WIDTH_SYMBOLS\|HALF_WIDTH_SYMBOLS\|showSymbolPopupOrFallback" app/src/main/java -r
```
```text
```
退出碼：1


### 3. 符號表鍵數

```bash
grep -c 'android:tag="key:' app/src/main/res/layout/keyboard_numbers.xml
```
```text
44
```
退出碼：0


### 4. 首頁標點

```bash
grep -n 'android:text="，"\|android:text="。"' app/src/main/res/layout/keyboard_view.xml
```
```text
163:                    android:text="，"
177:                    android:text="。"
```
退出碼：0


```bash
grep -n 'android:text="全"\|android:text="半"' app/src/main/res/layout/keyboard_view.xml
```
```text
```
退出碼：1


### 5. JVM 測試與完整建置原文

執行兩次；第二次在最後的動畫復原調整後重建。以下為第二次完整 stdout/stderr；退出碼 0。

```bash
export JAVA_HOME=/home/simon/.local/jdk
export PATH="/home/simon/.local/jdk/bin:/usr/bin:/bin:$PATH"
export GRADLE_USER_HOME=/home/simon/.gradle_clean
export ANDROID_HOME=/home/simon/android-sdk
./gradlew --no-daemon --project-cache-dir out/v629-gradle-cache testPhoneReleaseUnitTest assemblePhoneRelease compileWatchReleaseJavaWithJavac > /tmp/v629-build-final.log 2>&1
```

```bash
cat /tmp/v629-build-final.log
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
> Task :app:bundlePhoneReleaseClassesToRuntimeJar
> Task :app:bundlePhoneReleaseClassesToCompileJar
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
> Task :app:lintVitalAnalyzePhoneRelease
> Task :app:packagePhoneRelease
> Task :app:lintVitalReportPhoneRelease UP-TO-DATE
> Task :app:lintVitalPhoneRelease
> Task :app:createPhoneReleaseApkListingFileRedirect UP-TO-DATE
> Task :app:assemblePhoneRelease

BUILD SUCCESSFUL in 16s
65 actionable tasks: 12 executed, 53 up-to-date
```
退出碼：0


Gradle 預設主控台未印測試數量，以下直接引用 Gradle 產生的 JUnit XML 原文（6 與 10 個測試）：

```bash
grep '<testsuite ' app/build/test-results/testPhoneReleaseUnitTest/TEST-*.xml
```
```text
app/build/test-results/testPhoneReleaseUnitTest/TEST-com.simon.voiceime.KeyboardPagerTest.xml:<testsuite name="com.simon.voiceime.KeyboardPagerTest" tests="6" skipped="0" failures="0" errors="0" timestamp="2026-09-15T05:12:12" hostname="Simon" time="0.002">
app/build/test-results/testPhoneReleaseUnitTest/TEST-com.simon.voiceime.SwipeGestureJudgeTest.xml:<testsuite name="com.simon.voiceime.SwipeGestureJudgeTest" tests="10" skipped="0" failures="0" errors="0" timestamp="2026-09-15T05:12:12" hostname="Simon" time="0.003">
```
退出碼：0


### 6. APK 版本與封裝名稱

```bash
/home/simon/android-sdk/build-tools/34.0.0/aapt dump badging dist/simon-voice-ime-phone-6.29-release-20260915.apk | head -1
```
```text
package: name='com.simon.voiceime' versionCode='66' versionName='6.29' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='34' compileSdkVersionCodename='14'
```
退出碼：0


### 7. 手錶 Java 編譯

```bash
grep -A 3 '^> Task :app:compileWatchReleaseJavaWithJavac' /tmp/v629-build-final.log
```
```text
> Task :app:compileWatchReleaseJavaWithJavac
Note: /home/simon/simon-voice-ime/app/src/watch/java/com/simon/voiceime/WatchIMEService.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.

```
退出碼：0


```bash
grep -A 1 '^BUILD SUCCESSFUL' /tmp/v629-build-final.log
```
```text
BUILD SUCCESSFUL in 16s
65 actionable tasks: 12 executed, 53 up-to-date
```
退出碼：0


### 8. 未做的裝置驗證

```bash
/home/simon/android-sdk/platform-tools/adb devices
```
```text
List of devices attached

```
退出碼：0


未做：實機手勢、動畫、麥克風排除未在裝置驗證。由派工者／Simon 旁載後驗；本次無裝置互動結果。

## 產物與 SHA-256

來源 `app/build/outputs/apk/phone/release/app-phone-release.apk`，使用 shutil.copy2 複製至工單指定 dist 路徑。

```bash
sha256sum app/build/outputs/apk/phone/release/app-phone-release.apk dist/simon-voice-ime-phone-6.29-release-20260915.apk
```
```text
0cc22b1a3ac50cf3d84b2cd2bfd1c7de6be5e7d8748a5795ac25447e94004010  app/build/outputs/apk/phone/release/app-phone-release.apk
0cc22b1a3ac50cf3d84b2cd2bfd1c7de6be5e7d8748a5795ac25447e94004010  dist/simon-voice-ime-phone-6.29-release-20260915.apk
```
退出碼：0


## 收尾範圍檢查

```bash
git diff --check
```
```text
```
退出碼：0


```bash
git rev-parse HEAD
```
```text
6c2b8fb99df9935adf9207f352097fa9154be6c9
```
退出碼：0


```bash
git diff --name-only
```
```text
app/build.gradle
app/src/main/java/com/simon/voiceime/SimonIMEService.java
app/src/main/res/layout/keyboard_english.xml
app/src/main/res/layout/keyboard_numbers.xml
app/src/main/res/layout/keyboard_view.xml
```
退出碼：0


未執行 commit、tag、push、release；未建置手錶 APK。未修改 app/src/watch、.worktrees、既有 out 產物、伺服器、網路請求、錄音流程、面板功能、英文預測輸入、~/.claude 或 GDrive。建置使用工單指定 out/v629-gradle-cache，另有一般 app/build 建置輸出。

## 未完成／未驗證與接手狀態

- 原驗收第 2 條遞迴 grep 仍命中既有備份；真正 Java 原始碼搜尋無命中。
- 原 git diff --stat 不顯示五份未追蹤新檔；已逐檔附 no-index 原文補足。
- 實機六向切頁、滑動取消子按鍵、長按與點按標點、快速連續切頁、動畫、麥克風排除及剪貼簿左右刪除的互動仍待旁載驗收。
- 手錶只編譯 Java，未建 APK、未在錶上執行。
- 本次為實作與自檢；跨家族複核由派工者接續，尚無外部 review 收據。
- current_state：APK 與本報告已產出；原斷言差異與裝置驗證待處理。
- last_completed：最後版本建置、badging、APK 雜湊比對、原文驗收紀錄。
- files_touched：上列十份原始碼／測試檔、本 V629_REPORT.md、指定 dist APK；建置快取與 app/build 自動輸出。
- commands_run：本報告各節指令及兩次完整 Gradle 建置。
- blockers：無 adb 裝置；第 2 條受既有備份影響。
- next_action：派工者複核程式與驗收差異，Simon／派工者旁載 APK 驗互動；未獲本工單授權的版本發布作業留待派工者處理。
- 未寫 session bus、共用記憶或工作臺，遵守本工單禁止修改 ~/.claude 與 GDrive。本檔為接手記錄。

## 給使用者的摘要

手機 6.29 安裝檔已備好。
首頁左右滑可循環切換符號表與英文頁，原有切頁按鈕仍可使用。
首頁直接按出「，」「。」；長按輸入半形逗號、句號。
符號表多一列十個常用中文標點。
16 個邏輯測試的失敗與錯誤均為 0，手機建置及手錶 Java 編譯已執行。
手勢手感、動畫與麥克風排除仍需安裝後驗證。
原驗收搜尋會命中既有備份，報告保留原文供複核。
尚未提交或發布版本。
