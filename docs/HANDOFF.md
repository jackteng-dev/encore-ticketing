# Encore 售票平台交接

## 2026-11-17：M3 分類、場館、演出者與活動檔案（**含 schema 變更 V25**，疊在 M2 上）

**此 PR 修改受保護路徑 `backend/src/main/resources/`（新增 `V25__catalog_taxonomy.sql`），依部署限制開 PR 後先不 merge，需作者在伺服器端以受控工具切換。**

- Schema：只新增 `category`（含 6 個固定分類）、`venue`、`performer`、`event_profile`、`event_performer` 與索引。不修改既有表格，也沒有改名、刪欄或改型別。
- 回退相容：已實測目前 main 的 jar（V24）在已套用 V25 的資料庫上可正常啟動並服務。Flyway 預設忽略 future migration。
- 後端（`catalog` 套件內新增 `TaxonomyModels`／`TaxonomyService`／`TaxonomyController`，未新增套件）：
  - 公開 API：`/api/public/categories`、`/venues`、`/venues/{slug}`、`/performers`、`/performers/{slug}`、`/events/{id}/profile`。
  - `/api/public/events` 新增 `category`、`venue`、`performer`（slug）篩選；回傳新增 `categorySlug`、`categoryName`、`imageUrl`。
  - 後台：場館與演出者的新增／修改、活動檔案 `PUT /api/admin/events/{id}/profile`。沿用 `catalog_guard`／`catalog_command` 的原命令重送、指紋、版本與稽核；`CatalogService.command` 改為可指定 resource 的公開多載。
  - 圖片網址只接受 `https://` 或 `/` 開頭（DB CHECK＋驗證）。目前留空，前台改用文字海報。
- 前端：
  - 後台新增「場館與演出者」頁 `/staff/directory`，活動管理頁新增「活動檔案」區塊。
  - 前台新增 `/venues`、`/performers` 列表與詳情頁；首頁加分類磚；搜尋頁加分類篩選。
  - 活動頁顯示分類、演出者與場館連結；卡片顯示分類；有圖片網址時顯示圖片。
- 實測：
  - `mvn package` 638 tests 全過（新增 5 項，migration 數 24→25）。
  - `npm run build` 通過。
  - Playwright UI 腳本（`work/m3-ui-check.cjs`，ignored）13 項通過：員工建立與修改、活動檔案 v1→v2、無效圖片網址阻擋、前台各頁、首頁分類磚篩選、手機 375 無溢出。
- Codex 交叉審查：
  - 第 1 輪指出兩點，皆已修正：後台列表只載入前 50 筆（改為讀完所有分頁，有上限並明確報錯）；重試原操作後表單版本未更新（重試後重設表單）。
  - 完整測試另抓到活動檔案重送時 `id` 欄位無法反序列化（`fail-on-unknown-properties`），已加 `JsonIgnoreProperties`。
  - 第 2 輪指出兩點，皆已修正並以 Playwright 實測：場館／演出者版本衝突後未重新讀取（現在 409 時重讀並以最新資料重填）；演出者名稱前端上限 160 與後端 120 不一致。

---

## 2026-11-17：M2 活動搜尋、日期篩選與排序（疊在 M1 上）

無 schema 變更；後端只改 `catalog` 套件內既有檔案（未新增套件），未動 `backend/src/main/resources/`、`infra/`。
- 後端：`GET /api/public/events` 新增選填 `q`（標題／場館，萬用字元按字面比對，最多 60 字）、`from`／`to`（台北時間開演日）、`sort=newest|date|price`、`onSale`；回傳項目新增 `firstStartsAt`、`lastStartsAt`、`sessionCount`、`minPriceTwd`、`maxPriceTwd`、`selling`（只增欄位，舊前端相容）。
- 前端：頁首搜尋框、`/search` 搜尋頁（狀態存在網址，可分享）、共用 `EventCard`（日期、票價、售票中徽章）、首頁快速探索 chips。
- 實測：`mvn package` 633 tests 全過（新增 4 項）；`npm run build` 通過；瀏覽器驗證搜尋、售票中、價格排序、7 天內、無效日期範圍、錯誤網址參數。
- Codex 交叉審查：指出「7 天內／30 天內」多含一天（後端 to 含整日），已改為 +6／+29 天並在瀏覽器確認（11/17→11/23、12/16）。手機 375 寬首頁與搜尋頁無溢出。

## 2026-11-17：M1 網站說明頁與模擬整合入口

方向（作者 2026-11-17 決定）：補齊真實售票站功能；正式金流、社群登入、簡訊、App 等外部服務只做圖示與按鈕，點擊顯示「此為模擬版本」，正式上線時再串接。活動圖片之後由作者另行產生，目前留空。每次改動除自行驗證外，再以作者本機 Codex CLI 交叉驗證。

