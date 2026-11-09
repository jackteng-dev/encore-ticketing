# M4D2C4 正式取消／到期返還的新 epoch 接棒

C4 完成時狀態：**V14 與 production 內部 StockReturnService，明確呼叫的新 epoch 接棒。** 沒有新 HTTP API、員工 UI、排程或自動 consumer；沒有變更 checkout flags、原 StockHold／Settlement／Projection 或 Redis Lua。C3 仍是 test-only 證據游標，沒有拿它當可售票量。

當前 C5 已接 [verified 員工入口](STOCK_RETURN_STAFF.md)；下述無 HTTP／UI 的描述與 V13→V14 runner 指 C4 階段。舊 runner 要用 C4 checkout／V14 final artifact，不能把 current V15 當成 V14；當前升級驗收使用 C5 腳本。

## 返還如何成為可再次預扣的票額

原 Redis COMMIT receipt 是「這筆預扣已正式成立」的歷史，正式訂單取消／到期不應將它改成 RELEASED。V13 原 ORDER_RELEASE、context 和 audit 已在取消／到期交易中保存精確來源，返還命令只消費這些已提交證據，不補寫歷史。

C4 選擇 **新 generation 的完整 PostgreSQL snapshot**：

1. 核對原 accepted intent、COMMIT decision 與 ack=COMMITTED，以及原訂單 RELEASE；來源 revision 必須恰為當前 source head。
2. 原 UUID 註冊返還命令，同 PG 交易分配 fresh CLOSED generation、保存原 rebuild command 和 REGISTERED audit。沒有任何 Redis IO。
3. 原 UUID reconcile，在 ticket 鎖下用既有 projection.complete 初始化新的 epoch key；基準為 PG `capacity − held − sold`，不是舊 Redis available加quantity。
4. PG READY、返還 ack與APPLIED audit同交易 commit。然後既有 StockHoldService 可在新 generation 預扣；舊 FROZEN epoch及原COMMITTED receipt保留。

例子：capacity4，原一筆 COMMIT後舊ledger available3／committed1；取消後 PG available4。新generation baseline4／available4／held0／committed0。新HOLD1後available3／held1，conservation維持4；舊receipt仍COMMITTED，舊OPEN仍拒絕。

若另一筆已付款，PG sold1，取消第一筆後新baseline是3；**不把quantity加到capacity，也不重複credit**。原 intent 可以屬於更早 generation；接棒來源 generation取原 RELEASE context，不取最早intent。若事件已在另一fresh generation的snapshot內，RETURN_ALREADY_INCLUDED拒絕再登記。

## 持久身份與來源去重

V14只新增 `stock_return_command`／`stock_return_ack`／`stock_return_audit`；無 backfill，V1–V13 bytes不變。原 RELEASE `(ticket_id,to_revision)`和order是業務 effect identity；`order_id`／source tuple UNIQUE防止另一 UUID採用同返還。command綁ticket／order／原intent／releaseRevision／sourceGeneration／newGeneration／full available；同 UUID改order或revision衝突。

command與原rebuild引用同UUID。SQL驗證原COMMIT／COMMITTED ack、原RELEASE status／quantity、V13 origin／audit、完整snapshot、最新revision、source generation+1，以及rebuild的xmin屬目前註冊transaction；不能把先前ordinary rebuild改標為返還。原返還命令是後續consume，不冒充同交易原業務來源。

兩phase audit及ack不可改寫。deferred ack驗證精確READY snapshot／currentgeneration／sourcehead／available，且新epoch尚無任何intent。來源與scope均由PG原資料裁定，呼叫者不提供quantity或Redisbalance。

## Fence 與 UNKNOWN

