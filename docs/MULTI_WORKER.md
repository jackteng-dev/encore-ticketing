# M5C 兩個 Worker 的競爭與恢復

沿用 [部署設定](DEPLOYMENT.md) 的同一 production JAR／私人 PG、Redis、單 RabbitMQ quorum queue。增加 Worker 數量不拆業務服務，也不改訊息 schema、票額來源、期限或預設能力旗標。驗收結果以 HANDOFF／verification 為準；單 broker 不是跨主機 HA，本工具不是吞吐 benchmark。

## 自架操作

先完整 `backend mvn package`（不跳測試）與 `frontend npm run build`，再依 DEPLOYMENT 建立新 private env／build images。啟動兩個 Worker：

```bash
docker compose --env-file work/deployment/runtime.env \
  -f infra/compose.deploy.yml up -d --scale worker=2
```

Worker 沒有 container_name、HTTP server 或 host port，因此可用 Compose scale；每個容器仍有相同 nonroot／rootfs 唯讀／記憶體及 CPU cap。啟動時需 API-a 完成 migration；運行中的 Worker 依賴 shared PG／MQ，而不是 API-a 存活。兩個 Worker 都跑既有 publisher／consumer；單一 listener 的 prefetch4 與 consumer1 不改。Worker running 不代表已就緒，要確認 broker 有兩個 consumer 並能處理原請求。

## 可重現的拋棄式驗收

先停止占用 loopback15184 的自有展示（正式資料不要加 `-v`），再建立新空 scope：

```bash
python scripts/init_deployment.py --env work/workers-test/runtime.env \
  --loopback-http --demo-bootstrap --enable-async-checkout
docker compose --env-file work/workers-test/runtime.env \
  -f infra/compose.deploy.yml build
python scripts/run_multi_worker_runtime.py --env work/workers-test/runtime.env \
  --output work/workers-proof
```

工具拒絕既有同 project 的 containers／volumes／network，以及既有 output；只使用生成的 namespace／私人帳密，沿用 proxy／CA。需要本輪 Maven 編譯好的 `MultiWorkerBrokerProbe` test class。工具只將這一份不含秘密的 bytecode 建成 probe.jar，以0444檔案唯讀 mount 給拋棄式 nonroot 容器；host private output 目錄仍0700，不放寬整個 test-classes 或正式 image 權限。用 production JAR 內已存在的 AMQP 依賴；沒有打包進正式 JAR／image，沒有啟動 Spring app、公開 debug API 或下載額外 client。只 passively 查詢 queue，以及 bounded persistent／mandatory／confirmed 原事件 ID 訊息，不建立替代 topology。

驗收依序檢查：

1. 兩個相同 JAR 的真 nonweb Worker、兩個 broker consumers、private ports／hardening。
2. 十二個不同顧客意圖競爭五個單位，只有五筆 RESERVED，其餘 SOLD_OUT；PG 是正式票額來源，每個健康 outbox intent 只有一次發布 attempt。
3. 同一已保票事件額外發布八份 persistent duplicate；PG owner lock 讓兩個真 Worker 同時等待原交易，broker 仍有 unACKed messages。放鎖後 drain，原訂單／HOLD／庫存仍只有一個效果。
4. broker 已真 confirm，但 PG publication receipt 被 private test trigger 擋住時立即 SIGKILL 該 Worker，保存原未到期租約與當時的 business status。confirm 不依賴消費結果，不先等待 RESERVED。另一個 Worker 依原15秒租約到期重新 claim／publish，同 immutable event 只多一次 publication attempt，不多一份訂單或庫存效果。
5. owner lock 擋住 DB commit，以 PG client 找出真正處理原請求的 consumer，再 SIGKILL 該容器。先核對該 event 的 publication 已確認，確保此階段沒有 publisher 重送；以另一容器的 PG 原交易等待與 channel unACKed delivery 核對接手，放鎖後只完成一次保票，原容器重新加入。另一 consumer 可立即取得 requeued delivery，短暫 ready 計數不作必須捕捉的邊界。
6. 原 UUID replay、exact counts／唯讀一致性／audit／build hashes、temporary trigger 已移除、兩 Worker 重新加入。

queue 層的統計可能落後 delivery。ACK 邊界用 `list_channels` 的 consumer channel unACKed 計數配合兩個 PG client 的 lock wait 核對；保存 queue 回報值與 channel 讀值，不用過期的 queue 統計判斷提前 ACK。drain 同時檢查各 queue 與 consumer channels，不把 ACK 條件改成容器 running。

SIGKILL 前只對自有 victim 暫時設 `restart=no`，避免自動重啟掩蓋故障；記錄 deliberate exit137、OOM=false，手動回歸後恢復 `unless-stopped`。owner／advisory locks 和 trigger 只存在新空 `ticket_demo` fixture，沒有改已發布 Flyway bytes、正式 deadline 或 source intent。finally 釋放 locks／解 pause，保存 private logs，僅 dispose 自有 scope。

## 證據邊界

容器 consumer kill 點是 commit **之前**；不能宣稱 kill 恰好命中 business commit 與 ACK 之間。commit 後 ACK loss 由真 PG／RabbitMQ integration test 以 broker invalid delivery tag 關閉 channel，另一個獨立 PurchaseConsumer 收到 redelivery，驗證沒有 JVM-local dedup 依賴。兩個 independent OutboxStore 的真交易／同步起跑亦驗證 SKIP LOCKED 的 shared fencing；store2 必須有 TransactionTemplate，不能直接 new 後假稱 @Transactional 已生效。

這是至少一次投遞與 PG 原操作去重，不是端到端 exactly-once。PROCESSING 不等於保票，publisher confirm 不等於 business commit；Redis 預扣／自動新來源協調／Cluster／多 broker／正式 TLS／負載效能仍須各自驗收。原事件和原 UUID/body 不因未知結果更換。
