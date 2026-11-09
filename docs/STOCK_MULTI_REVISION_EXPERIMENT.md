# M4D2C3 多 revision 的有界消費證明

這是 **test-only、固定 source head、FROZEN epoch 的證據游標實驗**。使用 C2 正式 V13 的 immutable context／origin／audit；不是新 production consumer、Redis 可售票 writer、READY publication 或持續吞吐。V1–V13 沒有修改，不新增正式 schema、API、flag、排程。原購票流程、gate DIRTY／epoch FROZEN 保持既有語意。

## 效果分類與本輪邊界

| 正式來源 | 原 Redis 台帳效果 | 本輪證據快照效果 | 本輪支持 |
| --- | --- | --- | --- |
| accepted ORDER_HOLD，原 COMMIT 已 ack | HOLD 已預扣、COMMIT 已轉 committed；不再扣 | available 減 quantity | 是，同 generation、全部原 claim 精確核對 |
| 已 COMMITTED 訂單取消／到期 ORDER_RELEASE | 原 receipt 永遠保持 COMMITTED；不呼叫 RELEASE／INCR 補票 | available 加 quantity | 是，必須關聯範圍內較早的原 HOLD |
| 成功 PAYMENT held→sold | 不重扣，不改原 receipt | available 不變 | 是，原 payment event 已由 V13 同交易驗證 |
| 無預扣 intent 的同步 ORDER_HOLD／RELEASE／PAYMENT | 要另設獨立 business-effect marker | 本輪不能併入 | Fence；不因 delta 相同冒充 accepted COMMIT |
| CATALOG_CREATE／UPDATE，capacity／quote／version | 需要 terms／quote 與 gate 的新證明 | 本輪不能併入 | Fence，零 available delta 也不能略過 |
| UNATTRIBUTED／歷史無來源 | 沒有可信因果 | 不推測 | Fence |
| 仍 HELD／PROCESSING、RELEASE 決定、ABSENT／KEY_MISSING ack | 無法證明全 epoch conservation | 不推測 | Fence，沒有 active-hold 支持宣稱 |

來源證據已在原業務交易中提交，因此 C3 註冊可在後續獨立 PG 交易讀取；這是消費命令，不是 retroactive attribution。原 event／intent／order／payment identity 均來自 PG V13，沒有根據時間或數量補來源。

## 固定 head 的 conservation

fixture 必須先用既有 rebuild 建立 **held=0、sold=0、尚無 intent** 的原空 READY epoch；test sidecar 在所有 intent 前初始化。這個 seed 只作合成測試，不是恢復程序。限制 root→head 最多 16 個 revision、整個 epoch 最多 8 筆原 COMMIT；全部來源必須連續、有 audit、同 generation，原 claims 全部正式 COMMIT 且 ack=COMMITTED。超界停止，不截斷批次。

令 B 為 root 可售量、Q 為整個固定 epoch 的已 ack COMMIT quantity：

- 原 ledger available 永遠是 `B − Q`，held=0、committed=Q；consume 完全不寫它。
- revision r 的證據 available `E(r) = B − 已消費 accepted HOLD quantity + 已消費正式 RELEASE quantity`。
- 等價核對：`E(r) = 原 ledger available + 尚未吸收 HOLD quantity + 已吸收正式 RELEASE quantity`。
- PAYMENT 的 delta 為零；其 held／sold 與成功 event 仍要有原 V13 證據。

例子 B=4，兩筆各1的預扣都正式 COMMITTED；取消第一筆、付款第二筆。原 ledger available2／committed2，兩個 receipt 均 COMMITTED。證據 cursor 1→2→3→4→5，available 4→3→2→3→3；最終匹配 PG capacity4／held0／sold1。**證據 available3 不可用來接受新 HOLD**。正式返還並未修復原 epoch 的 spendable available，故仍不能 READY。

原台帳校驗覆蓋 generation、phase、baseline／available／held／committed／entries／limit、HASH cardinality、無 TTL／type，以及全部 receipt 的7欄 identity／status。原 totals 相等但其他 claim 換 owner／status 也拒絕。

## 原命令與兩層 checkpoint

