# 售票架構與重要決策

更新：2026-10-12。最初2026-10-08設計保存在[歷史架構](verification/architecture-before-m6d-2026-10-12.md)，其中未來式不代表目前未實作。現況見[FEATURE_STATUS](FEATURE_STATUS.md)。

## 目前組成與責任

Vue3／TypeScript／Vite；Java21／SpringBoot3.5.16模組化單體，使用Spring JDBC而非JPA。PostgreSQL／Flyway V1–V24是正式票額、限購、訂單、命令、session與不可變audit来源；Redis單節點承擔等候室、限流、Lua預扣；RabbitMQ單broker quorum queues傳送購票工作。全部免費自架，付款mock。

同一production JAR按角色啟動：api對外HTTP／migration，不跑業務背景排程或consumer；worker非web，不做Flyway／bootstrap，承接publisher／consumer／expiry／有限recovery。無profile的EMBEDDED版本適合簡單loopback展示，可同程序執行前述服務；不能把它叫雙API／雙Worker。

Nginx提供dist並代理雙API。PG／Redis／MQ仍各單節點，不能把容器restart與單broker quorum當HA。尚未接SeaweedFS活動海報、售票通知或Prometheus／Grafana；監控是原生readonly頁與心跳。

## 真實流程

1. 顧客cookie／CSRF驗證；可選每票種等候室短效資格，不等於保票。
2. 同步直接由PG交易保票；非同步由PG保存purchase＋outbox返回202／PROCESSING。
3. Lua預扣是另個預設關閉的非同步入口：持久intent／generation與原UUID核對後才送正式purchase。Redis→PG故障窗口須靠持久證據恢復，outbox不能解決這個窗口。
4. Outbox租約publisher在交易外發送持久MQ消息並核對confirm／return；未知保留原event與有限重試。
5. Worker以原event去重，共用正式PG限購／容量／訂單交易；commit後ACK。只有RESERVED／正式訂單成立才開始付款期限，PROCESSING沒有付款倒數。
6. 顧客建立並手動核對mock結果；PAID才可明確領取整批原quantity電子票券。**出票目前不是MQ自動事件流水線。**
7. QR／圖片／相機只填原票碼；MANAGER指定場次、核對身份／付款／DB時間窗後明確確認入場，一次terminal效果；未用票可撤銷，不退款／補票。

## 一致性與未知回覆

可售=capacity-held-sold，由PG CHECK／條件更新／唯一鍵／一致鎖順序裁定。保票→PAID／CANCELLED／EXPIRED；晚到成功REVIEW，不復活原票額。身分只取驗證principal；權限／版本／audit與業務同交易，owner不取client body。

Redis Lua短且有界，單key ledger有原request、quantity、deadline、generation及receipt，不裸DECR/INCR。Lua錯誤不提供SQL rollback，TTL不是補票；generation凍結不可重開，未知不盲補。每個正式stock revision使投影DIRTY，明確新epoch／有限返還與manual REVIEW受原證據及固定budget約束。M6B8/12後DIRTY仍限制接續，通用continuous incremental writer／授權輪替未完成；C1/C3是test-only實驗。

MQ至少一次，不承諾exactly-once。未知HTTP回覆保留原UUID／body，先核對；改內容拒絕，原UNKNOWN不清pending。票券／窗口等永久fence確保晚到原操作拒絕；停止不撤回已成功效果。

顧客和員工独立PG持久cookie／CSRF，MANAGER／VIEWER及current角色／sessionVersion重驗，不sticky session。現有等候順序／資格保存在Redis，不是PG durable queue；same-AOF恢復不代表failover公平。

## 部署、監控、備份邊界

[Nginx契約](DEPLOYMENT.md)：上游DNS啟動／reload解析，節點重建改IP後須明確reload；最初502／504允許有界收斂，已送POST不自動重放。readiness只證明PG SELECT1，心跳不證明每個job健康，MQ passive ready counts不包含所有unacked，PG／Redis／MQ探測非原子快照。

[M6C備份](BACKUP_RESTORE.md)：V24 ticket_demo有界offline PG custom dump，空目標single-transaction restore，target-bound原published pending重送与去重；來源維持停用，目標新流量disabled，空RedisKEY_MISSING保留DIRTY，不直接放流。無Redis／MQ／物件volume／PITR／跨版本及非空sequence實測。

## 後續設計而非已交付

持續預扣協調、Redis3Primary＋3Replica的slot／replication／failover核對、多broker quorum与跨主機故障、HTTPS／DB最小權限、正式通知／海報／完整IAM／退款／真provider。熱key不因Cluster變三份，replica不是備份、WAIT不保證零遺失。容量須用warmup／重複／長時間、orders/s與acceptance/s分開及不變條件實測，不先寫十萬併發。

分階段保留原契約與immutable migration；每個里程碑測試/build、HANDOFF、commit/push main，当前展示範圍與後續優先序見[ROADMAP](ROADMAP.md)。
