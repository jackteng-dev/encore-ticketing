# 持久預扣恢復（M4D2B3）

內部 `StockRecoveryWorker` 已接 PG durable queue、租約、有限重試與重啟補做。M4D2B4 已接預設關閉的顧客預扣 endpoint／原 UUID進度／UI，見 [STOCK_CHECKOUT](STOCK_CHECKOUT.md)；舊PG同步／非同步比較入口保留。Redis HELD 不等於正式保票。

## 任務來源與狀態

V10 新增 `stock_recovery_job` 與 immutable `stock_recovery_claim`，V1–V9 bytes 不變。

| kind | 同交易建立時機 | 執行依據 |
|---|---|---|
| UNUSED | stock_hold_intent INSERT | 原 PG deadline；只有未受理且沒有正式命令的 intent 才決定 ABORTING |
| SETTLEMENT | stock_hold_decision INSERT | 不可變正式 COMMIT／RELEASE 決定；不根據 TTL 推測 |

任務插入失敗會使原 intent 或正式 worker transaction 整筆回滾；consumer 不 ACK 成功。MQ ACK 後即使程序消失，核對工作仍在 PG，consumer 不等 Redis。V9→V10 只 backfill 尚未 ABORTED 的未受理 intent（ABORTING 立即到期），以及沒有 ack 的正式 decision；舊 acceptance／decision／ack／progress 不改資料。舊 accepted PROCESSING 依既有 PurchaseExpiry／worker 產生正式決定，不能 raw abandon。

WAITING→LEASED→DONE／WAITING／REVIEW。一次 job 固定六次嘗試；claim／程序死亡也耗一次，重啟不重設。並發用 PG `FOR UPDATE SKIP LOCKED`，每次新 token、attempt、lease deadline 與 immutable claim history 同交易保存。逾期租約可接手；第六次租約過期轉 REVIEW，沒有第七次。DONE 必須有正式 ack，或 UNUSED 已 ABORTED／accepted 的持久證據；identity、budget、claim audit 和 job 終態受 SQL guard 保護。

## 執行與租約邊界

每個 domain phase 先 owner／ticket／purchase 或 progress locks，再檢查並鎖住 job 的 token／attempt／PG lease deadline。Redis 操作後、保存 ack／ABORTED 前再檢查一次。舊 worker 無法記錄新嘗試的结果；job receipt 更新也要求原 token／attempt／租約仍有效。

沒有把整個任務包在外層 transaction。DIRTY／ABORTING 仍先獨立提交，再凍結舊 Lua epoch、讀原 claim、按原決定 COMMIT／RELEASE。租約不能撤回已送出的 Redis 指令；若操作成功後租約失效或 PG rollback，原不可變決定、凍結 ledger 與冪等終態供下一次核對。不是跨 Redis／PG exactly-once。

UNUSED 執行時，在與受理相同的 owner／ticket／progress locks 下重驗 acceptance、原命令與 PG clock。已 accepted 只記任務 ACCEPTED 完成，不碰 Redis；仍 live 不能決定 ABORTING。已持久 ABORTING 的首次任務不必等原 deadline，立即可領取續核對；其後失敗仍遵守 backoff。未受理的 PREPARED／HELD 到期才保存放棄決定；沒有根據 Redis TTL 或掃描時的過時資訊直接補票。

## 重試與維運

REDIS_UNKNOWN／PG_FAILURE／INTERNAL_FAILURE 有限重試：第一次失敗後等1秒，接著2、4、8、16秒；總共六次，最後 REVIEW。lease takeover 須等原租約到期，poll／程序啟動／鎖等待也影響實際時間，沒有固定恢復 SLA。

corrupt、反向 terminal receipt、generation／正式證據衝突直接 REVIEW，保持未解決 gate，不能自動 INCR／逆轉／重seed。只保存安全代碼，不寫原始 exception、token 或 body。REVIEW 為不可變嘗試結果，Redis 恢復不重開 budget；人工核對原 PG／ledger 後可由內部 domain service 完成證據，B6已接另存操作audit的 [原決定人工核對](STOCK_REVIEW.md)，不能直接改 job 或正式決定。

設定皆為環境變數（重啟程序生效）：

| 設定 | 預設 | 範圍 |
|---|---|---|
| TICKET_STOCK_RECOVERY_ENABLED | false | 是否啟動內部排程；任務即使關閉仍建立 |
| TICKET_STOCK_RECOVERY_LEASE_SECONDS | 30 | 5–120秒 |
| TICKET_STOCK_RECOVERY_BATCH | 10 | 每輪1–100筆，逐筆執行 |
| TICKET_STOCK_RECOVERY_POLL_MS | 1000 | Spring fixed delay |

有 accepted PROCESSING 時保留 `TICKET_EXPIRY_ENABLED=true`（預設），讓原期限產生正式 EXPIRED／RELEASE。recovery flag 不開啟顧客预扣，也不替代 purchase expiry。多程序共用 durable queue 可競爭領取，本輪沒有高吞吐／跨主機容錯宣稱。

以自有隔離 DB 執行只讀 `scripts/stock_recovery_status.sql`：due、expired leases、oldest due age、unacked decisions、ABORTING、未解決 REVIEW。REVIEW history 會保留，不當作已自動解決；具體完整性用 `verify_stock_recovery_invariants.sql` 的8項零違規條件。這不是公開管理 endpoint／Prometheus dashboard。

## 驗證與後續

本輪真 PG／Redis／RabbitMQ 測試、正式 JAR 的實際程序中斷／自動排程／Redis 停機重啟與 migration-only 升級證據見 HANDOFF。SQL CHECK fault 是 precommit rollback；lease callback 故障測試不冒充 Redis／PG TCP ACK loss。PG commit ACK 網路丟失、未登記 receipt 全台帳核對、epoch GC／增量 revision apply、Cluster／HA／負載仍待做。

verified 顧客 coordinator 已接，見 [STOCK_CHECKOUT](STOCK_CHECKOUT.md)。不能用外層 transaction 包各 phase，HELD 不顯示成待付款。下一步先完成 projection maintenance 的操作流程與故障邊界；全部 intent 解決後的新 epoch 仍由正式 PG 快照重建。

M4D2B5 員工 [STOCK_MAINTENANCE](STOCK_MAINTENANCE.md) 可以安全列出未解決 intent／REVIEW 與原 PG 決定，並在全部原 intent 已解決後明確建立新 epoch。它不處置 REVIEW、不重設 budget、不強制 ack；原決定人工處置已接B6，詳見 [STOCK_REVIEW](STOCK_REVIEW.md)。
