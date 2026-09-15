# WORKORDER v6.30 — 口述永不白唸：靜音看門狗、剪貼簿安全網、「換」鍵刪字先存

派工：Cymon（Claude）→ Codex。2026-09-15。來源＝Simon 當日：「講了一堆之後只出現兩個字，這種情況不是應該自我修復嗎？發生時要趕快把剛錄到的內容變成剪貼簿裡的文字，至少不會白唸。」

## 0. 前提（不符就停手回報）
- 工作樹 `/home/simon/simon-voice-ime`；`git rev-parse HEAD` 必須＝ `b29acc8`（v6.29）；`git status --short` 中**已追蹤檔**必須乾淨（未追蹤的 .bak／log／WORKORDER 不算）。
- 只改 phone flavor 用的 `app/src/main/**`；`app/src/watch/**`、`.worktrees/**`、伺服器（whisper-to-input-proxy）一律不碰。
- 開工第一步把要改的檔案行數＋md5 記進報告；收工前再量一次證明只有你在改。

## 1. 目的句與判斷歸屬
目的＝**使用者講過的話，在任何失敗形態下都至少留在剪貼簿，而且失敗當下就看得到提示**。三個已查實的洞（file:line 以 v6.29 為準）：
- 錄音迴圈 `SimonIMEService.java:1570-1624` 對 `read()≤0` 與全零音訊零偵測（9/13 17:51 手機送了 253 秒數位靜音，狀態列卡在「🔴 錄音中...」）。
- WS final 分支 `:1737-1749` 不比對 `streamedChunks`／`onDeviceAppendPreviewSegments`，短 final 直接取代長預覽；賽跑丟棄點 `:2481-2482`、`:2557` 對輸家 bare `return`。
- REPLACE `handleWTIResponse` `:2701-2722` 先 `deleteSurroundingText` 再插入，被刪文字不存；REPLACE 逾時 `onFailure :2349-2359` 只顯示「連線失敗」，不救、不存。
判斷歸屬：全部程式硬規則（門檻集中在常數、可由 SharedPreferences 覆蓋）。差異分級：本單所有改動都是**加法**（多存一份、多提示一行），不得改變既有 commit 順序、既有網路協定、既有錄音流程的成功路徑。

## 2. 規格

### A. 靜音看門狗（錄音迴圈）
- 新增純邏輯類 `SilenceWatchdog`（無 Android 依賴）：`Verdict feed(int readResult, double rms, long nowMs)` → `OK / READ_ERROR / SILENT_WARN / SILENT_RESTART`。
  - `readResult ≤ 0` 連續 ≥ 3 次 → `READ_ERROR`（呼叫端重建 AudioRecord）。
  - 「數位靜音」定義＝ `rms < SILENT_RMS`（預設 2.0/32768，即訊號幾乎為零；**不是說話停頓的門檻**，正常環境噪音 RMS 遠高於此），自錄音開始起連續 ≥ `SILENT_WARN_MS`（3000ms）→ `SILENT_WARN`（只提示一次）；連續 ≥ `SILENT_RESTART_MS`（6000ms）→ `SILENT_RESTART`（只重建一次，改用 `MediaRecorder.AudioSource.MIC`），之後維持警示不再重建、**不自動停止錄音**。
  - 有聲音出現即重置計時。
- 呼叫端：`:1570-1624` 迴圈每次 read 後餵看門狗。`SILENT_WARN` → `updateStatus("⚠️ 沒收到聲音（藍牙耳機或別的 App 占用麥克風？）")`；`READ_ERROR`／`SILENT_RESTART` → 停掉並重建 AudioRecord（沿用既有建立程式與 fallback 順序），`updateStatus("⚠️ 麥克風已重新啟動")`，並 `Log.w` 記錄原因與 readResult 值。重建失敗 → 保留警示，不 crash。
- 既有「聆聽中…（X字）」狀態更新優先於警示（有字進來代表已恢復）。

### B. 剪貼簿安全網（APPEND）
- 新增純邏輯類 `TextLossGuard`：`static boolean shouldRescue(int committedLen, int candidateLen)` ＝ `candidateLen - committedLen ≥ 10 && candidateLen ≥ committedLen * 1.5`（常數集中）。
- WS final 分支 `:1737`：commit 前先取 `streamedChunks` 拼接與 `onDeviceAppendPreviewSegments` 拼接中較長者為 `candidate`；照舊 commit `finalText`（伺服器版品質通常較好），但若 `shouldRescue(finalText.length(), candidate.length())` → `ClipboardHelper.addToHistory(candidate)` ＋ `updateStatus("辨識結果偏短，較完整版本已存剪貼簿")`。`:1734` 的清空移到比對之後。
- 賽跑丟棄點 `:2481-2482`（伺服器輸給端上）與 `:2557`（端上輸給伺服器）：`return` 前若 `shouldRescue(已提交長度, 被丟棄候選長度)` → `addToHistory(候選)` ＋ `updateStatus("辨識到更完整版本，已存剪貼簿")`；否則維持原行為但補一行 `Log.i` 記錄丟棄（長度、來源）。
- `ClipboardHelper.addToHistory` 若是 private 或需要 context，開最小的 package-level 入口；**只寫 App 內建剪貼簿清單，不覆蓋系統剪貼簿**（系統剪貼簿仍留給真正提交的那份）。

