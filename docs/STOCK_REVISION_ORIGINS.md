# 庫存 revision 的正式來源證據（M4D2C2）

PostgreSQL 仍是正式庫存來源。V13 在原業務交易中記錄每個新 stock revision 的前後票種、原始業務事件和不可變 audit；不依時間、數量或最新訂單推測已提交歷史的因果。這是增量同步的前置證據，尚未實作 Redis incremental consume、cursor 或 READY publication。

## 資料與交易邊界

- `stock_origin_boundary`：升級當下每個既有票種的 source revision；一次建立後封存。邊界以前的 journal 保持原樣，不回填來源。V13 後建立的票種沒有歷史邊界。
- `stock_revision_context`：原 V7 writer 產生 journal 後，同交易保存 OLD／NEW 的完整票種 JSON、當時 generation、精確 from／to revision、PG transaction ID。新增是 `NULL → 1`；修改只能 `n → n+1`。transaction ID 是交易證據，不能代替業務 UUID；與 xmin 核對使用 PG 原生 xid8 → xid 轉型，避免文字轉型在 32 位元交易邊界溢位。
- `stock_revision_origin`：每個新 revision 唯一分類及原事件 FK；業務事件插入時立即取得該交易的 context，而不是離線掃描後歸因。
- `stock_origin_audit`：與 origin 同交易插入且不可修改。context、origin、audit 或延後的正式證據驗證失敗，原庫存、訂單、命令、付款事件及 worker 結果共同 rollback。

原 Java Service 保留驗證身分、報價、版本、權限和鎖的責任。trigger 不新增顧客／員工 API，也不採用訊息中的 owner。已發布 V1–V12 bytes 不變；V7 的 journal writer 不替換。來源証據由 V13 trigger 自動與原事件連結，不能由 HTTP 呼叫者提供。

## 分類

| kind | 原始證據 | 精確效果 |
| --- | --- | --- |
| CATALOG_CREATE | 原 `catalog_command` UUID／resource／action／result | 新票種，held／sold 為 0，result 與新票種名稱、價格、票額、限購、版本及 available 一致 |
| CATALOG_UPDATE | 原 `catalog_command` | version +1；記錄名稱、價格、capacity、限購的前後值；held／sold／session identity 不變 |
| ORDER_HOLD | 原 `order_inventory_event(order_id,HOLD)` | held 增加原訂單 quantity；售出數及票種條件不變 |
| ORDER_RELEASE | 原 `order_inventory_event(order_id,RELEASE)` | 原取消／到期事件的 held 減少；售出數及票種條件不變 |
| PAYMENT | 原 `payment_inventory_event.event_id` 及同交易 SUCCEEDED `payment_event` | 原訂單 quantity 從 held 轉 sold，available 不變 |
| UNATTRIBUTED | 沒有原業務事件 | 直接 SQL 的 revision 變動在 commit 時明確標記為缺來源，不冒充正式因果 |

原事件與 context 的庫存 before／after 必須完全相符。catalog result 要與新票種 snapshot 相符；不能只比數量。付款 DECLINED／REVIEW 不會偽造 PAYMENT origin；逾期付款觸發原到期釋放時，只記錄 ORDER_RELEASE。

只有原 accepted Redis claim 才記錄 `intent_id`：對應原 acceptance／purchase／customer／request／ticket／quantity，且正式 decision 必須是原訂單的 COMMIT。worker 先寫 HOLD event、後寫 decision，因此在 transaction commit 前延後驗證，不能把兩個獨立交易拼成來源。後續取消或付款仍引用原 COMMIT，不能將它翻成 Redis RELEASE；它們是新的正式庫存事件。後續事件的 context generation 可不同於原 claim generation。

## 不可變與缺證據處理

新 context／origin／audit 與切換邊界不可 UPDATE／DELETE；切換邊界不允許事後新增。已連結的新 order inventory event、payment inventory event、成功 payment event、catalog command 不可再改。訂單的 customer／request／ticket／quantity／price／total identity 不可改，但合法狀態／付款／版本轉換保留。沒有新 origin 的舊事件仍保留原行為；不替舊資料虛構保證。

若直接 SQL 改票種價格、名稱、限購或 session 而不遞增版本，拒絕交易，避免 quote 變動繞過原 stock journal。直接 SQL 產生庫存／版本變動則保留 V7 DIRTY 行為並標 UNATTRIBUTED。SQL no-op 不新增 revision。

未來同步器必須 fence 邊界以前缺來源的 history 與 UNATTRIBUTED，經明確維護／新 epoch 建立已核對起點；本輪沒有自動解 fence、reseed、INCR、新 UUID 重送或放行持續預扣。UNATTRIBUTED 數量是操作訊號，不是「零違規即能 READY」。

## 驗證與限制

`StockOriginIntegrationTest` 使用真隔離 PG／Redis，覆蓋原同步／worker HOLD、取消／expiry、付款、catalog、原 UUID 重送與併發；另測原生交易 ID 跨 32 位元邊界轉型（不是實際跑完一個 PG wraparound）。真 CHECK fault 驗證 context／origin audit／延後 UNATTRIBUTED audit 的共同 rollback，再用原 body 重試。`scripts/verify_stock_origin_invariants.sql` 是只讀 PG 檢查，另報缺來源數量；不是 Redis 台帳或來源權限的全域審計。

V12 實際 JAR 建立原資料後，V13 升級先比對每個原表每列的 canonical JSON，確認只新增 boundary、其餘三表空白且不 backfill，再測試既有 accepted 訂單的新 RELEASE。實測結果見 [本輪證據](verification/stock-origins-m4d2c2-2026-10-10.json) 與 [HANDOFF](HANDOFF.md)。

單 PG 同交易測試不等於 TCP commit ACK drop、跨主機 HA 或壓測；單 Redis／MQ 仍有原限制。C1 的 sidecar／cursor 僅 test-only，C2 沒有把它直接搬入正式服務。下一步再分階段設計多 revision 與 active holds 的 consume／版本化台帳／原命令恢復。

C3 的 [有界多 revision 實驗](STOCK_MULTI_REVISION_EXPERIMENT.md) 已使用本輪 immutable 來源，核對固定 head 下的原 COMMIT／正式 RELEASE／PAYMENT 與原 UUID 恢復。原 Redis 台帳唯讀；production consumer、active-hold conservation 與 READY 仍待做。
