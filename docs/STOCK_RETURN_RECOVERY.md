# 原返還的有界背景恢復（M4D2C8）

承接 [C5 員工返還](STOCK_RETURN_STAFF.md) 及 [C7 未知結案核對](STOCK_RETURN_RESOLUTION_RECOVERY.md)。V18 新增返還 recovery job、claim、audit；V1–V17不改。C8 本身沒有 backfill、自動發現所有取消、新返還 UUID、改 body／選快照、C6 ABSORBED或C7 fence／rebind。

## 明確授權與原內容

原 current MANAGER 明確 POST `/api/admin/stock/returns/{原UUID}/recovery-job`，body與C5相同：`{requestId,expectedReleaseRevision}`。只有既有REGISTERED／CLOSED能新登記，原 command的actor／ticket／order／revision供應後續工作；無論ACK未知或背景完成，原 body都不可改。job／原actor驗證／原progress鎖／enrollment audit同TX；失敗整筆rollback。已登記 exact replay先於旗標／原終態檢查，不新增budget；寫入仍需原currentMANAGER。跨manager404／VIEWER403。

GET同路徑與`/audit?page=1&size=12`為本人 PG-only歷史，owner VIEWER可讀，size≤20／page≤100000；沒有把lease token回傳或提供外部claim／run API。GET `/api/admin/stock/return-recovery-settings`顯示目前旗標。unknown enrollment重試先GET同原UUID；UNKNOWN原return結果仍需本人原操作核對，queue WAITING不等於返還完成。

## 預算、租約與停止

job固定六次，不能修改／reset／刪除。WAITING→LEASED→DONE／WAITING／REVIEW，每次領取耗一次，程序死亡也耗。PG clock與`FOR UPDATE SKIP LOCKED`領取due工作、每次新token／attempt／lease及不可變claim與audit一起commit；live lease不可接手。逾期第六次轉LEASE_EXHAUSTED REVIEW，不出現第七次。

未知Redis IO／PG_FAILURE／INTERNAL_FAILURE有限退避1、2、4、8、16秒，最後REVIEW。source／generation／active／正式證據衝突，原C5會BLOCKED，job RETURN_BLOCKED REVIEW。原帳號停用／降權→ACTOR_FORBIDDEN REVIEW，原return不被冒名續做。C5寫入旗標關閉→RETURN_DISABLED REVIEW。不自動處置人工終態、增補票額或改snapshots。job的REVIEW是保留的嘗試歷史；原MANAGER可以走原C5／C6／C7核對途徑，但不重開budget。

DONE SQL guard要求同原UUID的C5 APPLIED與C4 ack；只確認job終態不會建立域ack。原C5 callback在每個獨立PG phase依actor→original progress→job lock順序檢查token／attempt／PG lease clock；Redis前及之後保存PG結果前都檢查。租約不能取消已送出的Redis指令；過期／rollback保留原immutable返還command及同世代冪等ledger供下一次核對，PG CLOSED gate先持久化。不宣稱跨Redis／PG exactly-once。

## 啟用與畫面

設定需重啟API／未來worker，同一業務程式共用：

| 設定 | 預設 | 範圍 |
|---|---|---|
| TICKET_STOCK_RETURN_RECOVERY_ENABLED | false | 明確enrollment及排程開關；關閉仍能讀／exact replay既有job |
| TICKET_STOCK_RETURN_RECOVERY_LEASE_SECONDS | 30 | 5–120秒 |
| TICKET_STOCK_RETURN_RECOVERY_BATCH | 4 | 1–20筆，每輪逐筆 |
| TICKET_STOCK_RETURN_RECOVERY_POLL_MS | 1000 | fixed delay，啟動初次延遲5秒 |

續做需要`TICKET_STOCK_RETURN_ENABLED=true`及原account currentMANAGER。不開啟顧客predebit、其他stock recovery、MQ、C6結案或C7rebind。員工 session 已於 M5A 改為共享 PG；背景 job 與 claim 也在 PG，API重啟不重設attempt。

員工原return pending新增「將原返還交給背景恢復」。送前保留原pending，GET／POST完整校驗job原UUID／revision／budget／version／state，ACK loss／缺欄仍保留原body及全站freeze。重整後原job GET可核對，背景DONE仍由原C5 APPLIED完整receipt解除pending；不把enrollment回覆當成功補票。可讀job／bounded audit，REVIEW顯示需人工核對。

## 驗證與後續

14項真PG／Redis integration tests：REGISTERED／CLOSED續做、原epoch／source／full HASH不變、role／source停止、fault rollback與退避、並發claim、stale lease、Redis後lease到期PG rollback、六次crash預算、immutable history／DONE proof／外層TX拒絕。完整package／frontend build／production結果見HANDOFF。

正式驗收工具`run_stock_return_worker.py`與`smoke_stock_return_worker_browser.cjs`只接受自己新空PG／Redis／loopback／privatework及真正舊V17 artifact。測試helper不打包；不下载SDK／瀏覽器／模型。原業務資料upgrade不變、實際scheduler／Redis停機／same-AOF／claimed phase被PG lock擋住時stop API後續做；本輪真V17→V18的50表／226筆舊資料不變，六phase／11組正式browser通過，38項readonly invariants違反0；程序stop後逾期lease以原UUID／attempt2續做，三DONE／兩REVIEW，五g1／三g2完整HASH不變。詳見 [驗收JSON](verification/stock-return-worker-m4d2c8-2026-10-11.json) 與HANDOFF；初跑兩次prepare工具問題及修正都有記錄。只讀`verify_stock_return_recovery_invariants.sql`核對原body、ack、budget／claim、exact audit。

M5D [有限未來來源發現](STOCK_RETURN_DISCOVERY.md) 已接入：只有新授權的來源會同交易建立 C5 原 command/C8 job；STOPPED／discovery 旗標在這類 job 每個 domain phase 額外 fence，EXHAUSTED 仍可完成原 job。M5A/B/C 多 API／Worker及 M4E 票券已另驗證；持續吞吐／跨主機與觀測／壓測仍待做。這一輪是原員工操作的恢復，不代替完整取消／expiry自動consumer。
