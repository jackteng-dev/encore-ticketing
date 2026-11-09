# Encore 售票平台交接

## 2026-11-08：自動更新端到端驗證（Encore）

本次只增加此交接段落；不變更應用邏輯、資料庫、帳號、映像 recipe、CI 或部署契約。

- 目的：驗證 Atlas／Encore CD 恢復與主機讀取憑證換新後，正常 PR → 原完整 CI → merge → 私人 GHCR 發布 → Ubuntu 主機自然排程部署的完整鏈仍可用。
- 無可見內容變更；以主機實際部署來源 SHA 等於本次 main 作為判定。
- 主機私人部署完成後，再確認 Funnel 公開唯讀版自動跟隨新部署版本重建。
- 未完成上述實測前，不以本段文件或映像發布宣稱自動更新 PASS；結果保存於 server-management 私人交接。

---

## 2026-10-21 私人 Homelab CD 驗證

- 已完成受控首次部署，來源 `4bf24e78fd18c86c3c818029ce4c632b4a77d014`；此紀錄不是後續自然更新驗收。
- 首次流程包含完整 PostgreSQL、Redis、RabbitMQ 還原及隔離升降／故障回復；正式資料未回灌或初始化。
- 雙 API 與 Worker 採同一 immutable image，Gateway 協調更新；新 Worker 心跳與實際 consumer 均需通過。
- 部署限定無 pending 工作與空佇列窗口，不清訊息、不更改期限，原 DIRTY 持續吞吐限制保留。
- 本次只新增交接紀錄，作為真實 main → CI → 私有映像 → 自然 timer 更新的最小來源變更。
- 驗證標記：`ENCORE-CD-VERIFY-20261021-A`。
- 未改業務程式、資料庫 schema、既有 flags、workflow、部署 recipe 或公開入口。
- 自然更新仍須以新 CI、digest、實際 OCI revision、備份還原與成功 root 交易共同驗收；未完成不可宣稱 AUTO PASS。


更新：2026-10-12（Asia/Taipei；private logs UTC）。以實際遠端main `9e9e088d57e54eba73f1b447520b1276a0f15e39`開始，fetch／HANDOFF／AGENTS／README／status／branch／log／diff含staged／ff-only完成，只修改ticket-platform。前輪交接保存在[M6C](verification/handoff-m6c-2026-10-12.md)。cloud-environment-runtime與Docker／networking已讀，revision351/current/connected/enforced unrestricted；保留proxy／CA／local Docker socket，無configured secrets／identities。沿用唯讀JDK21、自己的Maven cache／flat PG/MQ，未下载新SDK／模型或付費服務；無精確usage介面，按獨立里程碑保存。

## 本次完成

**M6D受控Cloud展示收尾完成。** [展示手冊](SHOWCASE.md)、[功能矩陣](FEATURE_STATUS.md)、[驗收](verification/showcase-m6d-2026-10-12.md)／[JSON](verification/showcase-m6d-2026-10-12.json)。README／ROADMAP／ARCHITECTURE／本地清單改為目前狀態，移除入口「k6／備份／場次窗未做」過期描述；完整舊架構封存。四個顧客頁修正mock／領票文案，未改功能旗標或交易。手冊明示需開旗標、另建展示scope、不對保留資料跑fault tests、時區差異、停止保留volume、故障與partial邊界。

完整測試發現並修復既有**人工REVIEW同UUID競爭**：第一個command讀取為空，peer完成原command後fresh validate誤報REVIEW_CHANGED；現在此錯誤才重新鎖讀原command，核對同actor／intent／body後返回原receipt。不是重試整個交易或取消檢查，其他actor／改body／fresh新UUID仍拒絕，不改票額／期限／job budget／REVIEW。兩項固定latch順序在舊碼失敗、新碼通過。

## 重要檔案

- SHOWCASE／FEATURE_STATUS：可重現正常流程、狀態與證據／limits。
- README／ROADMAP／ARCHITECTURE／CLOUD_LOCAL_FOLLOWUP／DEPLOYMENT：目前架構與收尾／本地優先序；不把退款／MFA／真服務全列換作品前置。
- StockReviewService：原command在READ COMMITTED競爭後重新核對。
- StockReviewIntegrationTest：UNUSED／SETTLEMENT固定peer先完成的deterministic regression，仍核對不改budget／audit／order。
- CustomerLogin／EventDetail／Purchases／Reserve：僅修正過期文案。
- verification/showcase-m6d-*、architecture-before-m6d-*、handoff-m6c-*：去識別證據与完整前輪文件。
- 本輪臨時展示harness／fixture／PNG／env／logs在ignored work/m6d-*；不是新通用產品或CI入口。

## 測試 / build

