# 持久 Redis 預扣意圖（M4D2B1）

此內部 Spring 服務已由 M4D2B4 顧客 coordinator 呼叫，入口預設關閉，見 [STOCK_CHECKOUT](STOCK_CHECKOUT.md)；內部 MQ 受理與正式決定已接 B2，恢復排程已接 B3（下方連結）。`HELD` 只記錄 Redis 收據，**不代表 PostgreSQL 已保票，也沒有訂單／付款期限**。既有同步與非同步購票仍使用正式 PG 核心。本段不是完整跨系統 orphan 恢復。

## 已提交命令先於 Redis

V8 新增不可變 `stock_hold_intent` 與受狀態轉移保護的 `stock_hold_progress`，V1–V7 不變。`prepare(verifiedPrincipal, originalPlace)` 在獨立 PG transaction 中驗證 persistent customer/session，鎖 owner→event SHARE→ticket，再核對 published/sales、quote、READY epoch、限購及未解決 intent 數量。原 owner/UUID/body 先 replay，不因 gate 改變、重新登入或重建換命令。

意圖綁定原 UUID、本人、session version/hash、ticket、quote、source revision、generation、PG clock 與最多 60 秒的銷售期限（截斷 milliseconds）。claim fingerprint 綁定上述全部資料；Redis receipt 使用 PG 分配的 intent UUID，避免不同 owner 使用相同原 UUID 互相碰撞。原命令仍是 owner scoped。每 owner 最多五個 unresolved intent，同一 epoch 最多 4096 個 intent，正式訂單與未解決 intent 合計不能超過限購。

`prepare` 不呼叫 Redis。PG commit 回覆未知時重送原 UUID/body；已存 intent 不重分配 generation 或 deadline。既有 order/purchase command 與 intent 互斥；正式 API 的相同 UUID 不可繞過 intent 重扣，即使 intent 已 ABORTED 也保留去重歷史。

## 預扣與放棄

`apply(durableIntentId)` 只能由內部協調者使用，不信任 broker/body 的 owner、quote、quantity 或 claim。鎖 owner→event→ticket→progress 後先 LOOKUP：已存在 HELD 可記錄證據，不需要重新 HOLD，即使期限／session 已到期或 gate DIRTY。這只是恢復收據，不是補授權建立正式訂單。

只有原 intent PREPARED、收據尚無但 epoch key 完整、PG READY 與同 generation/revision、原 session 仍有效且銷售/期限/quote 通過，才執行新 HOLD。PG 仍未扣票。缺失 epoch、receipt 遺失、corrupt、Redis 未知或 gate 改變會標 PG DIRTY，保留原進度；不 INIT、不 INCR，不在未知時換 UUID。未知後若無收據且 gate DIRTY，必須走放棄核對，不能原地繼續新預扣。

| PG 進度 | 意義 | 允許的下一步 |
|---|---|---|
| PREPARED | 原身分與命令已提交，Redis 結果可能尚無或未知 | LOOKUP／有條件 HOLD、決定放棄 |
| HELD | 原 Lua HELD 收據已觀察並持久記錄 | 同收據重讀、決定放棄；尚無正式訂單 |
| ABORTING | 已提交 UNUSED_INTENT_ABANDONED，PG 門 DIRTY | 凍結舊 epoch、核對原收據；未知保留此狀態 |
| ABORTED | 已確認 RELEASED、凍結後 ABSENT 或 KEY_MISSING | 原 UUID 永久 replay，不再 HOLD |

`abandon(id)` 僅處理未接任何正式 purchase/order 的內部 intent。先鎖 owner/ticket、驗證沒有相同 owner/UUID 的正式命令／request／order，**提交 ABORTING 與 DIRTY 決定**；下一個獨立 transaction 才 `reconcileAbandonment`。

核對在相同 writer locks 下先 CLOSE Lua，該 epoch 不可重新 OPEN，再 LOOKUP 原不可變 claim。HELD→RELEASED；已 RELEASED 重試不再次增加票額；凍結後沒有收據可記 ABSENT。整個 key 缺失時記 KEY_MISSING，不重 seed 原 epoch。官方 apply 讀到 ABORTING/ABORTED 不送 HOLD，維運 complete 只要該 epoch 曾有 intent 就不能再次 INIT。

Redis 失聯／corrupt／CONFLICT／異常 COMMITTED 不釋放、不標完成，維持 ABORTING 並阻擋新 generation。B1 沒有合法 COMMITTED 路徑，遇到它必須人工核對，不能當未使用票額補回。期限經過只表示不能新 HOLD；不等於放棄決定，也不會自動釋放。

