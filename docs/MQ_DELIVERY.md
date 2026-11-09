# M4B RabbitMQ 傳遞與恢復

免費自架 RabbitMQ，與原 PG／Redis 使用同一個隔離 Compose project。M4B完成後端publisher／worker；M4C已接非同步checkout、本人歷史與原操作恢復，見ASYNC_CHECKOUT。PROCESSING／202 只是持久受理，RabbitMQ confirm 只是 broker 收到，只有 PG RESERVED／orderId 才是保票成功。

## 隔離啟動

```bash
python scripts/init_demo.py --loopback-http
python scripts/init_mq.py
docker compose --env-file work/runtime.env -f infra/compose.yml -f infra/compose.mq.yml up -d --wait
set -a
. work/runtime.env
set +a
# 明確開放新受理；其餘讀取、取消與處理既有請求不靠這兩個旗標。
export TICKET_ORDERS_ENABLED=true TICKET_PURCHASES_ENABLED=true
export TICKET_DELIVERY_ENABLED=true TICKET_PUBLISHER_ENABLED=true TICKET_CONSUMER_ENABLED=false
java -jar backend/target/ticket-platform-0.1.0.jar
```

另一終端 export 相同 private env，使用同一 JAR／同一 DB／同一 vhost／namespace：

```bash
export TICKET_DELIVERY_ENABLED=true TICKET_PUBLISHER_ENABLED=false TICKET_CONSUMER_ENABLED=true
export SPRING_MAIN_WEB_APPLICATION_TYPE=none
export TICKET_DEMO_BOOTSTRAP=false TICKET_ORDERS_ENABLED=false TICKET_PURCHASES_ENABLED=false
java -jar backend/target/ticket-platform-0.1.0.jar
```

worker 不監聽 HTTP；仍共用業務服務與 Flyway checksum 驗證，不複製保票邏輯。API 可 publisher-only，worker 可 consumer-only，或單 JVM 同時兩者。預設 delivery／publisher／consumer 三個旗標全 false；只有 delivery=true 才宣告 topology／啟用 MQ beans。API 未處理的既有請求即使關閉新受理仍能查詢／取消／到期，worker 亦可處理既有 intent。

RabbitMQ 只綁 loopback25672，沒有公開管理 UI；user/password/vhost 留 private work env，不能放 VITE_*。init_mq.py 只補缺項，不替換原 DB／Redis／員工／MQ 帳密；反覆執行是 no-op。停止時只清理自己的 Compose project；-v 僅限明確拋棄式驗收，正式資料先備份。

## Outbox publisher

V6 新增 outbox_publication；V1–V5 bytes 不改。既有 V5 intent backfill 一筆 publication；新 intent AFTER INSERT trigger 在同個受理交易產生 publication。不可變 purchase_outbox 仍只有 event／purchase 參照、type/schema/time，publication 另保存 attempts、next_attempt_at、lease_token／lease_until、published_at／last_error。

短 PG 交易用 FOR UPDATE SKIP LOCKED 取得一筆未 published 且已到重试時間的租約；每輪最多10筆，逐筆 claim，再於 DB 交易外等待 confirm。預設租約15秒、confirm2秒，配置要求至少三倍 confirm timeout。以 random lease token 防旧 publisher 覆寫新 claim；過期 token 不能標記 published 或 failed。程序中斷未記 receipt 時，原 lease 到期後重送**相同 eventId**。

mandatory JSON persistent message：`{eventId,purchaseId,schemaVersion:1}`，AMQP messageId=eventId。沒有 cookie、session hash、owner、quantity、price 或付款資料。只有 broker basic.ack 且沒有 return 才寫 published_at；RETURNED、broker NACK、timeout／中斷／channel-close／失聯未知結果清租約並退避1..30秒。Spring 在 channel 關閉時合成的 negative confirm 帶 reason，歸 UNKNOWN，不能當真 broker NACK。確認成功但 PG receipt 遺失也仍原 ID 重送；不宣稱跨 PG／MQ exactly-once。

## Consumer 與重試

