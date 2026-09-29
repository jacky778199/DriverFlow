# Firebase 同步設定與待完成範圍

狀態：目前 App 只有本機保存與 JSON 匯入／匯出，沒有登入或雲端傳输。未配置時不會上傳任何紀錄。

## 無 Google framework 的裝置

Google 帳號本身可在外部瀏覽器登入。Android 原生 Sign in with Google 不能當作所有裝置都具備的能力，因此建議統一採外部瀏覽器＋Firebase Web Google 登入。不可在內嵌 WebView 冒充瀏覽器，也不可把 ID token 放在可被其他 App 攔截的自訂網址中。

## 先建立的資源

1. 到 https://console.firebase.google.com/ 建立專案；先採 Spark 免費方案評估。
2. Authentication → Sign-in method → 啟用 Google，設定支援信箱。
3. 建立 Cloud Firestore，選適合的區域，以正式模式開始，禁止公開讀寫。
4. 新增 Web App，保留專案 ID、authDomain、apiKey、appId，供登入網頁設定。
5. 新增 Android App：套件名 `tw.driver.schedule`。如果未來採原生登入，再設定簽章 SHA 指紋及下載 google-services.json；單有此檔案不足以完成外部瀏覽器登入。
6. 選定 HTTPS 登入網址與部署位置，加入 Firebase Authorized domains。需要安全的登入橋接服務，可能涉及付費方案或額外伺服器，應確認費用後再開通。

## 待實作與驗證

- 外部瀏覽器登入、帶隨機 state 的一次性授權交換、經驗證的 Android App Links；App 以一次性碼交換憑證，憑證放 Keystore 保護的儲存。
- Firestore 每位使用者獨立路徑 users/{uid}/rides/{id}，規則限制 request.auth.uid == uid；另限制可寫欄位與大小。
- 本機持久化待同步佇列；逐筆版本、伺服器時間戳、離線重試及衝突畫面。不能只在每次連線把整份裝置資料覆蓋雲端。
- 手機新增 → 平板收到、兩邊同時編輯、離線再連線、帳號切換、登出及重新安裝的整合測試。
- 原圖是否同步另行設定儲存與成本；目前 JSON 不包含圖片。

官方文件：
- https://firebase.google.com/docs/auth/web/google-signin
- https://firebase.google.com/docs/auth/web/redirect-best-practices
- https://firebase.google.com/docs/firestore/security/rules-conditions
- https://firebase.google.com/pricing

## 圖片辨識與路線方案

- 免費雲端圖片轉文字：現有 Gemini 可使用有免費層的文字／視覺模型。免費配額依模型和專案而異，不保證固定每日次數；以 AI Studio 的實際配額為準。免費層的資料使用方式與付費層不同。
- 本機 OCR 候選：ML Kit Text Recognition v2 支援中文，採打包模型避免首次下載依賴；無 GMS 裝置仍需實機驗證。這次尚未加入 OCR 引擎。
- Google Maps URLs 不需 API Key，可指定起訖點，這次已接入。無 GMS 可由瀏覽器開啟。
- App 內自動取得兩種車程，需後端呼叫 Routes API；需帳單設定、配額／快取設計和明確估算時間戳，不能假設 Maps SDK key 就可直接取得路線。

來源：
- https://ai.google.dev/gemini-api/docs/pricing
- https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- https://developers.google.com/maps/documentation/urls/get-started
