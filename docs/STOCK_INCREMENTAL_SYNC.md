# M4D2C1 投影增量同步契約與單一 COMMIT 實驗

後續進度：C2 已新增 [正式庫存來源證據](STOCK_REVISION_ORIGINS.md)，覆蓋業務變動的同交易 provenance；C3 已完成 [多 revision 有界消費證明](STOCK_MULTI_REVISION_EXPERIMENT.md)。Redis cursor／consumer 仍僅 test-only 實驗。以下是 C1 當輪範圍及證據。

狀態：**安全契約與 test-only 可重現證明**。這輪沒有 production incremental writer、API、排程、旗標或 Flyway migration；V1–V12 與正式購票路徑保持原樣。實驗 schema、Java coordinator、Lua、runner 都在 `backend/src/test/`，不進 production JAR。正式 gate 仍 DIRTY，原 epoch 仍 FROZEN；沒有持續吞吐宣稱。

## 為何不能直接套 journal delta

V7 `stock_revision` 是不可變庫存快照，只有票種、revision、capacity／held／sold／version。它沒有直接指向原 order event、claim 或正式決定。靠時間、數量相同或「目前最新 revision」推斷因果，不能證明沒有漏事件或重複套用。

Redis HOLD 已把 available 減去 quantity。正式 worker 的 PG 保票再把 PG held 增加相同 quantity，是**確認同一筆預扣的正式庫存占用**。若同步器再把 journal 的負 available delta 扣一次，最後一張票會被重複扣。

最小例子：初始 PG 可售4；Redis HOLD後 available3／held1；正式 PG 保票後 PG可售3；原 Redis COMMIT把 held1變committed1，available仍3。cursor從revision1前進2，不能再把available改成2。

在這個只有原始空投影與一筆 COMMIT 的實驗中，可核對：

`PG available(cursor) = Redis available + Redis held + Redis committed - 已吸收的 COMMIT quantity`

cursor1時已吸收0；cursor2時已吸收1。Redis baseline仍4，原 receipt與台帳不因cursor核對而改寫。此公式**尚未覆蓋正式取消／到期／付款、舊PG庫存、其他active holds或多個並行decision**，不能套到完整產品當通用補票公式。

## 正式接入前必須滿足的契約

1. **原業務交易保存因果。** 每次正式庫存 mutation與原 `stock_revision`、原order／payment inventory event、原claim／decision關聯必須同一PG交易提交。未受預扣保護的同步保票、quote／capacity變動也要有明確類型。舊history不能根據時間補成可信因果；沒有證據的範圍維持凍結，走既有明確新epoch。
2. **PG分配epoch與命令。** 同步 command不可變，綁原UUID、ticket、generation、連續from／to revision、原業務event／intent／claim及演算法版本；同UUID改body衝突。journal cursor不得跳過未知revision，MQ只喚醒，不以message payload授權或裁定庫存。
3. **先持久保存，再碰Redis。** command／REGISTERED audit先提交；原PG正式決定先提交，才允許核對相應COMMIT。不能把尚未提交的結果當成功，也不能用TTL推斷放棄。BEGIN／APPLY拒絕不合適的外層transaction。
4. **原UUID冪等apply。** 同slot Lua把apply tombstone與cursor一起寫；先驗原key type／TTL／結構、generation、cursor、原claim全payload、terminal receipt及預期counts，再寫。COMMIT吸收既有預扣，不重扣available。命令冪等與業務receipt冪等是兩個需要核對的層次。
5. **PGcheckpoint與audit同交易。** Redis已apply但PGphase rollback／回覆未知，保留原command與先前checkpoint；重讀原PG及Redis marker，用同UUID/body補完。完整PGack可以PG-only回放，僅代表歷史command完成，不能代表目前Redis健康或新入場可以開放。
6. **兩層fence。** 在ticket／cursor鎖下核對原generation與來源revision；Redis同slot檢查epoch／cursor。source前進超出已證明範圍、active holds、相反receipt、缺key、TTL、損壞、未知transport都不盲目HSET最新available／INCR／初始化現epoch或換UUID。
7. **READY另有完整證明。** cursor成功不等於gate可以READY。需要跨revision完整因果、所有仍有效HELD／PROCESSING／未ack decision計入、晚到writer排除，以及PGstock／Redisledger／消費checkpoint一致。現有不可重開FROZEN語意不能為省事直接取消；要設計版本化台帳或新epoch的安全接棒。
8. **完整庫存效果分類。** accepted COMMIT、未受理intent的ABANDON、正式拒絕RELEASE、已COMMITTED訂單的取消／到期、held→sold付款、capacity／quote變動分別處理。COMMITTED不能用原HOLD RELEASE補票；正式返還需獨立不可變event與去重marker。未分類效果禁止放行。
9. **可驗證有界操作。** 本輪沿用500ms Redis timeout、10秒PGphase，失敗保留未知。之後需要有限batch／lease fence／retry budget／review與可觀測性；不以延長timeout冒充高吞吐。收據／tombstone GC需另證明晚到重送不會復活。

## 本輪最小實驗

`CommitCursorFixture` 呼叫現有 Catalog／Customer／StockHold／Purchase core建立合成已受理請求。test wrapper將 `PurchaseService.process` 與新實驗command置於**同一PG交易**。實驗SQL trigger驗證decision與to-revision的`xmin`都屬當前交易，以及原HOLD event、前後快照、原intent identity／generation／quantity。這是實驗中的因果捕捉，**C1 當輪正式 worker 尚未接入此實驗 writer**；不回填舊journal。