- 原 formal COMMIT尚未ack、ABSENT／KEY_MISSING、無accepted intent的同步RELEASE、PENDING或PAID沒有RELEASE：拒絕登記。
- 所有ticket的原未解決intent（包括HELD／PROCESSING／未ackdecision）沿用Projection.begin的阻擋；已正式解決的其他PG訂單held／sold則留在完整snapshot內。
- source在begin前或兩phase間前進、newgeneration被取代、來源已被較新snapshot吸收：不改body、不換UUID、不加票；保留原命令供核對。
- 新Redisepoch wrong type／TTL／壞counts／FROZEN按原Projection規則拒絕，不覆寫。缺新key只有既有「尚未READY、無intent、durably CLOSED」bootstrap條件允許初始化；歷史APPLIED後丟key不重新seed。
- Redis OPEN已成功、PG APPLIED audit失敗：PG rollback回CLOSED，沒有可受理的新PGgate；保留同命令，以原新epoch snapshot核對補完。真正Redis停機回PENDING／REDIS_UNKNOWN，不補償INCR。
- 已APPLIED原命令reconcile為PG-only historicalReplay，不代表現在Redis健康或目前gate仍READY。新epoch已有HOLD時重送舊返還不呼叫INIT／OPEN，不重設票額。並發completion在同ticket鎖下先查ack，只有一次非歷史apply。

## 操作與驗證

production只提供內部 `begin(requestId,orderId,releaseRevision)`、`inspect(requestId)`、`reconcile(requestId)`；沒有對外路由／背景觸發。未來接員工 API時必須加verified actor／permissions／同交易員工audit與原UUID未知操作UI，不能直接把內部bean公開。兩phase不能由外層transaction包住；需要在每個原獨立phase加入permission guard／audit hooks。現有B5維護仍依既有規則運作，本輪不更動其操作流程。

完整backend `mvn package`、frontend `npm run build`按README。`StockReturnIntegrationTest`使用真PG／Redis核對新可預扣epoch、取消／expiry、其他sold庫存、較早intent／較新RELEASEgeneration、並發、phase audit rollback、來源／generation／active intent fences、歷史replay及SQLimmutable／deferred約束。

最終JAR的production StockReturnService另用test-only runner驗收真V13→V14 upgrade／同AOF恢復。需要自己的 **新空** `ticket-return-upgrade-*` PG／Redis scope、private env、先前保留或獨立構建的V13 JAR，以及完成fullpackage的當前JAR；腳本不下載／clean／repair／dispose：

```bash
python scripts/run_stock_return_handoff.py \
  --env work/自己的scope/runtime.env \
  --baseline-jar work/自己的scope/baseline-v13.jar \
  --output work/自己的scope/proof
```

腳本核對容器label／loopbackendpoint，真V13 core先建立原acceptedCOMMIT與正式cancel。以finalV14 core只migrate，核對所有舊public業務表rows（排除Flyway history）和原RedisHASH raw values不變、新表全空；prepare註冊新epoch並注入PGAPPLIED auditrollback；WAITAOF／真停止ownRedis／TCPclosed後offlineUNKNOWN；sameAOFrestart以原UUID補完PGREADY／ack／audit，原verifiedcustomer在新epoch實際HOLD成功，原ledger／COMMITTED收據與原業務證據不變。

這是core service及真儲存驗證，不是本輪新增HTTP／UI；不啟用原predebitflag。正常stop／restart與CHECKrollback不等於TCPcommitACKdrop、kill-9、Cluster、HA或壓測。只讀核對見 `scripts/verify_stock_return_invariants.sql`；當輪結果見HANDOFF／verification。

## 下一階段

C5 已接verified員工返還coordinator／權限／原UUID進度API與未知操作UI。source／generation前進導致pending的terminal review／replacement政策需明確記錄原effect已被哪個snapshot吸收，不能刪command改UUID重做。之後再做有界recovery、批次／更晚head、active intents安全接棒和live incremental consumer；C4並未解決持續吞吐或自動READY。

## C5 員工入口

C5 已透過 guarded phase hooks 接上 [verified 員工 coordinator／API／未知操作UI](STOCK_RETURN_STAFF.md)。此 core 仍為內部 primitive，HTTP 只經員工 coordinator；無背景 consumer。V1–V14 migrations保持原 bytes。pending replacement 政策另待設計。
