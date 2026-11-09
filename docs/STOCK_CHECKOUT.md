# 顧客預扣購票入口（M4D2B4）

已接 verified 顧客 coordinator、本人原 UUID 進度 GET 與未知操作 UI。PostgreSQL 是正式票額／訂單的來源；Redis HELD 是暫時預扣，不是保票或待付款。沒有新增 migration，已發布 V1–V10 不改。

## 預設關閉與啟用條件

`TICKET_PREDEBIT_ENABLED=false` 預設。要求 true 且 API 的 orders、purchases、delivery、publisher、stock-recovery、expiry 全部 true，才有效開啟；缺任何設定時有效旗標仍 false。public event detail 的 `preDebitEnabled` 回報有效設定，售票全域開關關閉時仍 false。Worker 必須另行啟動並啟用 consumer；此設定檢查不代表 worker／Redis／MQ 當下健康。

每個票種還須由 maintenance `StockProjectionService.begin/complete`（可經 B5 員工 coordinator／UI） 核對 PG source revision 與 Redis ledger，取得 READY。**入口不建立／重seed／自動重建投影**。正式 stock journal／settlement／abandonment 使 epoch DIRTY／凍結後，只有全部原 intent／decision 解決才能重新維護新 epoch。啟用旗標不代表每張票投影已 READY；B5 已接受 staff 權限保護的 [維護頁](STOCK_MAINTENANCE.md)，沒有自動 projection 排程／連續吞吐保證。

既有 `/customer/orders` 與 `/customer/purchases` 保留作 PG 同步／非同步比較；它們不走此預扣入口。維護時仍需正式關閉 sales／admission 並處理舊 writer。不是啟用預扣後全網站強制 Redis gate。

## API 與交易邊界

所有路徑以 `/api` 為前綴，需 verified customer cookie；POST 要 customer CSRF。前端帶 `X-Expected-Customer-ID` 防頁面載入後 cookie 切換，該 header 只核對已驗證身分，不能指定 owner。未知或他人原 UUID GET 回 404；不查 Redis，也不建立 intent。

| 方法 | 路徑 | 行為 |
| --- | --- | --- |
| POST | `/customer/purchase-attempts` | 原 Place body：requestId、ticketId、quantity、expectedTicketVersion、expectedTotalTwd。相同本人 UUID/body 恢復原操作，衝突 409。 |
| GET | `/customer/purchase-attempts/{requestId}` | 以顧客**原 request UUID** 查 PG 進度；不是 intent UUID／purchase UUID。旗標關閉／Redis 停機仍可讀。 |

Progress 僅含原 requestId／ticketId／quantity／原報價、state、原 deadlineAt、canRetrySameRequest、purchase（null 或正式安全 DTO）。不回 session hash、owner、intent、generation、job／Redis receipt。POST 未受理、PROCESSING 回 202；ABORTED 或已正式終止／RESERVED 回 200，Location 指向原 UUID GET。

coordinator 先用 verified owner lock 核對 PG 原 UUID/body。已 acceptance 或舊 SUBMIT 正式收據先回放，不依 Redis／新入口旗標。尚未受理才做早期 admission 檢查，再依序 **prepare commit → Lua／progress commit → acceptance＋SUBMIT command＋outbox commit**；各 phase 分開，拒絕外層 transaction。正式受理仍重驗 session／期限／報價／admission／PG gate 與原 Lua receipt。PG error 不觸發盲目 release／INCR；重送原 UUID/body，期限不延長。

原 intent PREPARED／HELD 尚 live 且同原 session、有有效入口旗標才可嘗試 resume；canRetrySameRequest 不是 gate／admission 成功保證。新 login 可讀本人原進度，但不能接管舊未受理 session。入口關閉、到期、ABORTING／REVIEW 不新受理；保留 PG 進度供 recovery／人工核對。ABORTED 代表 unused 原操作已核對結束，沒有 purchase。已受理則 state／期限／purchase 全取正式 PG 收據，由原 worker／取消／expiry 與 durable recovery 完成，不能 raw abandon。

## 前端與未知結果

有效旗標開啟時選預扣模式，仍保留舊 async／sync 比較；關閉時沿用原模式。暫時預扣／PROCESSING 都不顯示付款倒數，只有正式訂單成立才可付款。

沿用 `encore-reservation-pending-v1` sessionStorage，POST 前保存 owner／原 path／UUID／完整 body。此輪加入 purchase-attempts，舊訂單／購票／付款 pending 可繼續讀取。所有未知操作共用鎖，暫停新增購票、保票、取消、付款。重整後「核對原購票操作」先 GET 原 UUID：找到正式 purchase／ABORTED 才解鎖；canRetrySameRequest true 或本人 404 才 POST **同一 UUID/body**。404 本身不是撤回證據，旗標關閉後的新 POST 也不能把 pending 丟棄。401／403／409／429／5xx、連線失敗、缺字段或不一致 ACK 都保留命令。已驗證 GET 進度即使後續 resume POST 失敗也繼續顯示，不把原 HELD 證據當成未知正式 purchase。

PREPARED／HELD／ABORTING／REVIEW 保留 pending，沒有放棄按鈕或替換 UUID；需要 recovery 啟用或人工處置。未受理進度採手動核對，不自動持續 POST。不同會員不能核對或看原收據。sessionStorage 不保存 cookie／密碼；關閉整個分頁會失去此 client command，所以不是跨裝置持久操作清單；PG intent／原 UUID 仍存在。

## 驗證與限制

StockCheckoutIntegrationTest 使用真 PG／Redis，含完整 coordinator 六並發、Lua 後 PG progress rollback、acceptance rollback、同 UUID changed body／command collision、新 session、跨 owner、期限、entry off replay、早期 admission、REVIEW、外層 transaction 拒絕，以及 HTTP auth／CSRF／spoof／expected-owner guard。

`scripts/smoke_stock_checkout_browser.cjs` 對正式 JAR＋dist，用 operator-controlled 隔離 worker／Redis與內部 READY maintenance，分 setup、prepare、verify-worker、offline、flag-off、verify-aborted。私有 env／fixture／output 必須在 work/；acceptance constraint 故障只允許明確 `ticket-checkout-...-postgres-1` 拋棄式容器，禁止正式資料使用。verify-aborted另需work中的 `TICKET_UNUSED_FIXTURE`：獨立短期限unused原操作，JSON包含user（handle/password）、pending（owner/path/body）、url，禁止用新UUID替換原REVIEW；由真正background recovery完成終態。實測結果見本輪 HANDOFF 與 verification JSON。

Browser route abort／修改 ACK 是顧客端回覆遺失／malformed ACK 模擬，後端仍真 HTTP commit；PG CHECK 故障是提交前 rollback。沒有 PG TCP commit ACK drop／Redis TCP 成功回覆丟失／跨主機 HA／Cluster／壓測宣稱。manual REVIEW 處置與投影自動維護仍待做；真付款／出票另列後續。
