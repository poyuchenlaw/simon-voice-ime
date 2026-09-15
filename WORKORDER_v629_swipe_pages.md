# WORKORDER v6.29 — 手機版：左右滑切換鍵盤頁、首頁直出「，」「。」、特殊符號進符號表

派工：Cymon（Claude）→ Codex。2026-09-15。來源＝Simon 當日口述（「跟 Typeless 最新更新一樣，滑過去就直接切到相應鍵盤；首頁把符號格省掉、只留全形逗號句號；特殊符號左滑符號表，右滑 ABCD」）。
Typeless 官方說明：左滑／右滑在鍵盤之間**循環**切換（loop）。

## 0. 指紋前提（不符就停手回報，不得自行 rebase／merge）
- 工作樹：`/home/simon/simon-voice-ime`，`git rev-parse HEAD` 必須＝ `6c2b8fb99df9935adf9207f352097fa9154be6c9`，`git status --short` 除未追蹤檔外必須乾淨。
- 目標檔（行數／md5）：
  - `app/src/main/java/com/simon/voiceime/SimonIMEService.java` 4129 行 / `233e483ac082d7b3ee410189a68711c9`
  - `app/src/main/res/layout/keyboard_view.xml` 244 行 / `184a3e97429ab7a83cea7dc053a46d0a`
  - `app/src/main/res/layout/keyboard_numbers.xml` 182 行 / `1eef1302ff46ce013a087f6f14dc005e`
  - `app/src/main/res/layout/keyboard_english.xml` 234 行 / `acd8684606a31c5e621734fe2c4bef8d`
  - `app/build.gradle` 80 行 / `52264d92db0fd243b1a3abe46fb08bb6`
- 只改 **phone flavor 共用的 main 原始碼**；`app/src/watch/**`、`.worktrees/**`、`out/**` 既有檔案、伺服器端一律不碰。

## 1. 目的句（第一性原理）
使用者用單手拇指在三個鍵盤頁之間**一個手勢**到位，首頁只留最常用的中文標點，其餘符號集中在一頁。判斷歸屬：全部是程式硬規則（手勢門檻、頁序），沒有模型判斷。

## 2. 規格

### A. 三頁循環滑動（Typeless 式）
- 頁序固定：`[ENGLISH(ABCD)] ← [VOICE(首頁)] → [NUMBERS(符號表)]`，**循環**。
- 手指向左滑（dx 為負）＝到「下一頁」；向右滑（dx 為正）＝到「上一頁」。因此：首頁左滑→符號表、首頁右滑→ABCD、符號表右滑→首頁、ABCD 左滑→首頁、符號表左滑→ABCD（繞圈）、ABCD 右滑→符號表（繞圈）。
- 純邏輯抽成兩個**無 Android 依賴**的類別，方便 JVM 單元測試：
  - `KeyboardPager`：`static KeyboardMode next(KeyboardMode current, Direction dir)`（Direction = LEFT / RIGHT）。`KeyboardMode` 現在是 `SimonIMEService` 內的 package-private enum，可原地引用或搬到獨立檔，擇最小改動。
  - `SwipeGestureJudge`：輸入 `dx, dy`（px）、`minDistancePx`、`touchSlopPx` → 回 `NONE / LEFT / RIGHT`。規則：`|dx| >= minDistancePx` 且 `|dx| >= 1.5 * |dy|`；否則 NONE。`minDistancePx = max(56dp, 22% 鍵盤寬)`。
- 攔截層：新增 `SwipeInterceptLayout extends LinearLayout`，覆寫 `onInterceptTouchEvent` / `onTouchEvent`：DOWN 記起點；MOVE 超過 touch slop 且水平主導→開始攔截（子 View 自動收到 ACTION_CANCEL）；UP 用 `SwipeGestureJudge` 判定並回呼 `onSwipe(Direction)`。
  - 套用在 `keyboard_view.xml` 的 `@+id/voiceKeyboard`、`keyboard_english.xml` 的 `@+id/englishKeyboard`、`keyboard_numbers.xml` 的 `@+id/numbersKeyboard` 三個容器（把根 LinearLayout 換成自訂類別即可；**不要**套在整個 rootView，因為上方 `panelContainer` 的剪貼簿列表有自己的 ItemTouchHelper 左右滑刪除，不得干擾）。
  - **排除**：DOWN 落在 `@+id/btnMic`（首頁大麥克風，按住錄音）時整段手勢**不得**攔截，避免滑動把錄音 ACTION_CANCEL 掉。實作方式＝hit-test 該子 View 的邊界，或給它 `android:tag="noswipe"` 由攔截層辨識。
  - 現有點擊入口（首頁 `EN`、符號表 `ABC`／`🎤`、英文頁 `?123`／`🎤`）**全部保留**，滑動只是新增。