`stock_multi_experiment` 僅由 test classpath 建立；command、ack、REGISTERED／APPLIED audit 不可改寫。PG plan 函數核對 root→head 全來源、所有原 claim 與 ack；每個 command 綁原 UUID、ticket、generation、精確 from→to、固定 head、原來源 identity、before／after available、全部 claim 和演算法版本。digest 綁 UUID 與 canonical PG jsonb 文本。同 UUID/body可註冊重讀；新 UUID 不可採用同 revision，改 body 衝突。

註冊 transaction 在 Redis apply 前提交。consume 拒絕外層 transaction，ticket／cursor 鎖序固定；先核對 PG cursor、generation、固定 source head、原 plan。source 前進後仍未完成的原 command 回 SOURCE_CHANGED，**不改 head、不換 UUID、不補 seed**。已完成 command PG_REPLAY 只代表歷史完成，即使 Redis 離線也不授權 READY。

同 ticket hash tag 的 Lua 唯讀原 ledger，只改 `:multi-experiment` sidecar。每次僅一個 revision；核對所有已完成 PG command 的 digest marker、前一 cursor／available／last UUID／marker 數與 exact HASH cardinality。新 command marker 與下一 cursor／available 一筆 HSET；原 marker 已存在則完整核對後 REPLAY，不二次改快照。sidecar 最多16個 markers，不做 GC。

Redis apply 後 PG ack／cursor／APPLIED audit 在同交易提交。SQL guarded cursor 和 deferred ack 要求精確下一 command、digest、來源 plan 和 evidence available；audit CHECK 失敗共同 rollback。Redis 已前進、PG 還在前一 revision 時，重試同 UUID/body核對同 marker，補齊缺失 PG checkpoint。下一筆不越過尚未 ack 的前一筆。Redis UNKNOWN、缺 key、TTL、wrong type、壞 count、假 marker 不 ack、不重設。

## 重現

完整 backend `mvn package` 與 frontend `npm run build` 按 README。在自己的新拋棄式 PG／Redis／RabbitMQ 執行；不使用原作品資源。新增 `StockMultiCursorExperimentTest` 包含順序、並發、rollback、expiry、全 receipt 校驗、原 UUID／digest、generation／source／unsupported source fence、missing／TTL／wrong type、bounded range 與 immutable／deferred SQL 約束。

可用最終 production JAR classes/resources 加明確 test classpath 跑三個獨立程序：

```bash
python scripts/run_stock_commit_experiment.py --experiment multi \
  --env work/自己的scope/runtime.env --output work/自己的scope/proof
```

private env 必須是 `ticket-multi-*` project、`encore-demo-increment-*` namespace、own loopback ticket_demo／Redis；腳本先核對容器 label 和 port binding。prepare 建立兩筆真正式 COMMIT／取消／成功 mock payment，消費第一筆、第二筆注入 PG audit rollback。WAITAOF 後只停止自己的 Redis、確認 TCP 關閉；offline 證明 UNKNOWN 與歷史 PG-only replay。重啟原 AOF volume，verify 以原 UUID／digest恢復第二筆，再消費原 RELEASE／PAYMENT，核對原完整 HASH raw values、PG domain／來源／audit 全未變，gate 仍 DIRTY。

`--experiment commit`（預設）仍跑原 C1 實驗；兩者不進 production JAR。腳本不下載、dispose 或清理其他資源。實測結果見當輪 HANDOFF／verification；這是正常容器停止／重啟和 CHECK rollback，不是 TCP commit ACK drop、kill-9、Cluster、HA或負載驗收。

## 下一階段

先設計正式 business-effect 可售票台帳，或新 epoch 的安全接棒，讓已 COMMITTED 訂單正式返還有獨立 immutable effect／去重 marker；同時納入同步 HOLD／terms 和 active holds conservation。須明確排除晚到 writer、generation／lease fence、未知 apply恢復和 publication race，逐個有界實驗證明後才接 production consumer。不要拿本輪證據 sidecar 當可售量，更不能去掉既有 FROZEN 禁止重開規則。

只讀 SQL 核對：`scripts/verify_stock_multi_experiment_invariants.sql`；不初始化／修復 schema，舊 ack 視為歷史，不要求每一筆都等於目前最新 cursor。

後續：[M4D2C4正式返還接棒](STOCK_RETURN_HANDOFF.md)採用完整PG snapshot的新epoch，不將本實驗sidecar當作可售票量。
