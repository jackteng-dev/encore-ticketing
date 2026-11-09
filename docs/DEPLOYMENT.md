# M5B 同一產物、雙 API 與獨立 Worker 部署

免費自架的部署基礎：Nginx 直接提供已 build 的 Vue dist，將 `/api/` 分流至兩個相同 JAR 的 API，另有一個不開 HTTP 的 Worker。PG／Redis／RabbitMQ 使用同一 Compose project 的私人網路與獨立 volumes，只有 gateway 綁定 host loopback。**尚未公開 HTTPS、跨主機 HA 或 Cluster 壓測。M5D 已新增 [有界未來來源發現](STOCK_RETURN_DISCOVERY.md)，新功能的實測證據另記。** 本輪實測結果以 HANDOFF 和 verification JSON 為準。

## 角色與旗標

- 無 deployment profile：相容原單一 JAR／host-process demo，沿用明確 opt-in 旗標。
- `api`：servlet HTTP、PG migration、既有會員與員工身份／寫入 API；不註冊 outbox publisher／兩種 expiry／stock recovery／return recovery／return discovery 排程，MQ listener 即使 consumer 旗標 true 仍不啟動。
- `worker`：`web-application-type=none`、不做 Flyway、不初始化員工帳號，載入同一業務服務與有界背景工作；仍須各旗標明確啟用。不能與 `api` 同時選，不能覆寫為 web mode 或啟用 bootstrap，否則拒絕啟動。
- `publisher-enabled`／`stock-recovery.enabled`／`expiry.enabled` 等在兩 API 保持能力宣告，供既有預扣／checkout gate 核對；實際執行由 profile 限制。配置啟用不代表 Worker 此刻存活。Worker 故障時原請求仍可能受理為 PROCESSING，受原 DB deadline 限制，不能宣稱已保票。
- M5D discovery flag 預設 false，API／Worker 需同時啟用 return／return recovery／discovery；API 不執行排程。原 MANAGER 明確有限授權後才發現未來已 ack RELEASE；不是任意來源的持續 READY 投影。新部署預扣預設 false，不因容器化放寬 gate。

API-a 先 migration／readiness，API-b 與 Worker 才啟動；Flyway V1–V22 不改；新 API 先升 V23，再啟動新 Worker。API-a 的啟動順序依賴不代表運行時其他節點依賴它存活；停止 API-a 後現有 API-b／Worker 繼續運行。正式升級仍須先停止舊 memory-session API，不能混版。

## 建立全新隔離展示

需 JDK21／Maven、Node、Docker Compose v2。依環境沿用 proxy／CA。先完整 `backend mvn package`（不跳測試）和 `frontend npm run build`，再 build runtime images。Dockerfile 只 COPY 已驗證產物，不下載依賴；`.dockerignore` 不傳送 work／env／SDK／logs，預設基底 image 固定 digest。

```bash
python scripts/init_deployment.py --env work/deployment/runtime.env \
  --loopback-http --demo-bootstrap --enable-async-checkout

docker compose --env-file work/deployment/runtime.env -f infra/compose.deploy.yml build
docker compose --env-file work/deployment/runtime.env -f infra/compose.deploy.yml up -d --wait
```

`--loopback-http` 明確將 cookie Secure 改 false，僅限本機 HTTP 展示；預設 true。網站 `http://127.0.0.1:15184`。帳密只保存在 0600 的 private env，不打印、不提交。生成器拒絕覆寫既有 env／Redis config；每次新展示 scope 使用時間戳加隨機後綴的 project／image tag／namespace。`--demo-bootstrap` 只允許 api-a 首次在新空 ticket_demo 建 manager，Worker／api-b 不持有展示員工密碼。

不使用 `--enable-async-checkout` 時購票、delivery、publisher、consumer 旗標預設 false；有使用則明確開啟現有非同步保票流程，仍不開 admission、預扣、模擬付款、發票／驗票等額外功能。要開啟其他功能，修改自己的 private env 並 recreate；三個 app 共用功能旗標，角色限制不变。新增背景工作時必須納入 role 限制與相應驗證。

Compose 不與原 `infra/compose.yml`／MQ overlay 合併。原 demo env 的 loopback DB URL 不用於容器 app；部署檔固定 internal DNS／port，只使用該 scope 的 credentials。API／Worker user10001、gateway user101、rootfs唯讀、限制 tmpfs／記憶體／CPU、drop capabilities／no-new-privileges；PG／Redis／MQ 此階段仍用各官方 image 的啟動權限。沒有授予 host networking 或 global volume access。

Docker 沿用 daemon 的 proxy defaults；本輪 build 不需要容器內網路下載，app 只連 scope 內 PG／Redis／MQ。後續若 app 需外部 HTTPS，須另按環境 read-only 掛載 CA 並設定 JVM truststore，不把 session CA 或憑證 bake 入 image，不移除 proxy／停用 TLS 驗證。

## Nginx 與故障邊界