- 動畫：滑動觸發切頁時，新頁從滑動方向的反側（±1/3 頁寬）滑入、alpha 0.6→1，時長約 150ms，用 `View.animate()`；動畫只是視覺，切頁本身仍走既有 `switchKeyboard(mode)`（含 `dismissSymbolPopup`、`clearEnWordBuffer`、`closePanel`）。動畫例外時退回純切頁，不得讓鍵盤消失或卡住。

### B. 首頁 Row 2 改成：`␣ ， 。 ⌫ ↵`
- `keyboard_view.xml`：`btnComma` 顯示 `，`、`btnPeriod` 顯示 `。`（替換原本的 `全`／`半`）。
- `SimonIMEService`：移除 `setupSymbolLauncher(btnComma, FULL_WIDTH_SYMBOLS, …)` 與 `setupSymbolLauncher(btnPeriod, HALF_WIDTH_SYMBOLS, …)`。改為：
  - 點一下 `，` → 直接送出 `，`；點一下 `。` → 直接送出 `。`（走既有 `commitTextProgrammatically(ic, …)`，ic 為 null 時走 `commitTextSafely`）。
  - 長按 `，` → 送出半形 `,`；長按 `。` → 送出半形 `.`（這就是首頁保留的唯一半形符號入口）。
- `FULL_WIDTH_SYMBOLS`、`HALF_WIDTH_SYMBOLS`、`setupSymbolLauncher`、`showSymbolPopupOrFallback`、`buildSymbolPopupContent`、`calculateSymbolPopupXOffset`、`commitSymbolFromPopup` 若再無引用，**整組刪除**；`dismissSymbolPopup()` 與 `symbolPopup` 欄位若仍被 `switchKeyboard` 等處呼叫，一併清乾淨（不得留下無用欄位）。必須能編譯。

### C. 符號表（`keyboard_numbers.xml`）新增一列全形標點
- 在現有 Row 3（`= * " ' : ; ! ? ⌫`）之後、Row 4（`🎤 ABC ␣ . ↵`）之前插入 **Row 3b**，10 鍵、樣式與 Row 1–3 相同（高 42dp、`@color/key_bg`、18sp）：
  `、 ？ ！ ： ； 「 」 （ ） …`
  tag 依既有慣例 `android:tag="key:、"` … `android:tag="key:…"`（XML 屬性值直接放全形字，不需 escape）。
- 這列承接原本只能從「全」彈窗拿到、且中文書寫真正常用的符號；其餘罕用全形（『』《》〈〉─～％＃＠＆＊）**不加**（半形對應鍵已在表內）。
- Row 1–3、Row 4 與英文頁一字不改。

### D. 版本
- `app/build.gradle` 第 13–14 行：`versionCode 66`、`versionName "6.29"`。watch flavor 區塊（70 / 6.33）不動。

### E. 單元測試（新增，JVM，不需裝置）
- `app/build.gradle` `dependencies` 加 `testImplementation 'junit:junit:4.13.2'`。
- `app/src/test/java/com/simon/voiceime/KeyboardPagerTest.java`：六個轉移（含兩個繞圈）各一個斷言。
- `app/src/test/java/com/simon/voiceime/SwipeGestureJudgeTest.java`：至少：①點擊（dx=3, dy=2）→NONE；②垂直拖曳（dx=30, dy=120）→NONE；③水平左滑（dx=-200, dy=20）→LEFT；④水平右滑（dx=200, dy=-15）→RIGHT；⑤**可推翻預測的對角案例**（dx=100, dy=90）→NONE；⑥剛好未達 minDistance（dx=minDistance-1, dy=0）→NONE。
- 執行：`./gradlew testPhoneReleaseUnitTest`（或 `testPhoneDebugUnitTest`），把測試數量與結果原文貼進報告。

