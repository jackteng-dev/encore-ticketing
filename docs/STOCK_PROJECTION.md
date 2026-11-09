# M4D2A PG 持久 generation 與重建協調

本階段完成持久協調底座，**顧客預扣已接 B4 預設關閉入口；內部 worker／持久 intent 核對與自動 recovery 已接 B2／B3**。StockProjectionService 是內部 maintenance bean；M4D2B5 新增受 staff 權限保護且預設關閉的 coordinator／UI，原 begin／complete 的 PG gate 與未解決 intent 條件保留，沒有自動排程；現有購票仍由PG正式庫存裁定。M4D2B已登記內部持久預扣命令、接Lua与既有受理／訂單交易並驗恢復窗口；詳見下方連結。

## V7與正式庫存變動

- stock_projection保存每票種source_revision、PG generation、CLOSED／READY／DIRTY門狀態與原rebuild UUID／checkpoint。
- stock_revision是不可變逐版本快照，保存capacity／held／sold／ticket version；初始1，後續單調。V6既有票種backfill精確庫存，DIRTY／generation0，不自動放行。
- stock_rebuild_command不可變，global request UUID綁票種、generation、source_revision與原可售快照；同UUID不能改內容。

ticket_type的AFTER INSERT／UPDATE trigger覆蓋capacity／held／sold／version變動。在原業務PG交易中source_revision+1、journal寫入、門變DIRTY；trigger失敗整個保票／付款等業務交易回滾。同步保票／worker保票、取消／到期、付款held→sold以及草稿庫存／quote版本改動都覆蓋；held→sold雖然可售不變仍留journal。noop不增版本。未來退款若改同一正式庫存欄位也會觸發，但退款業務尚未實作。

旧order／payment不可變事件繼續保留各自業務語意；新journal是庫存快照序列，不把它冒充付款事件或逐請求Redis補償依據。V1–V6不改bytes。

## 分兩筆已提交PG交易

1. `begin(ticketId, requestId, expectedSourceRevision)`：鎖ticket row，再鎖projection row；核對來源revision、分配PG generation+1，保存原命令並提交CLOSED。此時未呼叫Redis。相同UUID重送返回原命令，不重新分配；跨票種並發UUID碰撞由唯一鍵守門、loser交易回滾後返回衝突。
2. `complete(originalRequestId)`：同一ticket row lock保護快照核對至PG READY提交。舊generation／已被另一命令取代為GENERATION_FENCED；中間正式庫存變動為STOCK_CHANGED，不換UUID自動繼續。

Redis採用 `...:stock:{ticketUUID}:ledger:g:<PG generation>`，由PG分配key；D1單keyLua原語沿用。舊INIT只能碰舊epoch，不能覆寫新epoch。epoch模式禁止Lua ROTATE。此階段只建立空收據投影，baseline為PG capacity-held-sold、held/committed/entries皆0、limit4096。

PG CLOSED原命令且Redis缺key時，才依該已提交命令明確INIT／OPEN。READY後缺key、錯型別／TTL／非空台帳或不一致，不覆寫／釋放，持久標DIRTY；原命令不可重新初始化同epoch，新的維運命令須再分配PG epoch。

如果Redis OPEN後PG READY交易失敗，先前獨立提交的PG CLOSED仍在；原命令可重試同epoch、同快照，前提是source仍一致。Redis未知結果在CLOSED時保留CLOSED，原命令可核對；已READY的未知結果會標DIRTY，要求新epoch。PG提交未知須重讀原命令及gate，不能換UUID盲重建。

Redis準備在維運專用ticket row lock內做有界呼叫（目前connect／command各500ms），PG phase交易timeout10秒。方法禁止在已有外層transaction中呼叫，避免未提交phase被誤認持久或REQUIRES_NEW鎖死。這不是高吞吐admission路徑；不能由業務controller直接當新保票授權。

## 邊界與下一段

READY代表某個PG revision下的**空投影checkpoint**，沒有worker健康／預扣資格承諾；後续庫存變動可立刻使其DIRTY。inspect只讀PG狀態，不保證Redis可用；complete才做實際核對。本輪Redis key loss不會從generation1重新算起，但**不能因此宣稱所有orphan已解決**。

不同epoch不共用Redis收據；舊UUID重送必須優先讀PG durable command。M4D2B需要持久預扣intent／身份／quote／期限與決定證據，核對已成功Redis但未落PG、PG commit ACK遺失、rollback／晚到writer、取消與worker競爭；未知不盲目INCR或RELEASE。已有HELD收據在本服務被視為需要核對，不會抹掉，但本輪沒有允許忽略它們後對顧客放行新epoch的接入流程。

