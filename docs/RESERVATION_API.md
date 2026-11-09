# M2B 同步保票合約

本階段是展示保票，付款只提供[免費模擬器](PAYMENT_API.md)，沒有真收款或出票。PostgreSQL是唯一正式票額／訂單來源，Redis/RabbitMQ尚未接入。`TICKET_ORDERS_ENABLED`預設false；明確true才允許新保票。關閉新保票後仍可查詢、重放已成功指令、取消及逾時釋放。

| 方法／路徑 | 合約 |
|---|---|
| POST `/api/customer/orders` | `{requestId,ticketId,quantity,expectedTicketVersion,expectedTotalTwd}` |
| GET `/api/customer/orders?page=1&size=10` | 僅本人；page1–100000／size1–20；`{items,total,page,size}` |
| GET `/api/customer/orders/{id}` | 僅本人，別人的ID同不存在回404 |
| POST `/api/customer/orders/{id}/cancel` | `{requestId,expectedVersion}`；顧客CSRF；原UUID/body重送 |

M3開啟admission旗標時，新保票還需本人／同票種短效資格，见[WAITING_ROOM_API](WAITING_ROOM_API.md)；原已成功UUID重放不受資格／新操作限流影響，取消／到期／付款不依賴Redis。

所有端點需獨立顧客session；POST先取得`/api/customer/auth/csrf`的header/token。owner只從驗證後的CustomerPrincipal取得，未知customerId／actor欄位400。員工session不能代替顧客。回覆Order包含id/requestId/ticketId、活動／場次／票種／時間／價格快照、quantity/totalTwd、status/version、createdAt/expiresAt/closedAt、nullable payment與mockPaymentsEnabled；不回顧客ID、session、hash或密碼。

## 票額、限購與期限
- 可售`capacity-held-sold`；V3新增held/sold及DB CHECK。未付款保票增加held；M2C核對成功後held轉sold。
- 一筆訂單一票種、1–10張。server重查票價/version，總額由DB票價乘數量，不信任client總額。每人每票種限購以所有未釋放PENDING_PAYMENT及PAID加總核對，最多5筆同時待處理訂單；取消／逾時釋放後可重新保票。
- 發布且salesStart≤DB現在時間<salesEnd才可建立。預設保留10分鐘，expiresAt=min(鎖後DB時間+10分鐘,salesEnd)，持久保存。頁面倒數僅參考，不會改票額或自行改訂單狀態。
- 即使到期，只要仍PENDING_PAYMENT且尚未物理釋放，仍佔held及限購，保守拒絕可能暫時延續至sweeper完成；不先減掉已過期數量再任意補回。

## 一致性與重放
固定鎖順序：customer_account → 顧客session shared lock → event shared lock → ticket row lock → order row lock。背景expiry不需有效登入，但仍先鎖該顧客。不同ticket可共享event鎖，封存UPDATE與新保票互斥；不取得catalog_guard。

權限在交易內再次核對enabled/sessionVersion/token row及期限；取得票種鎖後重新取DB clock，不能用等鎖前的時間。條件增加held、訂單不可變價格快照、HOLD紀錄與指令記錄同交易。取消／到期減held、狀態/version/closedAt與RELEASE同交易。故障任何位置全rollback；無中途補回指令。

`(customerId,requestId)`唯一且涵蓋PLACE/CANCEL/PAY/RECONCILE，fingerprint含操作／scope／完整body。同UUID改body或改action409，別的顧客可用相同UUID。重放先核對身分及fingerprint，回同一訂單**目前狀態**，不延長期限；即使售罄、封存、新保票關閉或已取消／到期，也不重新保留。

## 狀態與釋放
未建立付款時`PENDING_PAYMENT(v1) → CANCELLED(v2)`或`EXPIRED(v2)`；M2C付款建立／事件處理也提升version，期限內成功可→PAID，終態不復活。PAID不允許普通取消，須後續退款流程。取消取得鎖時已到期，回EXPIRED；若已有其他操作提升version，新cancel stale409，先讀再決定。原cancel UUID重放不再釋放，新的CANCELLED／EXPIRED終態cancel需當前version、只記錄no-op command。

RELEASE以`(orderId,phase)`唯一；held下限與總量受DB條件更新／CHECK限制。任意多個expiry worker與取消競爭只一筆release。停用顧客／封存活動／關閉新保票不阻止背景釋放。

API內sweeper每30秒最多100個due IDs，每筆獨立短交易；`TICKET_EXPIRY_ENABLED=true`預設啟用、`TICKET_EXPIRY_DELAY_MS=30000`。多實例重掃安全但尚未作效能調校；故障下一輪重試，資料不一致會拒絕釋放並記error，不盲減。沒有exactly-once／分散式原子交易宣稱。

## 前端未知結果
sessionStorage只保存原顧客UUID、path及原指令UUID/body，不放登入密碼/token。未知網路、5xx、401/403、408/429保留原指令，凍結新保票／取消／付款；換帳號不能接管，原顧客重新登入後重放。確認ACK才清pending；後續GET失敗保留已確認receipt，只重讀。較舊GET不得覆寫較新version的取消receipt，receipt只呈現給原顧客。

`/events/{eventId}/reserve/{ticketId}`選張數；`/account/orders`歷史／倒數／取消；登入可返回原checkout，同源限定returnTo。私有旗標開啟時顯示免費模擬付款與原結果核對，頁面明示不收款、不出票。各分頁sessionStorage獨立，跨裝置用server歷史核對，不宣稱全裝置共享pending。

## 驗收與下一步
完整真PG測試包含最後一張、同顧客並發限購／同UUID、最多5筆、權限撤銷、鎖後截止、audit rollback、取消／多expiry競爭、archive／feature-off重放及migration相容。正式JAR HTTP／Chromium實測回覆遺失、雙顧客隔離、讀寫回覆順序、真實截止和JAR停機跨截止後恢復；具體結果見HANDOFF及verification。

M2C已加入mock付款、order PAID與payment REVIEW；仍缺退款／出票、outbox、Redis等候室／Lua、MQ/Worker模式、全帳號營運與正式部署。V4處理付款成功與取消／到期競爭，晚到付款只記REVIEW不復活已釋放保票；詳見PAYMENT_API。

M4A持久請求另見[PURCHASE_API](PURCHASE_API.md)。PROCESSING的同UUID不能另走同步PLACE；處理成功會留下原PLACE命令與orderId，原body同步重放只回既有訂單，不再扣票。兩者共用PG保票核心，沒有第二份票額帳本。