## 重建與故障邊界

`StockProjectionService.begin` 在 ticket lock 下要求該票種所有持久 intent 都 ABORTED，才分配新 generation；原 maintenance UUID replay 不重新分配。`complete` 禁止任何曾有 intent 的同 epoch 重新準備。完整放棄後，新 epoch 從正式 PG free stock 建立空投影，舊 receipt 不刪，原 UUID 不可跨 epoch 再扣。

| 故障窗口 | 持久來源與恢復 |
|---|---|
| intent INSERT/commit 失敗 | 尚未送 Lua，整筆 identity/progress 回滾 |
| Redis HOLD 成功、PG HELD 回滾 | PREPARED 保留；新程序 LOOKUP 原收據，僅記錄 HELD，不重扣 |
| ABORTING 提交失敗 | 尚未 CLOSE/RELEASE，Redis 原狀不變 |
| 已提交 ABORTING、Redis 未知 | DIRTY + ABORTING；原 id 核對，阻止重建／晚到官方 apply |
| Redis RELEASE 成功、PG ABORTED 回滾 | 已提交 ABORTING 保留；重讀 RELEASED 完成 PG，不再補票 |
| 已有 intent 的 epoch key 遺失 | 不 re-seed；先持久放棄核對，再新 PG epoch |

上述保證限於遵守本協調者的官方 writer。直接使用內部 ledger、手動改 PG/Redis、未登記 receipt、舊 epoch／receipt 清理、跨節點 failover 與所有異常 ledger 修復尚未完成。沒有跨 Redis/PG 原子 commit、exactly-once 或 HA 宣稱。

網路呼叫仍在短 ticket lock 中（Redis connect/command 各 500ms，PG transaction timeout 10 秒），放棄一筆會凍結整個 epoch；其餘 intent 須逐筆核對。這是保守一致性底座，並非高吞吐實作。正式 PG 庫存變動仍會標 DIRTY，尚無增量 journal apply。所有 phase 禁止外層未提交 transaction，不能以 REQUIRES_NEW 隱藏死鎖／未保存命令。

## 驗證與下一步

24 個新增真 PG/Redis 整合測試涵蓋 identity 先提交、原 UUID 並發、跨 owner ledger id、限購、namespace 衝突、quote/session、HOLD/RELEASE 成功後真 PG constraint 回滾、期限不補票、放棄／apply 競爭、重建禁止、immutable evidence、key loss／corrupt／異常 COMMITTED。

正式 JAR 多次獨立程序退出／啟動、實際 Redis container 停機／同 volume 重啟、PG ACK 故障與原命令恢復，以及 V7→V8 legacy row preservation 結果見 HANDOFF 與 `verification/stock-hold-m4d2b1-2026-10-10.json`。`scripts/verify_stock_hold_invariants.sql` 只讀核對 9 項 PG 條件，不證明 Redis 全台帳一致。

下一個 M4D2B2 必須建立明確 intent→purchase/outbox→order 與 durable COMMIT 決定的鏈結，把未使用放棄與已受理／已保票決定區分；原 UUID 先讀 PG，worker 最後仍核對正式容量／限購，取消與晚到 worker 共用鎖裁定。不能移除本段 UUID guard 後直接接 endpoint，也不能把 HELD 直接映射為待付款。完成 Redis→PG 受理 rollback／commit ACK 遺失、worker commit ACK 遺失、取消／到期、active HELD 跨世代核對後，才能啟用顧客預扣。

M4D2B2 已新增 V9 immutable acceptance／decision／ack，內部 HELD 受理、正式 worker／取消／expiry 的 PG 決定與獨立 Redis 核對完成；B1 progress HELD 保留作歷史，已受理 intent 以正式 decision／ack 判斷是否解決。詳見 [STOCK_PURCHASE_DECISIONS](STOCK_PURCHASE_DECISIONS.md)。M4D2B3 已新增 V10 durable recovery queue／租約／有限 retry／自動排程與程序重啟補做，詳見 [STOCK_RECOVERY](STOCK_RECOVERY.md)。M4D2B4 已接 verified 顧客預扣 coordinator、原 UUID PG-only 進度 GET、預設關閉旗標與未知操作 UI，見 [STOCK_CHECKOUT](STOCK_CHECKOUT.md)。M4D2B5 已接員工維護頁與受權限保護的原 rebuild coordinator／phase audit，見 [STOCK_MAINTENANCE](STOCK_MAINTENANCE.md)；仍沒有自動重建或持續吞吐宣稱。
