# Simon Voice IME — Android 語音輸入法

專為**繁體中文**（含法律用語）優化的 Android 語音輸入法。連接 WhisperToInput Proxy 伺服器，提供高精度語音辨識 + AI 校正；同時支援本機離線辨識（Sherpa-ONNX SenseVoice）。

## 功能（4 種模式）

| 模式 | 說明 |
|------|------|
| 追加 (Append) | 語音直接轉文字，追加到游標位置 |
| 換字 (Replace) | AI 根據上下文智慧替換選取文字 |
| 拼字 (Spell) | 逐字描述修正難字 |
| 翻譯 (Translate) | 語音即時翻譯 |

## 系統需求

- Android 8.0+（API 26+）
- 伺服器端：WhisperToInput Proxy（見下方說明）

## 伺服器端依賴

此 App 需搭配 WhisperToInput Proxy 伺服器（私有）。伺服器使用：

- **Groq API** — Whisper STT + Llama 文字整理（必填）
- **Google Gemini API** — 語意校正（選填，免費額度足夠）
- **SambaNova API** — 長文處理備援（選填）

## 安裝方式

### 直接安裝

從 [GitHub Releases](https://github.com/poyuchenlaw/simon-voice-ime/releases) 下載最新 APK。

### 自行編譯

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
git clone https://github.com/poyuchenlaw/simon-voice-ime.git
cd simon-voice-ime
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 設定

1. 安裝 APK 後到系統設定啟用輸入法
2. 在 App 設定頁填入伺服器地址和認證密碼
3. 首次使用「換字」模式會自動下載本機模型（~220MB）

## 自行架設伺服器端（Claude Code 一鍵部署）

如果你有自己的 WhisperToInput Proxy 伺服器程式碼，安裝好 [Claude Code](https://claude.com/claude-code) 後貼上：

```
請幫我部署 WhisperToInput Proxy 語音輸入伺服器：
1. 建立 Python venv 並安裝 requirements.txt
2. 設定環境變數：
   - GROQ_API_KEY（從 https://console.groq.com/ 免費取得）
   - GEMINI_API_KEY（從 https://aistudio.google.com/apikey 免費取得，選填）
   - SAMBANOVA_API_KEY（從 https://cloud.sambanova.ai/ 免費取得，選填）
   - WHISPER_PROXY_PASSWORD（自訂認證密碼）
3. 用 PM2 啟動：pm2 start main.py --name whisper-to-input --interpreter python3
4. 確認 http://localhost:8001/ 回傳 status: online
5. 設定反向代理（Cloudflare Tunnel 或 nginx）提供 HTTPS
```

## 更新機制

App 內建 OTA 更新 — 設定頁「檢查更新」自動從 GitHub Releases 下載最新版。

## 版本歷史

- **v3.2** — Gemini 語意校正、英文品牌名修正、語助詞清除
- **v3.1** — SenseVoice 本機辨識、模型內建 APK
- **v3.0** — 三層 STT 架構
- **v2.7** — 全模式本機 STT + OpenCC 簡繁轉換
- **v2.6** — 本機 STT 加速換字模式（Sherpa-ONNX SenseVoice）
- **v2.5** — 內建法律常用片語庫
- **v2.4** — 翻譯/拼字修正、指令編輯器、鍵盤重新設計

## 授權

MIT License
