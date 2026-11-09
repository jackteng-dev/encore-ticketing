# 目前功能與證據

更新：2026-10-12。範圍為受控展示，不是真收款／公開營運。本輪見 [M6D](verification/showcase-m6d-2026-10-12.md)／[HANDOFF](HANDOFF.md)。歷史報告只證明當時版本／環境，不累加測試數、不當最新版全套重驗。

## 已完成且有證據（限定範圍）

| 功能 | 契約 | 證據與邊界 |
|---|---|---|
| 活動／場次／票種、發布／封存、版本／原命令／audit | [Catalog](CATALOG_API.md) | 最新完整真PG tests；發布後條件鎖定，無刪稽核 |
| 獨立顧客／員工cookie＋CSRF、PG sessions／撤銷、MANAGER／VIEWER | [Staff sessions](SHARED_STAFF_SESSIONS.md) | 最新完整tests、跨API歷史runtime詳見契約；不是完整IAM |
| PG票額／限購／保票／取消／到期、mock／REVIEW | [Reservation](RESERVATION_API.md)／[Payment](PAYMENT_API.md) | 最新交易tests、[M6B](verification/load-m6b-2026-10-12.md)／[M6C](verification/backup-m6c-2026-10-12.md)；不真收款 |
| 等候室、會員限流、短效資格／原UUID | [Waiting room](WAITING_ROOM_API.md) | 真Redis tests／M6B原queue replay；單Redis，不保證有票 |
| 持久purchase＋outbox＋confirmed MQ／重試／DLQ／commit後ACK／去重 | [Delivery](MQ_DELIVERY.md) | [雙Worker](verification/multi-worker-m5c-2026-10-12.json)、M6B/C；202非保票 |
| Lua ledger、PG generation／intent／decision／有限恢復／原命令REVIEW | [Stock checkout](STOCK_CHECKOUT.md)／[Recovery](STOCK_RECOVERY.md) | 真PG／Redis tests、M6B/C；安全核對非連續吞吐 |
| 固定有限來源授權／正式返還／audit／UI／停止 | [Discovery](STOCK_RETURN_DISCOVERY.md)／[UI](STOCK_RETURN_DISCOVERY_UI.md) | [核心](verification/return-discovery-core-m5d-2026-10-12.json)、[故障](verification/return-discovery-runtime-m5d-2026-10-12.json)、[UI](verification/return-discovery-ui-m5e-2026-10-12.json)；原管理員／固定budget，不自動輪替 |
| 明確領票、QR／圖片或相機填碼、一次驗票／撤銷、場次窗口、原命令停止 | [Tickets](TICKETS_API.md)／[QR](TICKET_QR.md)／[Windows](ADMISSION_WINDOWS.md) | 真PG tests與契約runtime；Cloud相機合成stream，手機待驗 |
| 同JAR API／nonweb Worker、Nginx／雙API／雙Worker | [Deployment](DEPLOYMENT.md)／[Multi worker](MULTI_WORKER.md) | [M5B16組＋browser5](verification/deployment-m5b-2026-10-12.json)、M5C6組；V22容器證據，IP換後reload，非HA |
| 基本readonly心跳／backlog／gate／REVIEW／Redis ping／MQ passive counts | [Monitoring](MONITORING.md) | [M6A15組](verification/monitoring-runtime-m6a-2026-10-12.json)，V24；心跳不證明每個工作正常 |
| 有界k6比較 | [Load testing](LOAD_TESTING.md) | M6B5組／807 HTTP／819 checks、55 invariants零違規；closed60人／12票／8VU，非最大容量 |
| V24有界PG備份／empty target restore／原pending replay／真rollback | [Backup](BACKUP_RESTORE.md) | M6C10組／67 invariants零違規；74表178列，sequence0、Redis missing gate仍DIRTY |

## 部分完成

- **持續預扣**：原命令核對與有限返還可用；通用production incremental writer／持續READY／授權輪替未完成，C1/C3實驗僅test classpath。M6B8 RESERVED＋52 gate拒絕，餘4票不接續，不換UUID迴避。
- **部署／容量**：單機雙API／Worker故障與小負載已驗，最新fleet＋本機HTTPS／長時間／最大容量／跨主機HA未驗。
- **設備／安全／備份**：QR／合成stream／場次窗／身份核心與有界PG restore已驗；手機、完整IAM、正式支付、全服務恢復／PITR未驗或未做。

## 尚未完成

Redis Cluster、多broker quorum HA、售票通知、SeaweedFS活動海報、真provider／退款／轉讓、指定座位、離線入場、正式售票App、Prometheus／Grafana／告警、完整員工管理／MFA。按產品需求與[本地清單](CLOUD_LOCAL_FOLLOWUP.md)排期，不全列為Cloud展示阻擋。

## 無法確認

使用者本機硬體／TLS／正式網域／真設備／真活動營運授權／商家憑證／外部正式資料；本輪未連這些環境。最新V24 Nginx fleet、Cluster／跨主機與非空sequence restore無本輪實測證據。

Cloud展示結案不表示持續大量搶票／正式營運完成。private fixtures／cookies／QR bearer code／備份不可放GitHub或履歷頁。