只修改 `frontend/`（疊在 `feature/stage-redesign` 上），無受保護路徑、schema、API 或旗標變更。

- 完成：
  - 共用模擬說明對話框 `components/MockNotice.vue`、`mockNotice.ts`（不送出任何資料）。
  - 服務說明頁 `/help/:slug`：常見問題、購票須知、退換票規定、隱私權政策、服務條款、客服中心（`info.ts`、`views/InfoPage.vue`）。
  - 頁尾改為四欄，包含說明連結，以及模擬的 App 下載與社群按鈕。
  - 會員登入頁新增 Google／LINE／Facebook／Apple 模擬社群登入。
  - 訂單付款區新增付款方式列：展示模擬付款可用；信用卡、ATM、超商、LINE Pay、Apple Pay、街口顯示模擬說明，不建立付款。
  - 各路由分頁標題（`title.ts`），活動頁標題為活動名稱。
  - OG／Twitter meta、PWA `manifest.webmanifest`、`icon.svg`、`robots.txt`。
- 實測：
  - `npm run build` 通過。
  - 瀏覽器實際走過：註冊 → 等候室取得資格 → 直接保票 → 訂單頁付款方式列。點 LINE Pay 只開說明框，「建立模擬付款」仍在，未建立付款。
  - 手機 375 寬：`/account`、`/account/orders`、`/help/contact` 無溢出。
  - `smoke_customer_browser.cjs` 14 項通過。
- 已知：
  - Windows 上 `work/**/runtime.env` 若為 CRLF，smoke 腳本讀到的密碼會帶 `\r`，導致員工登入 401。本機以 LF 副本執行。
  - `smoke_orders_browser.cjs` 依賴舊旗標組合（未開等候室與購票請求），在全旗標展示環境下找不到「確認保票 →」，本輪未改腳本。

### 後續里程碑（規劃，未實作）
- M2（後端 Java＋前端，無 schema）：活動搜尋（標題／場館）、日期篩選與排序；頁首搜尋框。
- M3（schema）：分類、演出者、場館實體與頁面、活動圖片欄位（可 NULL，先留空）。
- M4（schema）：收藏、開賣提醒、站內通知；Email／簡訊為模擬 outbox。
- M5（schema）：區域座位圖與選位。
- M6（schema）：折扣碼、早鳥票、主辦方銷售報表與匯出。
- M7（schema）：退票申請與票券轉讓（模擬退款）。
- M8（schema）：手機／Email 驗證（模擬 OTP）、實名資料。
- 乾淨展示資料（另建 demo scope，不對保留資料跑 fault tests）。

含 schema 的里程碑依部署限制，PR 開好後先不 merge，由作者在伺服器端做受控切換。

---

## 2026-11-17：前端視覺改版（B 版「夜場舞台」）

只修改 `frontend/`，未動受保護路徑（`backend/src/main/resources/`、`infra/`、套件根目錄），無 schema 變更、無 API／旗標變更。

- 完成：深色主題全站樣式（`style.css` 重寫，保留既有 class）；首頁焦點活動海報、跑馬燈、海報牆、公平排隊說明；活動頁海報＋資訊格、步驟條、場次票種表格；購票頁步驟條與右側合計摘要；等候室大字順位／秒數；訂單狀態徽章配色。
- 重要檔案：`frontend/src/style.css`、`App.vue`、`views/Events.vue`、`views/EventDetail.vue`、`views/Reserve.vue`、`views/Orders.vue`、`components/WaitingRoom.vue`、新增 `poster.ts`（活動無圖片欄位，依標題產生文字海報與固定色調）、`index.html`（Google Fonts：Anton／Noto Sans TC）。
- 保留：購票、排隊、付款、DIRTY gate 與非同步購票邏輯未改；smoke 腳本依賴的文字與 label（「選擇張數 →」「確認保票 →」「保票張數」「加入等候室」「目前排第 N 位，等待入場。」「會員」連結、場次 h3、「還沒有保票訂單。」）均保留。
- 實測：`npm run build`（vue-tsc＋vite）通過；本機 preview 於桌面 1440 與手機 375 寬檢查首頁／活動頁無橫向溢出。後端 `mvn package` 於本機隔離 PostgreSQL 先前通過 629 tests（本輪未改後端）。
- 未完成／已知：`scripts/smoke_*_browser.cjs` 未在本輪重跑；登入後保票→模擬付款→領票流程未逐頁目視；會員／後台頁僅隨 CSS 變深色。字型外部載入失敗時退回系統字型。
- 下一步：跑瀏覽器 smoke；如需真實海報圖片，需另開 schema 變更 PR（新增可 NULL 欄位）並走作者受控切換。

