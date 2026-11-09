# 員工庫存維護與核對（M4D2B5）

`/staff/stock` 提供 PG 投影／未解決預扣／REVIEW 清單及原重建操作核對。PostgreSQL 是正式票額來源；本頁不修改正式訂單、付款、預扣決定或 recovery budget，也不提供強制解除 REVIEW；B6另有受權限保護的 [原決定人工核對](STOCK_REVIEW.md)，保留原job／budget。維護寫入預設關閉；沒有自動重建排程或持續吞吐保證。

## 啟用與權限

私有 env 設 `TICKET_STOCK_MAINTENANCE_ENABLED=true`，重啟 API 後由活動管理的「庫存維護與核對」進入。MANAGER 可寫，VIEWER 只能讀票種／問題；登入、Cookie 與 CSRF 沿用員工系統。每筆原操作只能由原員工讀進度／audit 或恢復，另一管理員不能接管。角色變更會使舊 staff session 失效；新登入後降為 VIEWER 的原員工可以讀本人原操作，但不能恢復。

此旗標與顧客 `TICKET_PREDEBIT_ENABLED` 獨立；建立 READY 不自動啟用購票。每個 PG phase 持有原員工 row share lock 並重驗 MANAGER，再鎖原 operation progress，最後依既有 domain 順序鎖票種；不沿用活動管理全域 catalog_guard。寫入與 audit 在同交易中驗權限。HTTP body 不接受 actor／owner／generation／可售票額。

## API

所有路徑前綴 `/api`。GET 只讀 PG，不呼叫 Redis，不 prepare／seed／核對預扣。列表 page 1..100000，size 1..20；預設票種／問題 12、audit 20。

| 方法 | 路徑 | 行為 |
| --- | --- | --- |
| GET | `/admin/stock/tickets` | 投影清單、正式可售、source revision、generation、未解決 intent／REVIEW job 數。 |
| GET | `/admin/stock/tickets/{ticketId}` | 單票種目前 PG 狀態；旗標關閉、Redis 停機仍可讀。 |
| GET | `/admin/stock/tickets/{ticketId}/issues` | 未解決 intent 的原 UUID、state／deadline、正式 purchase／decision／ack、兩類 recovery job 的 state／attempts／安全代碼。 |
| POST | `/admin/stock/tickets/{ticketId}/rebuilds` | 原 body：`requestId` UUID、`expectedSourceRevision` 正整數；相同原員工／票種／revision 才能恢復，衝突 409。 |
| GET | `/admin/stock/rebuilds/{requestId}` | 原員工操作進度；未知／他人 UUID 回 404。 |
| GET | `/admin/stock/rebuilds/{requestId}/audit` | 本人原命令的不可變 checkpoint 歷史，最新在前。 |

POST／原 progress GET 的 `X-Expected-Staff-Username` 只核對實際登入員工，帳號變更回 401；它不提供 actor。POST 必須 staff CSRF。未登入或只有 customer cookie 不具有 staff 權限。

Receipt 包含原 request／ticket／source revision、state、generation（未分配時 null）、version、安全 failure、canResume、PG updatedAt，以及目前 PG sourceRevision／generation／state。REGISTERED／CLOSED 回 202，READY／DIRTY／BLOCKED 回 200；Location 是原 UUID GET。canResume 只表示旗標與原員工角色允許恢復原 REGISTERED／CLOSED，不保證 Redis 健康或 revision 仍適用。

問題 DTO 不回 customer／owner／session hash／token／密碼／原 Redis receipt。REVIEW 數為未解決工作數，同一 intent 可有兩類工作；不是不同顧客數。ack 已完成或 unused ABORTED 不列為未解決。

## 持久 phase 與 audit

V11 新增三表：immutable `stock_maintenance_command` 綁 global UUID／原 actor／票種／source；mutable `stock_maintenance_progress`；immutable `stock_maintenance_audit`。V1–V10 bytes/checksum 不改，舊 internal rebuild 不回填 actor，不能被新 HTTP 操作採用。

1. 獨立 PG transaction 驗員工、保存原命令＋REGISTERED version1／audit。新旗標關閉則不登記；已有原命令先核對 body，terminal 直接 PG 回放。
2. 既有 `StockProjectionService.begin` phase 中驗員工與原 progress，核對 source／未解決 intent，再提交原 V7 rebuild、PG CLOSED、operation CLOSED／audit version2。任一 audit 失敗整個 phase rollback，先前 REGISTERED 保留。
3. 原 complete phase 驗權限／identity／generation／snapshot，執行既有有界 Redis 核對後提交 PG READY 或 DIRTY/CLOSED，以及 operation checkpoint／audit。如果 Lua 已 OPEN 而 READY audit rollback，PG 與 operation 都維持先前 CLOSED；同 UUID 恢復同 epoch，不盲目補票。

