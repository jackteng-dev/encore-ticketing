# Encore · Ticket Platform

獨立售票展示作品：Vue 3／TypeScript、Java 21／Spring Boot 3.5.16、Spring JDBC、PostgreSQL／Flyway、Redis Lua、免費自架 RabbitMQ。顧客可排隊、送出請求、核對正式訂單、模擬付款、領取電子票券；員工管理活動、場次時間窗、驗票、庫存恢復與基本監控。**付款全部是免費模擬，沒有真收款。**

受控 Cloud 展示階段已於M6D收尾結案，正式公開部署、手機與持續高吞吐另驗收。預扣仍受 DIRTY gate 限制：M6B 的12張小批量只有8張 RESERVED，餘4張不能接續；不能宣稱持續大量搶票、最大容量或 HA。

## 從這裡開始

- [展示操作手冊](docs/SHOWCASE.md)：全新隔離啟動、必要旗標、活動→排隊→非同步購票→mock→領票→驗票、停止及故障展示。
- [現況與證據](docs/FEATURE_STATUS.md)：已完成、部分完成、尚未完成、無法確認，區分本輪与歷史。
- [本地驗收清單](docs/CLOUD_LOCAL_FOLLOWUP.md)：HTTPS、安全、手機、容量及全服務復原。
- [架構](docs/ARCHITECTURE.md)、[里程碑](docs/ROADMAP.md)、[最新交接](docs/HANDOFF.md)、[工作規範](AGENTS.md)。

## 工程重點

PostgreSQL 是正式票額、限購、訂單及不可變稽核的來源。等候室只控制入口；Lua 不提供跨 PG／MQ 原子交易。持久請求＋outbox＋publisher confirms＋commit 後 ACK 以原 UUID／body 與 PG 去重處理至少一次投遞。202／PROCESSING 不等於已保票。

同一 JAR 支援 api／nonweb worker profiles。Nginx／雙 API／雙 Worker 有單機隔離容器故障證據，節點重建須明確 reload。PG／Redis／RabbitMQ 仍單節點，MQ 使用單 broker quorum queues，未驗多 broker HA。票券由已付款顧客明確領取；掃描只填碼，員工核對後才確認入場。

## 啟動與驗證

需 JDK21、Maven3.9、Node>=22.12、Python3、Docker Compose v2；Linux/bash 或 WSL2。完整命令見 SHOWCASE，不要漏開購票／付款／票券旗標。env、合成帳號、cookie、票碼、日誌與備份只留 ignored work/。

每個里程碑執行 backend mvn package（不跳測試，使用自己的拋棄式 PG／Redis／MQ）及 frontend npm run build。最新629項／30 suites，已發布 V1–V24 migration 不改 checksum。歷史 smoke 腳本各有旗標／fixture phase 要求，不對同一 DB 盲跑全部；test context 限制排程，真實排程另用 runtime 證據核對。

## 契約索引

| 範圍 | 文件 |
|---|---|
| 活動、身分、保票、付款 | [Catalog](docs/CATALOG_API.md)、[Customer](docs/CUSTOMER_API.md)、[Reservation](docs/RESERVATION_API.md)、[Payment](docs/PAYMENT_API.md) |
| 排隊、非同步、MQ | [Waiting room](docs/WAITING_ROOM_API.md)、[Purchase](docs/PURCHASE_API.md)、[Checkout](docs/ASYNC_CHECKOUT.md)、[Delivery](docs/MQ_DELIVERY.md) |
| 預扣、維護、有限返還 | [Stock checkout](docs/STOCK_CHECKOUT.md)、[Maintenance](docs/STOCK_MAINTENANCE.md)、[Recovery](docs/STOCK_RECOVERY.md)、[Review](docs/STOCK_REVIEW.md)、[Discovery](docs/STOCK_RETURN_DISCOVERY.md)、[UI](docs/STOCK_RETURN_DISCOVERY_UI.md) |
| 電子票券、QR、窗口 | [Tickets](docs/TICKETS_API.md)、[原請求恢復](docs/TICKET_REQUEST_RECOVERY.md)、[QR](docs/TICKET_QR.md)、[Windows](docs/ADMISSION_WINDOWS.md) |
| 多實例、監控、負載、備份 | [Deployment](docs/DEPLOYMENT.md)、[Multi worker](docs/MULTI_WORKER.md)、[Monitoring](docs/MONITORING.md)、[k6](docs/LOAD_TESTING.md)、[Backup](docs/BACKUP_RESTORE.md) |

階段契約「此階段未接」描述當時範圍；目前狀態以 FEATURE_STATUS／最新 HANDOFF 為準，不累加歷史測試數。