namespace 需 encore-*，main／retry／dead 各有 durable direct exchange、durable quorum queue，persistent messages。各 queue 上限10000、reject-publish；main／retry 使用 at-least-once dead-letter strategy，避免 classic DLX 未確認轉送窗口。現在是**單 RabbitMQ 節點的一個 quorum 成員**，沒有跨主機 HA 或磁碟毀損恢復證據。

每個 worker concurrency=1、prefetch 預設4（1..16）。手動 ACK；strict JSON 最大1024 bytes、拒絕 duplicate key／未知欄位／非 schema1／非 canonical UUID／messageId 不符及非法 retry header。有效 ID 還須與 PG purchase_outbox 的 event/purchase/type/schema 綁定相等；無效／不存在去 dead-letter，不把 false 處理結果都當成功重送。

PurchaseService.deliver 是 Spring PG 交易入口，回傳 PROCESSED／DUPLICATE／INVALID。原身份／期限／取消與正式票額由 PG 共用保票核心裁定；RESERVED＋order／PLACE／HOLD 同交易。**service proxy 提交完成後才 basicAck**；commit 前 DB error 完整回滾，原请求 PROCESSING，不偽造 REJECTED。commit 後 channel 關閉／ACK 遺失，原 ID 再投遞只確認既有終止結果，不新下單。

DB／系統錯誤最多3次額外重試（初次＋3），先在 TX 外 confirmed persistent publish 到 retry exchange，再 ACK 原消息；retry queue TTL 預設5000ms 再 confirmed DLX 回主佇列。達上限轉 durable dead queue 並 confirm 後 ACK 原消息；請求留 PROCESSING 供人工核對／自然期限 EXPIRED。retry／dead publish 的 return、NACK 或未知結果**不 ACK 原消息**，關 channel，容器以5秒 recovery interval 恢復原投遞。retry 成功但 ACK 未知可能產生兩份消息，業務結果仍按原 PG request 去重。超限不等於永遠每 event 僅投遞4次；未知 ACK、broker redelivery 和 quorum delivery limit 都可能影響實際次數。

## 維運核對

不要清空 outbox、盲增 Redis 或把 DLQ 消息改 event ID。檢查 PG purchase/status/deadline/order/ledger、publication attempts/error/lease/published_at，以及 own main/retry/dead depth/age。published_at 不等於 worker 處理成功；terminal receipt 亦不是即時訂單狀態。

本輪沒有公開 re-drive 按鈕。DLQ 需先核對失敗原因、修正 DB／拓撲，確認仍 PROCESSING 且原 deadline 未到，再由持有私有 broker 權限的維運人員以原 eventId/purchaseId/schema 重新發送，retry header=0。已過期／取消不復活；已 RESERVED 必須讀原訂單。不要直接修 purchase 或 publication 為成功。一般人不能取得 MQ credentials；retry header 是內部 transport 計數，不能拿來授權顧客。

驗證程式：DeliveryIntegrationTest 真 PG／RabbitMQ 的 claim／confirm／return／broker NACK／租約到期／重送／commit後ACK遺失／DB回滾／retry／DLQ；scripts/smoke_delivery.py 配合運行正式 JAR 與維運控制 API／worker／broker 停啟。verify_delivery_invariants.sql 加原 purchase/payment invariants。停止程序前保存私有原 UUID/body/session fixture；不修改 deadline 假裝停機，原始秘密不進 git。實際結果見 HANDOFF／verification。

## 後續

M4C已完成202本人輪詢、unknown POST原UUID/body、取消競爭、登入切換与orderId導向本人訂單；不提前付款倒數。M4D1獨立Lua台帳／generation原語已完成但尚未接worker，下一步M4D2持久PG協調／孤兒核對與接入，再做出票／驗票、shared staff session、多API／worker、三節點MQ、Redis Cluster、監控／壓測／備份／正式本地部署。仍無真付款／真通知。

V10 在 linked purchase 正式 decision 的原 PG transaction 中建立 SETTLEMENT job，插入失敗同筆回滾、consumer 不 ACK 成功；ACK 後的 Redis 工作由預設關閉的 durable recovery worker 處理，見 [STOCK_RECOVERY](STOCK_RECOVERY.md)。
