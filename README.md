# DriverRoutine — 個人接送排程 Android App

單一司機、單一車輛使用的 Android 原型，以繁體中文操作，功能涵蓋 AI 預約辨識、Google Maps 路線估算、WebSocket 訊息搶單、費用記錄與 Firebase 同步（架構已備，尚未啟用）。

- **最低 SDK**：Android 8.0（API 26）
- **目標 SDK**：Android 15（API 35）
- **建置工具**：AGP 8.6.1 / Gradle 8.10.2 / Kotlin + Jetpack Compose

---

## 功能頁面

### Page 1 — 當日排程

- 啟動預設進入當日排程，可按前／後一天查歷史或未來紀錄。
- 行程依接客時間排序，分類標籤：自費（淡金）、補助單（淡藍）、日照（淡綠）、未分類。
- 卡片勾勾直接切換完成狀態（灰色未完成、綠色已完成），日結同步更新。
- 左側 00–24 時進度條，每 15 秒更新；卡片不按比例定位，已過接客時間不代表實際位置。
- 姓名、上下車地點各有複製按鈕；上下車地點有導航按鈕（優先開啟 Google Maps 開車導航）。
- 乘客旁「客上／客下」圖示複製 `乘客名稱客上` / `乘客名稱客下` 文字，並可透過 Message 連線回報。
- **估算車程**：一次估算當日全部行程，或單張卡片「更新此趟車程」，使用 Google Routes API（`TRAFFIC_AWARE`）。
  - 顯示本趟分鐘／公里、上一趟銜接分鐘、估算出發時間、預計抵達及早到／晚到分鐘。
  - 地點透過 Places API (New) 查詢；多候選時需手動選擇。

### Page 2 — AI 辨識新增

- 貼上預約文字，或選 JPEG／PNG／WebP 圖片（最多 8 MB），也可同時提供兩者。
- 按「傳送並使用 AI 辨識」，由 Gemini 或 DeepSeek 解析，產生去回程草稿。
- 草稿可逐張確認、略過或修正，儲存時成對建立，之後各自獨立編輯。
- 右上「清空全部」取消進行中處理並清除草稿；已存行程及原圖不受影響。
- 辨識不直接加入排程，結果先列草稿，司機可比對原文／原圖修正。

### Page 3 — 費用記錄（ExpenseScreen）

- 記錄每日營運費用，與排程行程分開管理。

### Page 4 — 地圖

- 顯示當日行程目的地於 Google 地圖上。
- 每筆目的地可啟動裝置地圖 App（`geo:` intent）進行導航。

### Page 5 — Message 搶單

- 透過 WebSocket（WSS）連線接收 LineFlow 訊息，背景常駐接收。
- 可設定關鍵字（預設：即時可等、報分、跳表、自費、+300、+400），命中訊息高亮並發系統通知。
- 命中訊息可「評估可行性」：依目前行程位置與下一趟銜接，以 Google Routes 計算三段車程，綠色可行／紅色延誤，確認後可回覆原聊天室。
- 回報對象（預設 `小明`）可在設定中自訂，客上／客下按鈕透過同一連線送出。
- 詳細設定與驗收流程見 [docs/MESSAGE.md](docs/MESSAGE.md)。

---

## 本機建置

### 需求

- Android Studio（最新穩定版）或 Android SDK
- API 26 以上的模擬器或裝置

### local.properties 設定

```properties
# Android SDK 路徑（Android Studio 自動產生）
sdk.dir=/path/to/Android/Sdk

# Google Maps / Places / Routes API Key（需在 Google Cloud 啟用對應 API 與帳單）
MAPS_API_KEY=你的MapsApiKey

# Routes API 可單獨設定，未填時沿用 MAPS_API_KEY
# ROUTES_API_KEY=你的RoutesApiKey

# AI 辨識（Gemini 與 DeepSeek 可擇一，兩者皆空則停用 AI 按鈕）
GEMINI_API_KEY=你的GeminiKey
GEMINI_MODEL=gemini-2.5-flash
DEEPSEEK_API_KEY=你的DeepSeekKey
DEEPSEEK_MODEL=deepseek-flash
```

> **注意**：`local.properties` 已列入 `.gitignore`，不會進入版本控制。API Key 由 Gradle 注入 `BuildConfig`，不 hardcode 於原始碼，但仍可從 APK 取出，請在 Google Cloud 設定 API 限制與用量上限。

### 建置

```bash
# 一般建置（Android Studio 同步後直接執行即可）
./gradlew assembleDebug

# 若 Android Studio 鎖住 app/build，可改用獨立產物目錄
./gradlew assembleDebug -PdriverBuildDir=build-ai-check
```

---

## AI 辨識

- **Gemini 優先**：HTTP 408 / 429 / 5xx 或網路錯誤，且 DeepSeek Key 已設定時，自動切換一次備援。
- **DeepSeek**：Chat Completions JSON 模式、關閉 thinking；文字與圖片均支援。
- Timeout：每家 connect 5 秒、read 15 秒、整個 call 15 秒；兩家合計最多約 30 秒。
- 辨識前畫面說明目前使用服務與備援設定。

---

## Message 連線設定

1. Page 5 → **連線設定**，填入 WebSocket 端點（`wss://`）、Access Token、instance_id，按「儲存並開始」。
2. Token 以 Android Keystore AES-GCM 加密儲存於 `noBackupFilesDir`，不進系統備份。
3. App 與 Server 強制使用 WSS 加密；明文 `ws://` 僅允許單元測試在 loopback 使用。

---

## 資料與同步

- 行程存於裝置 `SharedPreferences`（JSON），可匯出為 JSON（schemaVersion=1，不含原圖）或 UTF-8 BOM CSV。
- JSON 匯入時，相同 ID 不同內容會詢問保留版本。
- **Firebase 同步**：架構與帳號流程已備（`FirebaseSyncManager`、`SyncAccountDialog`），尚未正式啟用。
  詳見 [FIREBASE_SETUP.md](FIREBASE_SETUP.md)。

---

## 已知限制

- 出車地點預設為「蘆洲（出發地，待定位）」，尚未用於任何路程推算。
- 全部訂單固定為輪椅接送，無輪椅確認開關。
- 接客時間為「最晚到達」；上下車各 5 分鐘緩衝已用於車程估算，但不追蹤 GPS 實際位置。
- AI 辨識結果僅為草稿，不自動加入排程，所有欄位需司機確認。
- 目前為個人測試版（直接呼叫 API），正式部署前應改為後端代理，避免 Key 嵌入 APK。

---

## 單元測試

```bash
./gradlew test
```

涵蓋範圍：

| 測試檔案 | 涵蓋內容 |
|---------|---------|
| `DailyRecordsTest` | 日結計算、分類統計 |
| `DaySummaryTimelineTest` | 時間軸渲染 |
| `InsertionFeasibilityTest` | 插單可行性（三段車程、緩衝、跨日） |
| `LineFlowTest` | WebSocket 握手、關鍵字正規化、去重、分頁、Token 驗證 |
| `MessageActionsTest` | 送出編碼、回執配對、逾時不重送 |
| `WorkDurationTest` | 工時計算 |

---

## 相關文件

- [Message 使用說明](docs/MESSAGE.md)
- [Firebase 設定](FIREBASE_SETUP.md)