### F. 建置（環境依 repo `CLAUDE.md`）
```bash
export JAVA_HOME=/home/simon/.local/jdk; export PATH="/home/simon/.local/jdk/bin:/usr/bin:/bin:$PATH"
export GRADLE_USER_HOME=/home/simon/.gradle_clean; export ANDROID_HOME=/home/simon/android-sdk
cd /home/simon/simon-voice-ime
./gradlew --no-daemon --project-cache-dir out/v629-gradle-cache testPhoneReleaseUnitTest assemblePhoneRelease compileWatchReleaseJavaWithJavac
```
- `compileWatchReleaseJavaWithJavac` 只為證明手錶 flavor 仍可編譯，**不要**組手錶 APK、不要動 `out/simon-voice-ime-watch-*.apk`。
- 產物複製到 `dist/simon-voice-ime-phone-6.29-release-20260915.apk`；用 `/home/simon/android-sdk/build-tools/34.0.0/aapt dump badging` 確認 `versionCode='66' versionName='6.29'`，並記 `sha256sum`。
- 結束後 `./gradlew --stop` 不必跑（已 `--no-daemon`）；若 gradle 因記憶體失敗，先 `ps` 看是否有別的 gradle/kotlin daemon 再決定，不得殺不是自己起的程序。

## 3. 禁區
- 不 commit、不 tag、不 push、不建 GitHub release（由派工者複核後處理）。
- 不碰伺服器（whisper-to-input-proxy）、不改任何網路請求、錄音流程、剪貼簿／常用指令面板、英文預測輸入。
- 不改 `app/src/watch/**`、`.worktrees/**`、`~/.claude/**`、GDrive。
- 不引入新的第三方 runtime 依賴（junit 只在 test scope）。

## 4. 驗收斷言（可重跑；禁自評 PASS，只貼原文）
1. `git diff --stat` 只含：`SimonIMEService.java`、三個 layout xml、`build.gradle`、新增的 `KeyboardPager*`、`SwipeGestureJudge*`、`SwipeInterceptLayout.java`、兩個測試檔。
2. `grep -n "setupSymbolLauncher\|FULL_WIDTH_SYMBOLS\|HALF_WIDTH_SYMBOLS\|showSymbolPopupOrFallback" app/src/main/java -r` → 0 筆。
3. `grep -c 'android:tag="key:' app/src/main/res/layout/keyboard_numbers.xml` → 原 34 ＋ 10 ＝ 44。
4. `grep -n 'android:text="，"\|android:text="。"' app/src/main/res/layout/keyboard_view.xml` → 各 1 筆；`android:text="全"`／`"半"` → 0 筆。
5. 單元測試全綠且數量 ≥ 12，貼 gradle 原文摘要行。
6. `aapt dump badging` 顯示 `versionCode='66' versionName='6.29'`，package `com.simon.voiceime`。
7. `compileWatchReleaseJavaWithJavac` BUILD SUCCESSFUL。
8. 未做（如實寫）：實機手勢、動畫、麥克風排除未在裝置驗證（本機無 adb 裝置），由派工者／Simon 旁載後驗。

## 5. 回報檔 `V629_REPORT.md`（寫在 repo 根目錄）
- 改了哪些檔、關鍵行號（新舊）。
- 上述 8 條斷言的**指令與原文輸出**。
- 產物路徑、sha256、badging 行。
- 「未完成／未驗證」清單。
- 最後附 ≤8 行給非技術使用者的白話摘要（做成什麼、裝上後會看到什麼、哪些還沒驗）。

## 6. 九格摘要（first-principles-spec）
目的＝單手一手勢切頁、首頁只留最常用標點｜歸屬＝程式硬規則｜差異分級＝手勢誤判只會切頁不會改字，屬「放行並記錄」；錄音被取消屬「擋」→ 麥克風排除是不可變條件｜例外→根因＝若出現「滑了沒切」或「點了卻切」，代表門檻常數選錯或攔截層位置錯，改常數／位置而非加特例｜不確定＝判不出方向就 NONE，不切頁｜泛化＝手錶 flavor 未來若加多頁，沿用 KeyboardPager｜退場＝`git revert` 該 commit 即回 6.28 行為｜驗收＝§4。