退休epoch沒有自動清理／歷史壓縮；多次維運會增加私有Redis keys與PG audit資料。舊epoch可能仍OPEN，後續入口必须在PG持久gate下fence舊writer，不能只信舊Redis phase。Cluster／多節點MQ／負載與跨主機HA仍待後續。

## 本輪驗證

StockProjectionIntegrationTest用真PG／Redis：原UUID並發、跨票種UUID競爭、stage間正式庫存變動、真ticket lock等待、不可變journal／command、失敗交易回滾、Redis OPEN→PG READY失敗、READY key loss／TTL／wrong type、舊INIT隔離、同步／非同步保票、付款與到期／取消。

V6保留production JAR建立既有pending／paid訂單與PROCESSING請求後，正式V7migration核對原DB rows未變、原stock精確backfill。production JAR程序另驗持久CLOSED後退出、Redis實際停機／重啟及原命令恢復、PG提交故障、logical key loss／new epoch与正式保票／取消失效。結果見HANDOFF及verification/stock-projection-m4d2a-2026-10-10.json。

`scripts/verify_stock_projection_invariants.sql`只讀查7項PG不變條件；它不核對所有Redis key或未接入的orphan台帳。

M4D2B1 已新增 V8 持久預扣 intent 與未使用 intent 的放棄決定／Lua 核對。未解決 intent 阻擋新 epoch，曾有 intent 的 epoch 不允許 maintenance complete 再初始化；詳見 [STOCK_HOLD_INTENTS](STOCK_HOLD_INTENTS.md)。沒有顧客 checkout／MQ worker 接入，也沒有正式 COMMIT 決定；這些留 M4D2B2。

M4D2B2 已新增 V9 immutable acceptance／decision／ack，內部 HELD 受理、正式 worker／取消／expiry 的 PG 決定與獨立 Redis 核對完成；B1 progress HELD 保留作歷史，已受理 intent 以正式 decision／ack 判斷是否解決。詳見 [STOCK_PURCHASE_DECISIONS](STOCK_PURCHASE_DECISIONS.md)。M4D2B3 已新增 V10 durable recovery queue／租約／有限 retry／自動排程與程序重啟補做，詳見 [STOCK_RECOVERY](STOCK_RECOVERY.md)。M4D2B4 已接 verified 顧客預扣 coordinator、原 UUID PG-only 進度 GET、預設關閉旗標與未知操作 UI，見 [STOCK_CHECKOUT](STOCK_CHECKOUT.md)。M4D2B5 已接員工維護頁與受權限保護的原 rebuild coordinator／phase audit，見 [STOCK_MAINTENANCE](STOCK_MAINTENANCE.md)；仍沒有自動重建或持續吞吐宣稱。

M4D2C1 完成 [投影增量同步契約與最小 COMMIT 實驗](STOCK_INCREMENTAL_SYNC.md)：test-only 同交易 provenance、sidecar 原UUID cursor／audit與真停機恢復；原 ledger／receipt不改，正式 gate仍DIRTY／FROZEN。沒有 production incremental writer、READY publication或持續吞吐。V1–V12不改、新schema僅實驗scope。

M4D2C2 完成 [正式庫存 revision 來源證據](STOCK_REVISION_ORIGINS.md)：V13 在原業務 PG 交易保存精確前後票種、原事件／accepted COMMIT 與 immutable audit；來源失敗共同 rollback。舊 history 不回填，只封存切換 boundary；直接 SQL revision 明確 UNATTRIBUTED。沒有 Redis incremental consumer 或 READY publication，原 gate 行為不變。

M4D2C3 完成 [多 revision 有界消費證明](STOCK_MULTI_REVISION_EXPERIMENT.md)：以正式 V13 來源逐筆核對原 accepted COMMIT、正式 RELEASE／PAYMENT，test-only immutable command／ack／audit 與原 UUID checkpoint 恢復。原 FROZEN ledger 唯讀，證據 available 不能用來新預扣；active holds、同步 HOLD、terms、缺證據、source／generation 變更仍 fence。未接 production consumer 或 READY。

M4D2C4 完成 [正式取消／到期返還的新epoch接棒](STOCK_RETURN_HANDOFF.md)：V14與production內部 StockReturnService，原 RELEASE／已ackCOMMIT作來源，原UUID保存完整PG snapshot與fresh CLOSEDgeneration；明確reconcile同交易READY／ack／audit後可用既有StockHoldService再次預扣。舊FROZENledger／COMMITTEDreceipt不改；沒有新增HTTP／UI／背景consumer，activeintents仍阻擋接棒，未解決live持續吞吐。
