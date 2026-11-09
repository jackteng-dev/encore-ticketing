# 電子票券 QR 與掃描入口（M4E3）

免費、自架、瀏覽器本地處理。QR 是原 M4E1 的 bearer 票券碼另一種呈現，不建立新票、不換碼、不增加庫存，也不是付款證據。後端 API／V1–V20／權限／單次驗票交易不變。

## 持票人

本人 `/account/orders/{orderId}/tickets` 在完整 Bundle 核對成功後，只對 `ACTIVE` 且目前付款／帳號 eligible 的票顯示 QR。每張包含且僅包含原本 43 字元 base64url code；不包含網址、會員資料、訂單 ID 或 query string。使用 `qrcode-generator@2.0.4`（MIT）在本地產生 SVG，M 級糾錯、四格 quiet zone、黑白且固定比例；不使用第三方圖片或 QR 服務。

已入場／撤銷的票隱藏 QR 與原始碼，歷史仍保存；重新讀取／API 重啟使用原碼。eligible 只是目前付款與帳號狀態，M4E4 已加入[入場時間窗](ADMISSION_WINDOWS.md)，QR 顯示不保證可入場。已有付款 REVIEW 时不展示 QR，仍提示核對。截圖同樣是 bearer 憑證，不能放進公開報告。

## 工作人員

MANAGER 於 `/staff/admissions` 先選活動／場次，才顯示掃描入口。VIEWER 無掃描、驗碼及寫入入口。原 staff pending／busy／讀取狀態會停用掃描；伺服器仍獨立核對原本人、角色、CSRF、場次、版本、支付來源及 terminal 狀態。

1. 明確選擇相機或 QR 圖片。`jsqr@1.4.0`（Apache-2.0）在瀏覽器解析畫面。
2. 解碼只接受完全符合原碼格式的內容，填入 textarea，清除上一張的核對結果。URL／JSON／帶空白的碼不會被開啟或執行。
3. 明確按「核對票券」，向原 inspect POST 提交 code 與預選場次。錯場次不會入場。
4. 核對成功後，再明確按「確認驗票入場」；沿用原 UUID／body／完整 receipt，以及 M4E2 未知操作核對／停止規則。

掃碼本身沒有 API、入場或撤銷效果。重新開始扫描會清空舊碼與核對，失敗也不能沿用上一張的「已核對」畫面。手動改碼或換場次也清除原核對；清單選票仍不能繞過驗碼入場。原成功 recovery receipt 仍可顯示，不把 programmatic 恢復場次當成另一張票的 inspection。

## 圖片、相機與生命週期

- 圖片只接受 PNG／JPEG／WebP，最多 5 MiB、解碼後最多 1600 萬像素，分析 canvas 最長邊 1280。SVG 不接受；不保存、上傳或向其他服務傳圖。
- 相機只在使用者明確點擊後呼叫 `getUserMedia`，不要音訊、優先後鏡頭；每 160ms 分析一幀。成功讀碼就停止所有 track。
- 相機需 HTTPS 或 loopback secure context 與支援的瀏覽器。權限拒絕、不支援時保留圖片／手動輸入；不強制下載付費軟體。
- 明確停止、pending／busy／loading、換場次、visibility change、pagehide、route unmount 都停止 track／timer，清除 srcObject。權限或圖片晚到時以 generation 檢查丟棄，晚到的 stream 亦立即停止，ImageBitmap 在 finally 關閉。
- 不快取相機畫面、圖片或額外 code 到 storage／URL。原 CHECK_IN pending 依既有契約仍含原碼；查到完整證據後才清除。

大圖像素限制是在瀏覽器解碼後檢查，不是對 image decoder 的硬記憶體上限。實際手機解碼耗時、對焦、背光、螢幕反光、旋轉／相機切換／背景恢復、HTTPS 權限需本地裝置驗收。使用本地純 JS library 並不等於實體現場驗收完成。

## 可重現的 Cloud 驗收

完整 backend `mvn package` 不跳測試與 frontend `npm run build` 後，對**新空、明確自有** `ticket-qr-runtime-e3`（PG25433／Redis26380／API19111／preview15181）執行：

```bash
python scripts/run_ticket_qr_runtime.py \
  --env work/<自己的隔離路徑>/runtime.env \
  --output work/<自己的隔離路徑>/proof
```

腳本要求 private work、正確資源 label／loopback port／空 PG，執行正式 JAR／dist 與 Chromium；不操作其他範圍、不 schema clean／repair／baseline。`smoke_ticket_qr_browser.cjs` 建立合成 mock PAID／原碼，從實際頁面 SVG 截圖解碼，驗證沒有自動驗票、錯場次拒絕、無效圖片、晚到 bitmap／相機、track 關閉、未知 ACK freeze／原 receipt、owner／VIEWER 與手機寬度。再實際停止／等待 API 退出，以兩票券旗標 false 重啟，核對原碼／QR 並拒絕新驗票；最後執行來源／票券／恢復只讀 invariants。

相機測試使用合成 canvas MediaStream，經真 video 與 production jsQR，證明瀏覽器控制流程；**沒有實體相機或手機宣稱**。所有帳密、原碼、圖片、storage、原始 log 與失敗截圖留 0600 ignored work。公開的 verification JSON 只保存無 bearer 的統計／限制／artifact hash。實際執行結果見 HANDOFF，不將工具存在當成已執行。

## 入場時間窗

M4E4 已接 [版本化每場入場窗口](ADMISSION_WINDOWS.md)，未設定／未開／已關不接受新 CHECK_IN；原 successful receipt 與未用撤銷不受當前關窗限制。QR仍只是原碼，窗口資訊與驗票提交由DB重新判定。以上M4E3 runner固定對應V20／f754319；V21驗收改用run_admission_window_runtime.py，新工具亦涵蓋production QR screenshot／掃描入口／原ACK恢復。