測試schema `stock_commit_experiment` 含immutable command／ack／audit與guarded cursor。REGISTERED由command trigger記錄；APPLIED由ack trigger記錄；deferred constraint要求ack與精確下一cursor及原正式COMMITTED證據一起commit。schema整體以單一PG交易建立，失敗不留半套初始化。

消費前呼叫既有 `StockSettlementService.reconcile`，依原正式COMMIT核對原receipt並保存原PGack；原台帳依既有路徑凍結。實驗只接受：

- 初始空READY rebuild的baseline與from revision。
- 一筆原accepted COMMIT，capacity／sold／ticket version不變、PG held恰增加原quantity。
- 同generation、source恰為下一revision、production gate DIRTY。
- 原PGack明確COMMITTED；ABSENT／KEY_MISSING不能當作Redis receipt存在。
- 原ledger FROZEN、held0、entries1、counts精確一致，唯一receipt的全部7欄精確匹配且COMMITTED。

Lua使用原epoch ledger與 `原ledgerKey:commit-experiment` sidecar，兩key共用原ticket hash tag。**原ledger唯讀**；只以一筆HSET保存sidecar下一cursor與原命令digest marker。沒有新增原ledger欄位，既有HLEN=8+entries規則維持。sidecar限單一COMMIT；不能藉此串接第二筆或跳revision。same-slot結構不等於已通過Redis Cluster測試。

sidecar seed只供合成fixture在原空READY投影且尚無intent時建立，不是production復原程序。缺失後consume不seed、不INIT。實驗scope要求`ticket_demo`與`encore-demo-increment-*` namespace，runner另要求自己的`ticket-increment-*` project；資料與原UUID保存在private work，不可提交。

## 驗證與重現

依README建立**新的拋棄式隔離PG／Redis／MQ**。完整 `mvn package` 跑原341項與新增20項實驗；`npm run build`仍含typecheck。JUnit context自行使用`encore-demo-increment-test` namespace，沿用當前隔離Redis endpoint，不必改正常demo的private namespace。獨立runner的private env則必須明確使用`encore-demo-increment-...`及`ticket-increment-*` project。

新增測試涵蓋不重扣／原whole hash保留、六並發原UUID、Redis checkpoint成功後PG audit rollback原命令恢復、provenance失敗與正式order／stock／decision共同rollback、拒絕跨交易retroactive attribution、active holds／無正式決定／未ack／ABSENT／反向receipt、已PGack但原Redisreceipt變更owner或status、缺原key或sidecar不seed、TTL／wrong type／壞counts／假marker、後續取消revision與新generation fence、immutable SQL evidence、原body衝突、外層交易與seed限制，以及ack缺精確domain evidence或cursor不能commit。

另以最終production JAR classes/resources **加明確test-only classpath** 跑 `StockCommitCursorExperimentRunner` 的prepare／offline／verify三個獨立程序。prepare注入真PG audit CHECK rollback，sidecar先前進而PGcursor仍1；確認local WAITAOF後停止自己的Redis並確認TCP关闭，offline只回UNKNOWN且原PG進度可讀；同AOF volume重啟後verify以原UUID/body補完缺失PGcheckpoint，核對原whole ledger／receipt bytes與原PG intent／decision／ack／order／event／stock／gate／journal完全不變。

runner使用方式（repository根目錄，JDK21，classpath含最終JAR抽取的classes／lib與`backend/target/test-classes`）：

已export自己的private env，已完成package且隔離Compose正在執行時，可由安全檢查過的腳本自動抽取最終JAR、執行三個程序、WAITAOF、停止／啟動自己的Redis並核對TCP與結果：

```bash
python scripts/run_stock_commit_experiment.py --env work/你的scope/runtime.env --output work/你的scope/proof
```

腳本不下載工具、不dispose volume；輸出目錄必須是全新private work路徑。若offline失敗，也會恢復自己原Redis容器。資料核對與清理須由本輪操作者完成。

也可以在相同受控scope手動執行：

```bash
java -cp "$TICKET_EXPERIMENT_CP" dev.elpsy.ticket.StockCommitCursorExperimentRunner prepare work/你的scope/original.json work/你的scope/prepare.json
# 停止自己的Redis，確認其TCP關閉後
java -cp "$TICKET_EXPERIMENT_CP" dev.elpsy.ticket.StockCommitCursorExperimentRunner offline work/你的scope/original.json work/你的scope/offline.json
# 啟動同一Redis AOF volume後
java -cp "$TICKET_EXPERIMENT_CP" dev.elpsy.ticket.StockCommitCursorExperimentRunner verify work/你的scope/original.json work/你的scope/verify.json
```

這是CHECK commit前rollback與正常停止／重啟，不是PG TCP commit ACK loss、Redis成功回覆TCP drop、kill-9、Cluster、跨主機HA或負載測試。實際結果、artifact與cleanup見本輪HANDOFF及verification證據。

## 下一步

C2 已完成正式來源記錄與歷史缺證據的 fence，見 STOCK_REVISION_ORIGINS。接下來設計多revision／active holds條件下的投影消費與READY publication；正式取消／expiry單獨event apply與failure矩陣需逐個里程碑驗證。不要直接把本輪test-only schema／單筆Lua複製成production gate。
