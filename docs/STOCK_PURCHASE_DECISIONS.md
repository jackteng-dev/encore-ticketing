# 預扣、持久購票與正式決定（M4D2B2）

內部 `PurchaseService.acceptHold` 已接原 HELD intent→purchase_request／command／outbox→RabbitMQ→共用正式 PG worker；取消／逾時／拒絕與正式保票同交易保存 Redis 決定。`StockSettlementService` 在另一組已提交 phase 核對 COMMIT／RELEASE。

**M4D2B4 顧客 coordinator 已呼叫 acceptHold，預設關閉入口與原 UUID 進度見 [STOCK_CHECKOUT](STOCK_CHECKOUT.md)；M4D2B3 內部核對排程見 [STOCK_RECOVERY](STOCK_RECOVERY.md)。** 既有 PG 受理／保票比較入口保留；內部 HELD 不等於待付款。正式 PG 訂單成立後才可顯示保票期限。不可直接從 HTTP／broker body 指定 owner 或 Redis claim。

## V9 持久鏈結與證據

| 表 | 用途 |
|---|---|
| stock_hold_acceptance | 一 intent／一 purchase 的不可變鏈結，FK 指向已存在的 request 與 outbox |
| stock_hold_decision | 一 intent 的不可變 COMMIT／RELEASE、原 purchase 終態、order／reason |
| stock_hold_ack | 原決定的不可變 Redis 觀察證據：COMMITTED／RELEASED／KEY_MISSING／ABSENT |

V1–V8 不變，舊表／舊意圖不改資料、不自動建立 acceptance。INSERT acceptance trigger 要求原 progress HELD，以及 owner／原 UUID／ticket／quantity／quote／session／deadline 完全相同。已鏈結 purchase 的身份、期限與終態受 guard 保護；其原 V8 progress 保持 **HELD 歷史觀察**，不走「未使用 intent 放棄」。是否未解決由 acceptance／decision／ack 判定，不單看 progress.state。

正式 purchase 從 PROCESSING 轉 RESERVED／REJECTED／CANCELLED／EXPIRED 時，AFTER UPDATE trigger 在原 transaction 建立決定。RESERVED→COMMIT 必須匹配原正式 order／庫存 HOLD 事件；其他終態→RELEASE 必須沒有相同本人／原 UUID 的正式訂單。決定寫入失敗，order／stock／journal／audit／purchase 結果一起回滾，worker 不 ACK 成功。既有未鏈結購票不產生這些決定。

## 受理與 worker

內部受理前，先透過 B1 `prepare(verifiedPrincipal, originalPlace)` 保存意圖，再 `apply(originalIntentId)` 核對 HELD。`acceptHold(verifiedPrincipal, originalIntentId)` 的 body／期限只取不可變 PG intent，驗本人與原 session、PG READY generation/revision、sales／quote、受理上限／waiting-room eligibility，並 LOOKUP 原 HELD receipt。新受理不能只信舊 PG HELD 而忽略 Redis 缺失或終態變更。

接受後，request＋原 SUBMIT command＋outbox＋acceptance 一起提交，使用原 intent deadline，不重算等待時間。PG failure 留原 HELD intent，沒有半筆 request／link／outbox；用原 id 重試或按未使用核對流程處理。已提交 acceptance 先 replay，無需 Redis、原入場资格或開啟新購票旗標；原 owner/UUID/body 的既有 submit 路徑也可核對同一持久結果。

受理是 PROCESSING，沒有正式 PG hold。RabbitMQ publish／retry／consumer 的 immutable event identity 沿用 M4B。worker 不依賴 Redis：原 accepted request 的 session／期限／sales／quote、正式容量、限購仍由 PG 核心最後裁定。即使預扣後其他同步訂單已消耗容量，worker 也會拒絕並保存 RELEASE，不超賣。

取消／expiry／worker 都沿用 owner lock 與 purchase row lock，一個終態、一個決定；晚到 worker 讀終態直接 duplicate，不復活。RabbitMQ ACK 在 PG 代理 transaction commit 之後，已存決定不代表 Redis 核對完成。consumer 不等待 Redis；V10 同交易建立恢復任務，由独立 recovery worker 核對。

## 獨立核對與重建