---

## 2026-11-08：自動更新端到端驗證（Encore）

本次只增加此交接段落；不變更應用邏輯、資料庫、帳號、映像 recipe、CI 或部署契約。

- 目的：驗證 Atlas／Encore CD 恢復與主機讀取憑證換新後，正常 PR → 原完整 CI → merge → 私人 GHCR 發布 → Ubuntu 主機自然排程部署的完整鏈仍可用。
- 無可見內容變更；以主機實際部署來源 SHA 等於本次 main 作為判定。
- 主機私人部署完成後，再確認 Funnel 公開唯讀版自動跟隨新部署版本重建。
- 未完成上述實測前，不以本段文件或映像發布宣稱自動更新 PASS；結果保存於 server-management 私人交接。

---

## 2026-10-21 私人 Homelab CD 驗證

- 已完成受控首次部署，來源 `4bf24e78fd18c86c3c818029ce4c632b4a77d014`；此紀錄不是後續自然更新驗收。
- 首次流程包含完整 PostgreSQL、Redis、RabbitMQ 還原及隔離升降／故障回復；正式資料未回灌或初始化。
- 雙 API 與 Worker 採同一 immutable image，Gateway 協調更新；新 Worker 心跳與實際 consumer 均需通過。
- 部署限定無 pending 工作與空佇列窗口，不清訊息、不更改期限，原 DIRTY 持續吞吐限制保留。
- 本次只新增交接紀錄，作為真實 main → CI → 私有映像 → 自然 timer 更新的最小來源變更。
- 驗證標記：`ENCORE-CD-VERIFY-20261021-A`。
- 未改業務程式、資料庫 schema、既有 flags、workflow、部署 recipe 或公開入口。
- 自然更新仍須以新 CI、digest、實際 OCI revision、備份還原與成功 root 交易共同驗收；未完成不可宣稱 AUTO PASS。


更新：2026-10-12（Asia/Taipei；private logs UTC）。以實際遠端main `9e9e088d57e54eba73f1b447520b1276a0f15e39`開始，fetch／HANDOFF／AGENTS／README／status／branch／log／diff含staged／ff-only完成，只修改ticket-platform。前輪交接保存在[M6C](verification/handoff-m6c-2026-10-12.md)。cloud-environment-runtime與Docker／networking已讀，revision351/current/connected/enforced unrestricted；保留proxy／CA／local Docker socket，無configured secrets／identities。沿用唯讀JDK21、自己的Maven cache／flat PG/MQ，未下载新SDK／模型或付費服務；無精確usage介面，按獨立里程碑保存。

## 本次完成

**M6D受控Cloud展示收尾完成。** [展示手冊](SHOWCASE.md)、[功能矩陣](FEATURE_STATUS.md)、[驗收](verification/showcase-m6d-2026-10-12.md)／[JSON](verification/showcase-m6d-2026-10-12.json)。README／ROADMAP／ARCHITECTURE／本地清單改為目前狀態，移除入口「k6／備份／場次窗未做」過期描述；完整舊架構封存。四個顧客頁修正mock／領票文案，未改功能旗標或交易。手冊明示需開旗標、另建展示scope、不對保留資料跑fault tests、時區差異、停止保留volume、故障與partial邊界。

完整測試發現並修復既有**人工REVIEW同UUID競爭**：第一個command讀取為空，peer完成原command後fresh validate誤報REVIEW_CHANGED；現在此錯誤才重新鎖讀原command，核對同actor／intent／body後返回原receipt。不是重試整個交易或取消檢查，其他actor／改body／fresh新UUID仍拒絕，不改票額／期限／job budget／REVIEW。兩項固定latch順序在舊碼失敗、新碼通過。

## 重要檔案

- SHOWCASE／FEATURE_STATUS：可重現正常流程、狀態與證據／limits。
- README／ROADMAP／ARCHITECTURE／CLOUD_LOCAL_FOLLOWUP／DEPLOYMENT：目前架構與收尾／本地優先序；不把退款／MFA／真服務全列換作品前置。
- StockReviewService：原command在READ COMMITTED競爭後重新核對。
- StockReviewIntegrationTest：UNUSED／SETTLEMENT固定peer先完成的deterministic regression，仍核對不改budget／audit／order。
- CustomerLogin／EventDetail／Purchases／Reserve：僅修正過期文案。
- verification/showcase-m6d-*、architecture-before-m6d-*、handoff-m6c-*：去識別證據与完整前輪文件。
- 本輪臨時展示harness／fixture／PNG／env／logs在ignored work/m6d-*；不是新通用產品或CI入口。

## 測試 / build