新空owned PG／Redis／RabbitMQ scope完整**mvn package 629項／30 suites，failure/error/skip0**。Maven256MB、suite384MB、SerialGC／CPU2／fork1／reuseForks=false，沒有H2／跳測試。StockReview單組22/0，新增兩項。Vue型別／production build通過，最新JS index-CH8ZALSJ.js、CSS index-C2FRQd4L.css；JSON有JAR／dist SHA256。V1–V24 source及packaged migration bytes對baseline完全相同。

用同一629項package JAR及最新dist，host EMBEDDED JVM／Vite production preview＋真data containers，**Chromium151 UI10組通過、7項readonly PG不變條件零違規**：活動／場次／12票種／發布、台北OPEN窗口、顧客／員工cookie並存、排隊→202→真MQ正式order、手動mock PAID→明確2張領票與原QR、PG第3張限購拒絕、另一顧客保票取消、實際production SVG圖片解碼只填碼／明確入場／重掃不能第二次、第二張撤銷、readonly monitoring與393×852 fit。PG最後capacity12/held0/sold2、PAID1/CANCELLED1、purchase RESERVED1、issuance1/issued2、CHECKED_IN1/REVOKED1／terminal audit2。這不是最新Nginx／雙API／雙Worker fleet重驗或真手機。

## 失敗與恢復證據

首輪完整627/30為0fail/1error：既有StockReview六執行緒race。新固定順序UNUSED／SETTLEMENT各1error（舊碼共2/2），小幅修復後整組22/0，再另一新空scope629/0；原失敗logs/XML保留，不用重跑隱藏競爭。

展示runtime1：我在harness及手冊填expected role0，MonitoringSettings只允許1–16，啟動拒絕；已改valid2/2並保留EMBEDDED缺split-role警示，不放寬validation。runtime2：harness漏接受發布window.confirm，無publish POST；補明確確認。runtime3：過前7組，但早期捕獲PNG沒有完整QR，local獨立jsQR亦拒絕；改等待完整票券GET與read control unblocked再截原SVG，decoder／票碼未改，也未用另產QR代替。runtime4過10＋7。各次新owned disposable scope／原失敗不覆寫，沒有production自動換UUID重試、放寬deadline／gate／預算；不能確定所有渲染時序機制，這是harness修正而非通用QR改善宣稱。

## 清理與環境

只處理本輪自有scopes，無global prune／daemon restart／其他作品操作。全部自有JVM／preview退出，兩個完整測試scope及四個展示scopecontainers/volumes/networks0，25432／26379／25672／19110／15180關閉。自己的依賴映像／cache／原失敗／成功XML與private receipts保留。所有QR／cookie／個資／dump保持private，不commit。

## 尚未完成 / 已知限制

- **持續預扣仍部分完成**：M6B8/12後DIRTY、餘4不能接續；有限來源授權／同epoch原證據不等於continuous writer／授權輪替。C1/C3增量實驗test-only。沒有新高吞吐宣稱。
- 本機HTTPS／可信proxy／Secure Cookie、DB runtime least privilege／migration分離、完整IAM／全域session清理、真手機／現場、實際24–32GB多作品資源容量未驗。
- 最新V24 fleet未重跑全套Nginx容器故障；歷史M5B/C V22 evidence保留，後續feature多用host JVM。Nginx節點IP換後須reload，最初502/504可能，非無縫HA。
- PG／Redis／RabbitMQ仍單節點（單broker quorum），Cluster／多broker／跨主機HA未做。等候順序與資格Redis-only，不是PG durable fairness。
- M6C備份為V24 ticket_demo有界offline PG dump（100tables／100sequences／64MiB），source sequence0；未驗非空sequence、Redis/MQ/物件volume、PITR／跨版本／正式全服務切換。新target空RedisKEY_MISSING仍DIRTY，新流量disabled。來源與目標不得双邊對外履約。
- 沒有真收款／售票通知／SeaweedFS海報／完整退款轉讓／MFA／指定座位／離線入場；基本監控不是Prometheus／Grafana／告警。EMBEDDED只1node，保留expected API2/Worker2不足警示，不能當全部健康。

## 下一步

受控Cloud展示範圍**結案，可以切回整體展示平台的下一個未完成作品**；不預設無限追加售票商用功能。若回到本repo，先fetch實際remote main、讀此交接／AGENTS、status/branch/log/diff含staged與ff-only；工作副本 `/workspace/work/ticket-platform-review-20261012`。本機可用後優先CLOUD_LOCAL_FOLLOWUP P0；要主打持續大量搶票則先解決DIRTY協調，再warmup／多輪／長時間負載。所有新里程碑仍完整tests/build／HANDOFF／commit/push／remote與clean核對。