CLOSED Redis 未知結果保存 CLOSED／REDIS_UNKNOWN 與新 audit；同 UUID 可重試。KNOWN stale revision／unresolved intent／fenced generation 等阻擋保存 BLOCKED，未分配 generation 時保持 null。Redis corrupt／不一致由既有 domain 封閉門並保存 DIRTY。READY／DIRTY／BLOCKED 是該操作 terminal，不能改回可重試狀態。

歷史 READY 不等於現在 READY 或 Redis 健康：正式 stock journal 仍可使目前 gate DIRTY，另一明確 command 也可形成新 generation。原 terminal POST／GET 只讀 PG，不碰 Redis、不重新 INIT。全部原 intent／decision 解決後，管理員重讀最新 revision，再**明確提出新 command**取得新 epoch；不因頁面讀取自動重建，不用重建掩蓋 REVIEW。

coordinator 禁止外層 transaction，沒有 Redis／PG 分散式原子 commit。PG 提交結果未知需查原 UUID；不能丟棄命令或換 UUID 自動重送。Redis 呼叫仍在維護專用票種鎖內，connect／command 各 500ms；PG transaction timeout10秒，沒有 latency／throughput SLA。

## 未知操作 UI

沿用 `encore-catalog-pending-v1`：POST 前保存原 staff／path／UUID／完整 body，與活動管理共用寫入 freeze。重整仍保留，另一帳號不能接管。頁面「核對原維護操作」先 GET 原 UUID，只有本人 404 或 verified canResume=true 才 POST 同一 body；READY／DIRTY／BLOCKED 的完整匹配收據才解除 freeze。REGISTERED／CLOSED、401／403／409／429／5xx、連線及 malformed ACK 都保留。

已驗證 GET 的 CLOSED 進度不因後續 POST 失敗而消失。維護關閉時仍能 GET，但不 POST、換 UUID 或丟掉未解決 command。沒有自動 POST polling；sessionStorage 不含 Cookie／密碼，關整個分頁會失去 client pending，PG 原命令仍在。目前沒有本人操作歷史列表／跨裝置 pending 恢復。

## 驗證與尚未完成

18 項 StockMaintenanceIntegrationTest 使用真 PG／Redis，涵蓋六並發原 UUID、同交易 audit rollback、Lua OPEN 後 PG rollback、phase 間 role 變更、ownership／旗標、known blocker／corrupt ledger、正式決定解決後新 epoch、不可變與 terminal guards、REVIEW 保留／外層 transaction 拒絕。

`scripts/smoke_stock_maintenance_browser.cjs` 只用 loopback production JAR／dist，分 prepare、offline、flag-off、resume、review。operator 控制自有 Redis／API 重啟及 final-JAR domain REVIEW fixture；env／fixture／output 必須在 work/，容器必須明確匹配 `ticket-maintenance-...-postgres-1`，故障 constraint 只允許拋棄式隔離資料。參數：`TICKET_RUNTIME_ENV`、`TICKET_MAINTENANCE_FIXTURE`、`TICKET_MAINTENANCE_OUTPUT`、`TICKET_TEST_POSTGRES_CONTAINER`。不要在正式資料上執行故障演練。

`scripts/verify_stock_maintenance_invariants.sql` 是 PG 只讀八項完整性核對，容許歷史 READY 對目前 DIRTY；不驗全部 Redis ledger／未登記 receipt。實測證據見 HANDOFF 與 verification JSON。Browser route.abort／欄位修改是 client ACK 模擬，PG CHECK 是 commit 前 rollback；未注入 PG TCP commit ACK drop 或 Redis TCP 成功回覆丟失。

B6原決定人工 REVIEW 核對已接另存actor／原 command／PG decision／ack核對與不可變操作紀錄，見 [STOCK_REVIEW](STOCK_REVIEW.md)；不能直接重設 attempts、把 REVIEW 改 WAITING、強制完成或覆寫原決定。投影自動協調、incremental journal apply、未登記 receipt 全台帳核對、epoch GC、共享 staff session、Cluster／多節點 MQ／k6／HA、正式付款／出票仍待做。