Nginx 是正式反向代理程式與部署設定，不使用 Python test proxy 或 Vite preview。上游在啟動／明確reload時解析Docker DNS，兩 API round-robin；passive failure `max_fails=1`／`fail_timeout=3s`。connect timeout1秒、read/write timeout15秒，最多2個 upstream attempts，允許 connection/error 與 timeout 的安全讀取切換；**不啟用 non_idempotent，不對已送出的POST在timeout／error時自動重放，也不對HTTP 5xx重試**。已送出的 POST 遇未知結果由 client 保留原 UUID／body，再明確核對或重試，不能換新 UUID。

單節點停止時連線／passive失效可能讓最初讀取得到502／504；必須記錄實際狀態並驗證有界收斂，不能承諾無縫讀取。正式驗收要求8秒內恢復、其後20筆嚴格200及原cookie／pending恢復；這是此隔離環境的測試界線，不是公開SLA。已在途寫入可能仍得到502／504，不能承諾每次都透明成功。Nginx 沒有 active health check，Compose health 只是啟動與診斷，不能宣稱自動 readiness-aware HA。節點重建／地址變更後須確認API健康，再執行 `docker compose ... exec -T gateway nginx -s reload` 重新解析上游，不需重啟gateway容器；這輪不啟用動態resolve或宣稱自動服務發現。恢復與reload須驗證，不靠sticky session。

`/api/public/readiness` 在真正 PG SELECT1 成功才 READY，PG故障503／no-store，不揭露 DB細節；不是 Redis／MQ狀態、Worker健康或購票承諾。`/healthz` 只證明 gateway 運行；兩 API／PG都不可用時，前端靜態頁仍可能載入。

gateway 覆寫 X-Forwarded-*、移除 Forwarded；API `forward-headers-strategy=none`，不信任客戶帶入的 forwarded identity／scheme。這是 private HTTP gateway，HTTPS termination／可信proxy範圍／正式host與cookie驗收留本地部署；現在不可直接映射公開0.0.0.0。

access log 只含 method／status／upstream address／upstream status，無 URI/query/body/cookies；原 error logs 仍可能含 URI，全部留 private scope。JS/CSS不存在回404，不回SPA；其他前端deep link回index，不cache API。

## 操作與驗收

```bash
# 有資料的部署：停止時保留 volumes，不加 -v。
docker compose --env-file work/deployment/runtime.env -f infra/compose.deploy.yml down

# 先停止展示釋出gateway port，原volumes保留。驗收另建全新拋棄式scope。
python scripts/init_deployment.py --env work/deployment-test/runtime.env \
  --loopback-http --demo-bootstrap --enable-async-checkout
docker compose --env-file work/deployment-test/runtime.env -f infra/compose.deploy.yml build
python scripts/run_deployment_runtime.py --env work/deployment-test/runtime.env \
  --output work/deployment-proof
```

Runtime 工具由實際已驗證 JAR 核對完整 migration 序列與 DB success count，避免固定舊 schema 版本。M5B/C 舊 verification 保留當時 V22 證據，不代表新 V23 已重跑全部容器組。Runtime 工具先拒絕同 project 的既有 container／volume／network，核對只有 loopback gateway 有 published port，透過 production UI 保留原 pending。它實際 stop/restart API與Worker、PG，並用 PG lock 造成已送 POST 的15秒 upstream timeout，核對代理不自動改投另一 API、原 UUID 最終一個 effect。所有logs／cookies／body／screenshots在 ignored work／0600。運行前須 build 對應 images，不能指向其他 repo 或舊資料。

M5C 已完成[雙 Worker 競爭與原操作恢復驗收](MULTI_WORKER.md)，6組正式容器checks及完整597項測試／build通過。M5D有界新來源發現以自己的HANDOFF／證據為準；M5E已接[自動返還管理UI](STOCK_RETURN_DISCOVERY_UI.md)；M6A基本監控已完成（[操作契約](MONITORING.md)）。仍待：持續吞吐、部署 DB least privilege／migration帳號分離、可信TLS代理／跨主機HA。M6B有界k6與M6C隔離備份已完成，現況見[FEATURE_STATUS](FEATURE_STATUS.md)。本設定的restart policy與三個單機資料服務不等於高可用。

RabbitMQ健康檢查明確以su-exec rabbitmq執行，避免root CLI在首次啟動參與建立Erlang cookie。只檢查owner／mode，不讀或公開cookie值；正式runtime核對UID100與group/other不可讀。原host-process MQ overlay同步修正此健康檢查。

運行前確認 Docker data root／資料 volumes 所在磁碟有足夠可用空間，並在佇列建立後再次核對。Cloud 的 VFS driver 會額外複製容器檔案系統；不能只用 `docker system df` 的 image 大小估算實際占用。本輪曾在 quorum queue 初始化時實際發生 `ENOSPC`，沒有 OOM；保留失敗日誌後，只移除自有未用 image tags、重複 SDK，並對本倉庫 ignored 驗證產物中內容與權限相同的依賴檔案作 hardlink 去重，保留 bytes／hash／原證據。新的 scope 重用同一對已 build images，仍驗證容器內 JAR／dist／Nginx 的 hash；不改成 classic queue、不延長購票 deadline、不清其他專案或 global Docker 資源。