`StockSettlementService.reconcile(originalIntentId)` 沒有 endpoint，由 B3 持久任務觸發；禁止外層未提交 transaction：

1. 讀不可變 decision，以 owner→ticket→purchase locks 重驗正式 purchase／order 證據。已有 ack 直接 replay，允許其後的新 generation，且不改新門。
2. 無 ack 時要求同 PG generation，**先獨立提交 DIRTY gate**，尚未送 Redis。禁止新官方 HOLD／fresh acceptance。
3. 下一個 transaction 重讀證據／ack，CLOSE 原 epoch，永久 FROZEN；再 LOOKUP 原 claim。
4. HELD 或符合目標的既有終態才可 COMMIT／RELEASE。反向終態／corrupt／未知保留 PENDING，不逆轉、不 INCR。成功後保存 ack；ack 寫入失败仍保留先前已提交的 DIRTY 與正式決定。

整 key 缺失記 KEY_MISSING；凍結後原 receipt 不存在記 ABSENT，不建立新 HOLD 或 ghost receipt，也不重 seed 同 epoch。這是投影資料遺失的證據，**不是已找回 Redis 收據**。正式 PG 訂單／取消結果已存在，原 writer 已被終態／generation／DIRTY 門 fence；所有 intent 解決後，新 PG generation 依當時正式 capacity-held-sold 重建。

正式訂單後來取消／到期／付款，不改原 purchase COMMIT 決定：那是當時保票的不可變歷史。Redis 原 epoch 凍結、COMMITTED tombstone 留存；當前可售量由最新 PG stock 重建，不把舊 COMMITTED 再 RELEASE。

StockProjection.begin 只在所有 intent 已 ABORTED（未使用）或已有正式 ack（已受理）時分配新 generation；complete 仍禁止任何曾有 intent 的同 epoch 重初始化。ack 後原 intent 不再計入未解決預扣限額，正式 PENDING_PAYMENT／PAID 訂單仍計入限購。原 UUID 永久 replay，不跨 epoch 新扣。

## 故障與實測範圍

| 窗口 | 恢復來源 |
|---|---|
| Redis HELD、acceptance transaction 失敗 | 原 immutable intent＋HELD 收據；request／command／outbox／link 整筆回滾 |
| acceptance 已提交、回覆未知／Redis 失聯 | 原 link／SUBMIT command replay；不重新扣票／延長 deadline |
| worker 正式保票／決定寫入失败 | 全部 PG 回滾，PROCESSING 留存，MQ有限重試 |
| worker commit 後 ACK 失敗 | 同 immutable event redelivery，只一 order／HOLD／decision |
| Redis 決定成功、PG ack 記錄失敗 | 已提交 decision＋DIRTY；新程序重讀原 Redis 終態，不重複補票 |
| Redis 投影遺失／相反终态／corrupt | 資料遺失可按正式 PG 決定與凍結證據 ack；相反終態／corrupt 留 PENDING 阻擋重建 |

新增26個真PG/Redis測試、2個真RabbitMQ鏈結與 commit-before-ACK redelivery測試；正式JAR独立publisher／worker與Redis真停機、新程序COMMIT／RELEASE記錄故障恢復、V8→V9資料保留见HANDOFF与verification。未注入PG TCP commit ACK丟失，不能把SQL constraint回滾稱作該網路故障。`verify_stock_purchase_invariants.sql`的10項只讀PG核對，不是所有Redis key即時一致性或HA證據。

## 邊界與下一步

仍在 PG ticket lock 內做受理 LOOKUP／核對 Lua（connect/command 各500ms、settlement phase TX timeout10秒），一筆核對凍結整個epoch；沒有增量 journal apply 或吞吐保證。B3 已新增 durable task claim／排程；仍沒有顧客預扣 coordinator／啟用旗標／未知 intent UI、未登記 receipt 全台帳掃描、epoch GC、Redis Cluster／跨主機HA。

B3 持久核對工作領取／bounded retry／重啟補做與 unused intent recovery 已完成，見 STOCK_RECOVERY。下一步串 verified顧客入口 coordinator、原UUID未知進度讀取、預設關閉旗標及UI。不能直接把acceptHold接controller後宣稱故障恢復完整。正式付款／出票／驗票、集群與壓測依ROADMAP逐段完成。
