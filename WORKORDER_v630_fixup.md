# WORKORDER v6.30 fix-up — 複核兩條必修（同版號 6.30／67，不另升版）

派工：Cymon → Codex。2026-09-15。承 WORKORDER_v630_never_lose_dictation.md；跨家族複核（Claude Sonnet）判 CONDITIONAL，兩條嚴重項必修。

## 0. 前提
- 工作樹 `/home/simon/simon-voice-ime`，HEAD 仍＝ `b29acc8`，未提交的 v6.30 改動在工作樹：**已追蹤檔的 `git diff --stat` 必須恰為 build.gradle＋SimonIMEService.java**；未追蹤檔只需確認四個新檔存在（`SilenceWatchdog.java`、`TextLossGuard.java`、`SilenceWatchdogTest.java`、`TextLossGuardTest.java`），**其餘未追蹤項目（.worktrees/、.bak、日誌、施工單、報告、out/、verify/ 等）本來就存在，不算不符**。只有「HEAD 不對」或「已追蹤改動不止那兩檔」才停手。
- 只改 `SimonIMEService.java`（必要時 `ClipboardHelper.java`）；其餘不動。

## 1. 必修
### F1 本機預覽候選要去重
- `appendRescueCandidate`（約 :1548-1560）組 `local` 時逐段 `append(segment)` 未去重；既有預覽組字（約 :2330-2335）用 `dedupOverlapHead(prev, segment, 6)` 去除段落重疊頭。改成**同一套累加去重**（沿用 `dedupOverlapHead`，或直接重用預覽已算好的 `live` 字串），否則候選長度灌水會誤救、且存進剪貼簿的是帶重複字的壞文字。
- `streamedChunks` 拼接同樣檢查：若既有預覽對 WS chunk 也有去重／重疊處理（v6.x 有 ~300ms chunk overlap），候選拼接沿用同一處理；沒有就維持。
- 補一個 JUnit：把「去重拼接」抽成可測的純函式（例如 `TextLossGuard.joinDedup(List<String>, int maxOverlap)` 或既有 `dedupOverlapHead` 搬成 static），測 ①三段各重疊 3 字時拼出正確長度 ②無重疊時等於單純相加。

### F2 換鍵刪字前的 addToHistory 必須在主執行緒
- `handleWTIResponse` REPLACE 分支（約 :2952-2960）從 OkHttp `onResponse` 背景緒直接呼叫 `clipboardHelper.addToHistory(...)`；`ClipboardHelper.history` 是純 `ArrayList`，主緒的系統剪貼簿監聽也會寫它。改法二選一：①把該 `addToHistory` 包進 `mainHandler.post`（先在背景緒讀出 before/after 字串，再 post）；②在 `ClipboardHelper.addToHistory` 內加 `synchronized` 並保證 SharedPreferences 寫入也在同一鎖內。**建議兩者都做**（post＋synchronized），因 diff 其他救援點都已在主緒，唯獨此處例外。
- 順手核對：diff 內所有 `addToHistory` 呼叫點（WS final、兩個賽跑點、REPLACE 刪前、REPLACE 逾時）逐一標明所在執行緒，寫進報告。

## 2. 可選（不阻斷）
- 看門狗重建失敗後 `audioRecord` 仍指向已 release 物件：可在重建失敗時把 `rebuildFailed=true` 之外再設一個旗標讓迴圈改 `sleep(50)` 並停止再呼叫 `read()`；不做也可，但報告要寫明。

## 3. 建置與驗收（同 v6.30）
- 同 F 節指令重跑 `testPhoneReleaseUnitTest assemblePhoneRelease compileWatchReleaseJavaWithJavac`（`--project-cache-dir out/v630-gradle-cache`）。
- 產物覆蓋 `dist/simon-voice-ime-phone-6.30-release-20260915.apk`，重量 badging（仍 67／6.30）與 sha256。
- 報告追加到 `V630_REPORT.md` 新節「fix-up」：F1/F2 的新舊行號、測試數（應 ≥ 33）、原文輸出。禁自評 PASS。不 commit／tag／push。
