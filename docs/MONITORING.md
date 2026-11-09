# M6A 基本執行監控

員工 `/staff/monitoring` 提供唯讀取樣：API／Worker最近心跳、PG持久積壓／租約／REVIEW／庫存gate、Redis PING、MQ被動queue ready／consumers。入口在活動後台；current MANAGER與VIEWER可讀，匿名及顧客不能讀。監控不補票、不claim／重試job、消費MQ、重開budget，也不解除或重送既有員工pending。

## 啟用與角色

| 設定 | 預設 | 範圍 |
|---|---|---|
| TICKET_MONITORING_ENABLED | false | 心跳與依賴／聚合監控；需明確配置每個API／Worker再recreate |
| TICKET_MONITORING_STALE_SECONDS | 15 | 5–120秒，至少三個心跳間隔 |
| TICKET_MONITORING_HEARTBEAT_MS | 3000 | 500–10000毫秒，fixed delay、首次5秒；ApplicationReady也回報 |
| TICKET_MONITORING_EXPECTED_APIS | 2 | 1–16個預期API |
| TICKET_MONITORING_EXPECTED_WORKERS | 2 | 1–16個預期Worker |

Compose app env已傳遞上述設定。新API先完成V24再啟動新Worker；V1–V23不改。無profile為EMBEDDED（同時具HTTP／背景能力），api／worker依原角色契約；API新增的操作心跳不執行購票／返還等業務排程。每次JVM啟動生成新的私有boot UUID，啟動與fixed-delay PG回報更新一份操作紀錄。心跳時間／過期判斷使用PG clock，不是client時鐘；沒有由HTTP提交heartbeat的入口。

V24 `runtime_heartbeat`只是操作觀察，與immutable業務audit分開：無backfill，不修改原業務表；同boot不能被程式換role，API不能回報consumer lifecycle true。程序結束不假稱即時down，超過門檻後顯示過期；重啟為新boot，不重設舊觀察。一pulse最多清100份超過24小時的**操作**舊紀錄，不刪audit或job。不同服務共用同一PG才可聚合；預期數要按實際部署配置。

心跳只證明最近能寫PG，不證明每種scheduler工作正常或購票成功。worker的consumer-running只是Spring監聽器lifecycle，可能在broker斷線時仍為true；真正broker的consumer數另從MQ讀。`capabilities`僅此回覆API節點配置，不能代表其他節點設定、存活或工作進度。

## 讀取API與取樣限制

GET `/api/admin/monitoring`，成功200／`Cache-Control: no-store`；同源staff登入、CATALOG_READ/currentenabled。沒有寫入端點。回覆含 enabled、localRole、staleSeconds、durationMs、postgres(observedAt／三個角色aggregate／counts)、redis、broker(state／三個label queue ready與consumers)、此node capabilities。不回傳boot UUID／host／原order/actor/session／lease token／secret或connection URL。

PG business counts在repeatable-read／readonly transaction取樣，statement timeout1.5秒、transaction4秒；PG取樣結束後才探測Redis/MQ，不把外部IO放進業務交易。Snapshot讀取不更新心跳；心跳是另一個操作scheduler。counts包括：PROCESSING、未確認outbox／未知回覆／expired lease、READY／CLOSED／DIRTY、兩类stock/return WAITING／LEASED／REVIEW／expired lease／用盡budget REVIEW、ACTIVE／EXHAUSTED／STOPPED policy、兩種來源disposition。這是此DB的全局數量，不能接管原管理員操作。

Redis只PING，不跑Lua或改keys。MQ使用**獨立raw client**，不借Spring RabbitAdmin/shared connection（避免自動初始化topology），只queueDeclarePassive，不declare／publish／consume／ACK。最多三份已有configured queues（main/retry/dead labels），連線／握手／RPC timeout1秒、shutdown500ms、無自動恢復；delivery關閉時DISABLED、不連線。UP只代表這次三個RPC成功，MISSING代表configured queue缺失，UNAVAILABLE表示無法核對；後兩者queue map空，不能當ready0／consumer0。ready不含已投遞unACKed，也不證明無積壓或exactly-once；需配合原PG outbox、業務效果與broker channels核對。

PG無法驗證登入或讀取時503／安全訊息，不回傳假零值；`/api/public/readiness`仍只檢查PG，不代表Redis/MQ。監控關閉仍需staff驗證，回覆空roles/counts及NOT_CHECKED，並明示disabled，不能把空集合當零積壓／健康。

PG／Redis／MQ為不同時間取樣，沒有跨服務原子health snapshot。頁面只在明確按鈕讀取，不自動輪詢或寫入。完整欄位／counter驗證失敗、讀取中、request error及過期都標示舊資料；freshness用request耗時與receipt後monotonic elapsed，包含server duration，避免client clock skew／傳輸耗時讓快照假新鮮。snapshot timestamp只供顯示。角色recent不足預期顯示需查證，不直接把stale boot當目前正在運行的node。

## 可重現驗收

`run_monitoring_runtime.py`僅接受新的owned `ticket-monitor-runtime-*`、明確loopback/privatework及兩個已核對SHA的舊artifact：V22供test-classpath helper seed真核心資料，實際V23 JAR建立migration邊界；新V24 JAR核對原public rows不變。真production dist以loopback Vite preview供Chromium驗收，不當正式反代／TLS證據。兩API＋兩nonweb Workers為真實host JVM、PG Redis RabbitMQ為自有資料containers，避免VFS完整app fleet複製；不是跨主機HA或容器效能結果。

```bash
python scripts/run_monitoring_runtime.py \
  --env work/<新目錄>/runtime.env --seed-jar work/<保留目錄>/baseline-v22.jar \
  --baseline-jar work/<保留目錄>/baseline-v23.jar \
  --java-home <已安裝完整JDK21> --output work/<全新證據目錄>
```

Browser各phase使用實際API與PG，建立真實PROCESSING／unpublished outbox與源進展導致的原返還REVIEW，worker publisher明確關、consumer開以保留積壓；API能力配置仍true，不假稱旗標等於publisher存活。SIGKILL一个Worker后等PG TTL和broker consumers降為1，同JAR重啟留舊boot／新回報；Redis/MQ真實TCP stop／same-AOF回归；PG真實stop/start时loaded UI／readiness／authenticated snapshot驗證503與舊資料警示；API監控flag-off再重啟只驗證該API關闭，不假稱全roles關闭。原業務fingerprint／UUID／budget不因读监控改变。

Env、原body／cookies／screenshots／logs均private ignored work。finally只停自有process／dispose自己新scope，核對container／volume／network0，不global prune。測試／build及實測結果以 [HANDOFF](HANDOFF.md)和verification為准；工具存在不代表通過。k6、HTTP throughput／latency／server instrumentation、Prometheus/Grafana時序與告警另排，目前没有performance／SLA／Cluster或HA宣称。

本輪完整624項/30 suites及Vue型別/production build通過。最終真V23→V24的72原表不變、9phase/15組Chromium/實際dependency故障通過，owned程序/c/v/n0。[正式證據](verification/monitoring-runtime-m6a-2026-10-12.json)。兩次工具失敗及首輪兩個MQ fixture斷言均保留，修正只針對fixture/精確202/唯讀SQL包裝，不改business期限/原UUID/budget。