### C. 「換」鍵（REPLACE）
- `:2701-2722`：`delete_before > 0` 或 `delete_after > 0` 時，刪除前先用 `ic.getTextBeforeCursor(deleteBefore, 0)`／`getTextAfterCursor(deleteAfter, 0)` 讀出將被刪文字，`addToHistory(被刪文字)`；刪除與插入順序不變。
- REPLACE 逾時／失敗 `onFailure :2349-2359`：改為在背景執行緒用該世代的 PCM（`fullPcmByGeneration`）跑 `LocalSTTHelper.recognize`（若 `localSTT` 就緒）取得端上文字 → `addToHistory(端上文字)` ＋ `updateStatus("換字逾時，原話已存剪貼簿（端上辨識）")`；端上未就緒 → `updateStatus("換字逾時，錄音已保留，請再按一次換")`，PCM 不清。
- 伺服器成功回應但 `insert` 為空或 ≤ 6 字、且該世代錄音 ≥ 5 秒：同樣跑端上辨識存剪貼簿並提示「換字結果偏短，原話已存剪貼簿」；**不覆蓋**伺服器結果、不改變插入行為。

### D. 版本
- `app/build.gradle` phone 預設：`versionCode 67`、`versionName "6.30"`；watch 區塊不動。

### E. 單元測試（JVM，沿用 v6.29 的 junit）
- `SilenceWatchdogTest`：①正常 rms 永遠 OK；②連續 3 次 read≤0 → READ_ERROR，2 次不觸發（反例）；③數位靜音 2.9 秒不警示、3.0 秒警示一次、6.0 秒 RESTART 一次、之後不再 RESTART；④靜音中途出現聲音則重置；⑤rms 在「安靜房間」量級（例如 30/32768）**不得**判靜音（反例）。
- `TextLossGuardTest`：候選長 9 字不救（反例）、長 10 字但不到 1.5 倍不救（反例）、長 10 字且 ≥1.5 倍救、候選較短不救。

### F. 建置（同 v6.29）
```bash
export JAVA_HOME=/home/simon/.local/jdk; export PATH="/home/simon/.local/jdk/bin:/usr/bin:/bin:$PATH"
export GRADLE_USER_HOME=/home/simon/.gradle_clean; export ANDROID_HOME=/home/simon/android-sdk
cd /home/simon/simon-voice-ime
./gradlew --no-daemon --project-cache-dir out/v630-gradle-cache testPhoneReleaseUnitTest assemblePhoneRelease compileWatchReleaseJavaWithJavac
```
- 產物複製到 `dist/simon-voice-ime-phone-6.30-release-20260915.apk`，`aapt dump badging` 確認 `versionCode='67' versionName='6.30'`，記 sha256。

## 3. 禁區
不 commit／tag／push／release；不碰伺服器、網路協定、錄音成功路徑的提交順序、剪貼簿面板 UI；不改 `app/src/watch/**`、`.worktrees/**`、`~/.claude/**`。

## 4. 驗收斷言（貼指令原文輸出，禁自評 PASS）
1. `git diff --stat` 只含 `SimonIMEService.java`、`ClipboardHelper.java`（若需開入口）、`build.gradle`、新增 `SilenceWatchdog.java`、`TextLossGuard.java` 與兩個測試檔。
2. `grep -n "SilenceWatchdog\|TextLossGuard.shouldRescue\|addToHistory" app/src/main/java/com/simon/voiceime/SimonIMEService.java` → 看門狗餵入 1 處、shouldRescue ≥ 3 處（WS final、兩個賽跑點）、addToHistory ≥ 5 處（WS final、兩個賽跑點、REPLACE 刪前、REPLACE 逾時）。
3. 單元測試全綠且新增 ≥ 9 個；貼 testsuite 行。
4. badging `versionCode='67' versionName='6.30'`；`compileWatchReleaseJavaWithJavac` BUILD SUCCESSFUL。
5. 未做（如實寫）：實機靜音警示、藍牙情境、換鍵逾時救援未在裝置驗證。

## 5. 回報檔 `V630_REPORT.md`
改動清單（新舊行號）、五條斷言原文、產物 sha256、未驗證清單、最後 ≤8 行給非技術使用者的白話摘要。

## 6. 九格摘要
目的＝口述不白唸＋失敗當下可見｜歸屬＝程式硬規則、門檻集中可覆蓋｜差異分級＝全部加法，主路徑不動｜不可變＝既有提交順序與網路協定｜例外→根因＝若警示誤觸（正常停頓被判靜音）代表門檻定義錯（要用數位零量級，不是停頓量級），改常數不加特例｜不確定＝判不出就不提示、但一律存剪貼簿｜泛化＝手錶 flavor 同樣缺看門狗，另單｜退場＝revert 本 commit｜驗收＝§4。