新空owned PG／Redis／RabbitMQ scope完整**mvn package 629項／30 suites，failure/error/skip0**。Maven256MB、suite384MB、SerialGC／CPU2／fork1／reuseForks=false，沒有H2／跳測試。StockReview單組22/0，新增兩項。Vue型別／production build通過，最新JS index-CH8ZALSJ.js、CSS index-C2FRQd4L.css；JSON有JAR／dist SHA256。V1–V24 source及packaged migration bytes對baseline完全相同。

用同一629項package JAR及最新dist，host EMBEDDED JVM／Vite production preview＋真data containers，**Chromium151 UI10組通過、7項readonly PG不變條件零違規**：活動／場次／12票種／發布、台北OPEN窗口、顧客／員工cookie並存、排隊→202→真MQ正式order、手動mock PAID→明確2張領票與原QR、PG第3張限購拒絕、另一顧客保票取消、實際production SVG圖片解碼只填碼／明確入場／重掃不能第二次、第二張撤銷、readonly monitoring與393×852 fit。PG最後capacity12/held0/sold2、PAID1/CANCELLED1、purchase RESERVED1、issuance1/issued2、CHECKED_IN1/REVOKED1／terminal audit2。這不是最新Nginx／雙API／雙Worker fleet重驗或真手機。

## 失敗與恢復證據

首輪完整627/30為0fail/1error：既有StockReview六執行緒race。新固定順序UNUSED／SETTLEMENT各1error（舊碼共2/2），小幅修復後整組22/0，再另一新空scope629/0；原失敗logs/XML保留，不用重跑隱藏競爭。

展示runtime1：我在harness及手冊填expected role0，MonitoringSettings只允許1–16，啟動拒絕；已改valid2/2並保留EMBEDDED缺split-role警示，不放寬validation。runtime2：harness漏接受發布window.confirm，無publish POST；補明確確認。runtime3：過前7組，但早期捕獲PNG沒有完整QR，local獨立jsQR亦拒絕；改等待完整票券GET與read control unblocked再截原SVG，decoder／票碼未改，也未用另產QR代替。runtime4過10＋7。各次新owned disposable scope／原失敗不覆寫，沒有production自動換UUID重試、放寬deadline／gate／預算；不能確定所有渲染時序機制，這是harness修正而非通用QR改善宣稱。

## 清理與環境

只處理本輪自有scopes，無global prune／daemon restart／其他作品操作。全部自有JVM／preview退出，兩個完整測試scope及四個展示scopecontainers/volumes/networks0，25432／26379／25672／19110／15180關閉。自己的依賴映像／cache／原失敗／成功XML與private receipts保留。所有QR／cookie／個資／dump保持private，不commit。

## 尚未完成 / 已知限制

- **持續預扣仍部分完成**：M6B8/12後DIRTY、餘4不能接續；有限來源授權／同epoch原證據不等於continuous writer／授權輪替。C1/C3增量實驗test-only。沒有新高吞吐宣稱。
- 本機HTTPS／可信proxy／Secure Cookie、DB runtime least privilege／migration分離、完整IAM／全域session清理、真手機／現場、實際24–32GB多作品資源容量未驗。
- 最新V24 fleet未重跑全套Nginx容器故障；歷史M5B/C V22 evidence保留，後續feature多用host JVM。Nginx節點IP換後須reload，最初502/504可能，非無縫HA。
- PG／Redis／RabbitMQ仍單節點（單broker quorum），Cluster／多broker／跨主機HA未做。等候順序與資格Redis-only，不是PG durable fairness。
- M6C備份為V24 ticket_demo有界offline PG dump（100tables／100sequences／64MiB），source sequence0；未驗非空sequence、Redis/MQ/物件volume、PITR／跨版本／正式全服務切換。新target空RedisKEY_MISSING仍DIRTY，新流量disabled。來源與目標不得双邊對外履約。
- 沒有真收款／售票通知／SeaweedFS海報／完整退款轉讓／MFA／指定座位／離線入場；基本監控不是Prometheus／Grafana／告警。EMBEDDED只1node，保留expected API2/Worker2不足警示，不能當全部健康。

## 下一步

受控Cloud展示範圍**結案，可以切回整體展示平台的下一個未完成作品**；不預設無限追加售票商用功能。若回到本repo，先fetch實際remote main、讀此交接／AGENTS、status/branch/log/diff含staged與ff-only；工作副本 `/workspace/work/ticket-platform-review-20261012`。本機可用後優先CLOUD_LOCAL_FOLLOWUP P0；要主打持續大量搶票則先解決DIRTY協調，再warmup／多輪／長時間負載。所有新里程碑仍完整tests/build／HANDOFF／commit/push／remote與clean核對。
