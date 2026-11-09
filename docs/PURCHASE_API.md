# 持久購票請求與 outbox

目前完成 PostgreSQL 接受／終止狀態、同交易 outbox intent、共用保票交易與內部處理入口。M4B 已接 RabbitMQ publisher/consumer，啟動與 commit 後 ACK／重試見 [MQ_DELIVERY](MQ_DELIVERY.md)。M4C已接非同步顧客畫面與本人歷史、原命令恢復，保留同步比較，見 [ASYNC_CHECKOUT](ASYNC_CHECKOUT.md)；尚無效能驗收。`TICKET_PURCHASES_ENABLED=false` 預設關閉，需 orders 與 purchases 同時true才受理新請求。非同步 API 展示須明確啟動 publisher／worker；PROCESSING／202 只代表持久受理，不等於保票。

## API

| 路徑 | 行為 |
|---|---|
| POST `/api/customer/purchases` | `{requestId,ticketId,quantity,expectedTicketVersion,expectedTotalTwd}`；受理 PROCESSING 時202、Location為本人查詢路徑；重送已終止結果200 |
| GET `/api/customer/purchases/{id}` | 僅本人；其他會員／不存在404 |
| GET `/api/customer/purchases?page=1&size=10` | 僅本人、每頁1..20、page1..100000 |
| POST `/api/customer/purchases/{id}/cancel` | `{requestId,expectedVersion}`；新取消 UUID，原 body 重送 |

顧客獨立Cookie/CSRF，owner只從principal；未知owner／worker／session／deadline欄位400。未登入401，缺CSRF403。無公開 process／outbox端點。回覆 Purchase：id/requestId/ticketId、原quantity/expectedTicketVersion/expectedTotalTwd、status/version/reason/orderId、createdAt/deadlineAt/closedAt；不回owner/session hash或outbox名單。

## 狀態與交易

PROCESSING(version1、無order/closedAt) → RESERVED、REJECTED、CANCELLED、EXPIRED(version2)。終止不可復活；RESERVED只代表已建立保票訂單，訂單後續取消／逾時／付款要查本人orderId，不把此receipt當即時訂單狀態。已RESERVED的請求不能用取消請求API釋放票額，須走原訂單版本核對與取消。

預設接受期限60秒，可配置5..120秒，截止取 min(PG現在+期限,售票截止)。PROCESSING／202 不等於保票成功，不扣held或Redis庫存、不啟動10分鐘付款倒數。實際處理成功後的PG保票時間才是order.createdAt，order.expiresAt為 min(保票時間+10分鐘,售票截止)。每顧客最多5個未終止請求；未釋放保票／已付款限購由正式訂單核心再次裁定。

受理：PG owner/session鎖與身份→原 SUBMIT fingerprint replay→開放旗標／有界輸入與未處理數→新操作會員限流→已發布／銷售窗口／價格版本→本人票種資格→PG request＋SUBMIT command＋唯一outbox同交易→再次核對PG時間和身份。FK可能等待ticket writer，故最終受理也檢查原期限與身份；失敗整筆回滾。終止／同UUID重送在新旗標、Redis、限流之前查原資料，改body或跨action409。命令ID按會員隔離；pending UUID不能另走同步PLACE繞過，既有訂單UUID不能再建立不同購票請求。

內部 `PurchaseService.process(eventId)` 只讀本機持久outbox的purchase ID／schema1，使用已接受資料，不接受外部customer或quote。owner→request鎖與原session身份核對，共用 `OrderService.reserve` 的event/ticket／PG窗口、報價、限購、可售、order/PLACE/HOLD帳本交易；最後更新RESERVED也同交易。正式ticket鎖等待後重檢request deadline與session期限。重複／並發event只生一筆訂單，終止event重送為no-op。預期業務錯誤保存REJECTED/reason，期限到則EXPIRED；DB/帳本/constraint錯誤向外拋出並整筆回滾，保留PROCESSING供原event重試，不能把系統失敗當業務拒絕或成功ACK。

资格只在**受理**檢查，不要求Worker等待後仍有原Redis permit。保存原session hash/version：顧客登出、到期、停用／改密碼後，尚未完成的請求不能產生保票（REJECTED或已逾期EXPIRED）；已成功結果不變。資料庫只保存hash，不保存cookie原token。取消／處理／逾時共用owner鎖决定先後；處理先成功需改走訂單取消，取消先完成則Worker no-op。取消前也重驗身份、版本與PG時間。

expiry每輪最多100條原DB期限，沿用TICKET_EXPIRY_ENABLED與delay；即使新接受旗標關閉、顧客登出或API重啟，也能補掃EXPIRED。不是Redis TTL通知，不補任何Redis票額。

## Outbox 邊界與後續

V5新增 purchase_request、purchase_command、purchase_outbox；V1–V4不改。outbox為不可變 PURCHASE_REQUESTED/schema1 intent，每request唯一event ID，只保存aggregate參照與時間；M4B 的 V6 另存 publication lease／attempt／confirm 狀態，durable RabbitMQ 與常駐 worker 共用此 PG 核心；沒有公開 process 按鈕。M4C已接非同步UI，只有RESERVED才導向本人訂單。

已加入publisher claim／期限、confirms/returns、重試與consumer commit後ACK，使用eventId＋purchase核對PG，不信任message owner/body。M4C UI、M4D1 Lua原語與M4D2A持久PG epoch／庫存revision重建底座完成，此舊API不預扣；M4D2B4的預扣入口另為 `/customer/purchase-attempts`，見 STOCK_CHECKOUT。outbox只解PG→MQ意圖，不能解Redis→PG未知窗口；此API仍沒有Redis票額扣減。真單機PG/MQ故障測試不代表跨主機容錯。

驗收：scripts/smoke_purchases.py 正常HTTP、retained-V4 upgrade、實際API重啟／feature-off／admission phases；verify_purchase_invariants.sql 與 verify_payment_invariants.sql唯讀核對。原始token/fixture只存private ignored work，證據見HANDOFF。

M4D2B2 已新增 V9 immutable acceptance／decision／ack，內部 HELD 受理、正式 worker／取消／expiry 的 PG 決定與獨立 Redis 核對完成；B1 progress HELD 保留作歷史，已受理 intent 以正式 decision／ack 判斷是否解決。詳見 [STOCK_PURCHASE_DECISIONS](STOCK_PURCHASE_DECISIONS.md)。M4D2B3 已新增 V10 durable recovery queue／租約／有限 retry／自動排程與程序重啟補做，詳見 [STOCK_RECOVERY](STOCK_RECOVERY.md)。M4D2B4 已接 verified 顧客預扣 coordinator、原 UUID PG-only 進度 GET、預設關閉旗標與未知操作 UI，見 [STOCK_CHECKOUT](STOCK_CHECKOUT.md)。投影仍需內部 READY maintenance，沒有自動重建或持續吞吐宣稱。
