# M4D1 Redis 預扣台帳基礎

M4D1完成可獨立驗證的Lua原語與Java adapter；目前M4D2A另增 [PG持久generation與重建底座](STOCK_PROJECTION.md)，**顧客預扣已接 B4 預設關閉入口；內部正式 worker／持久核對與自動 recovery 已接 B2／B3**。RedisTicketLedger不是Spring bean，沒有HTTP endpoint、排程或啟用旗標；既有同步及非同步流程保持PG正式票額。

## 台帳與不變條件

每票種一個 `encore-demo-...:stock:{ticketUUID}:ledger` hash；namespace／ticket 有界驗證，單 key 天然 same-slot。這輪只測單 Redis 與 slot 計算，尚未跑 Redis Cluster。hash 保存8個metadata欄位及 `r:requestUUID` 收據；每次扣／決定以一個 HSET 同時寫計數與收據，沒有先 DECR 後補收據的窗口。Lua 不等於 SQL rollback；異常／transport失敗仍是 UNKNOWN。

`baseline = available + held + committed`，全部為非負整数。baseline是上層提供的該generation可售快照，**不是活動總capacity**；已由PG held／sold的數量不應再算入。entries≤limit≤4096，HLEN=8+entries；收據一直保留，不TTL／不逐出。滿載拒絕新請求，原UUID仍能核對。這是有界正確性原型，尚無安全歷史壓縮，因此不能拿4096當正式營運容量。

收據綁generation、原requestId、已驗證owner、quantity、deadline及64字元fingerprint；上層須從principal與原命令建立fingerprint，包含session／ticket／quote。Java／Lua雙重驗型別、範圍與canonical IDs。原UUID改owner／數量／期限／fingerprint為CONFLICT，不扣、不補。腳本只O(1)讀metadata及本筆收據，檢查key type、PTTL、計數守恆／cardinality及本筆JSON，**不是全台帳總和審計**；Redis私有ACL與後續PG核對仍必要。

Redis TIME決定新HOLD的接受時點，期限必須在未來且不超過120秒；重送不延長原期限。PG與Redis仍需要時鐘偏差政策，不能把Redis時間當正式帳務時鐘。

## 狀態與重送

| 操作 | 條件與結果 |
|---|---|
| INIT | 只對空key明確建立，初始CLOSED。已存在同generation／baseline／limit則只核對，不重置計數、不開門。不同參數CONFLICT。 |
| OPEN | CLOSED→OPEN；相同OPEN可重送。FROZEN無法重開。 |
| CLOSE | 當前generation→FROZEN，可重送；晚到的OPEN不能取消凍結。 |
| HOLD | 新請求只在OPEN、有效期限、有餘額與台帳容量時available→held。原UUID精確重送回既有收據，即使已關門／過期／terminal，不再次扣。 |
| LOOKUP | 只核對同generation的原身份與payload，不建立請求。 |
| COMMIT | HELD→COMMITTED，held→committed，available不變。只能由上層確認原PG保票已commit後呼叫；期限已過／門已關仍可處理既有結果。 |
| RELEASE | HELD→RELEASED，held→available，只能由上層確認無PG保票且已fence晚到writer後呼叫。相同決定可重送，與COMMIT競爭只有一個terminal結果。 |
| ROTATE | CLOSED／FROZEN且held=0才可切到generation+1，重設上層已核對的baseline、保持CLOSED；保留全部舊收據。舊generation全部FENCED，原UUID換generation仍CONFLICT。 |

COMMITTED不能直接RELEASE；PG訂單取消／付款／到期／未來退款必須有各自持久唯一事件與順序，不能用原HOLD釋放重複補票。ROTATE回覆遺失不能換expectedGeneration盲重送，須讀回並由持久coordinator核對。

期限經過不會自行變RELEASED，也不會刪key。Redis失聯／回覆無法解析，Java拋Unavailable，不重試、不補票。key缺失MISSING、錯型別／TTL／可偵測的結構錯誤CORRUPT，操作不自動INIT。邏輯key loss與AOF容器恢復分開測。

## 必須完成後才能接購票

M4D2A已落實PG epoch／重建門狀態与庫存revision底座；M4D2B仍需預扣intent／核對證據及業務接入，正確性至少涵蓋：

- 初始化／恢復先關新入場與預扣，PG與Redis同時fence舊writer；key消失後generation必須來自PG，不能從Redis重新從1算起。此原語的INIT無法自行防止全key遺失後ABA。
- 原UUID查PG既有command優先；Redis成功但PG未落、PG commit回覆遺失、rollback與晚到worker，各有持久狀態可辨識，未知不能直接RELEASE。
- 同步保票、非同步保票、取消／到期、paid／未來退款都進同一協調機制。要證明baseline快照取樣至開門之間無漏接的正式庫存變動；CLOSE一個Redis hash本身不能阻止PG writer。
- COMMIT／RELEASE只能由原PG結果核對而來；舊session撤銷、原deadline、限購與正式可售仍由既有PG核心決定。沒有借訊息payload授權owner。
- 有限批次claim／fencing／補償原ID重送、凍結恢復、generation輪替及歷史安全壓縮；不得先放流再SET覆寫counter。

這輪未新增schema，未改V1–V6，未宣稱Redis→PG原子交易、exactly-once、HA或負載能力。

## 驗證與重現

完整Maven package中的InventoryLedgerIntegrationTest使用真隔離Redis與PG環境，測最後票額並發、同UUID重送、payload綁定、COMMIT／RELEASE競爭、真實時間跨deadline、不可重開generation、邏輯遺失、TTL／錯型別／錯JSON／錯計數及有界台帳；不使用H2。既有測試仍全跑。

`scripts/smoke_inventory_ledger.py`從最終production JAR取Lua bytes，在自己的Docker Redis執行；要求私有env的project為ticket-lua-foundation-*，檢查container label，秘密用REDISCLI_AUTH環境傳遞。prepare保存work內0600 fixture並取得local WAITAOF；維運**在原30秒deadline前實際停止自己的Redis、確認port关闭、停機跨過期限，再啟動相同volume**後verify-restart。這是正常停機後AOF恢復，不是kill -9、主從故障或跨主機備援。

```bash
python scripts/smoke_inventory_ledger.py --env work/你的隔離scope/runtime.env --phase prepare --fixture work/你的隔離scope/ledger-private.json --output work/你的隔離scope/ledger-prepare.json
# 只停止／重啟此scope自己的Redis，不能動別的作品；不要重建volume。
python scripts/smoke_inventory_ledger.py --env work/你的隔離scope/runtime.env --phase verify-restart --fixture work/你的隔離scope/ledger-private.json --output work/你的隔離scope/ledger-restart.json
```

恢復後核對原HELD／計數完整、逾時未自動補票、原UUID不重扣、frozen gate、generation fencing及控制性key loss。腳本中的RELEASE是合成原語測試，**不是已實作PG orphan reconciliation**。詳細本輪證據與清理結果見HANDOFF與verification/redis-ledger-m4d1-2026-10-10.json。

M4D2B1 的 StockHoldService 已以 V8 持久 intent 包裝 epoch ledger，先保存 verified identity／原命令，再在 PG ticket lock 下 HOLD；未使用 intent 先提交放棄決定，再凍結／核對 RELEASE，見 [STOCK_HOLD_INTENTS](STOCK_HOLD_INTENTS.md)。原語本身仍不能判定正式訂單，顧客與 worker 尚未使用此預扣路徑。

M4D2B2 已新增 V9 immutable acceptance／decision／ack，內部 HELD 受理、正式 worker／取消／expiry 的 PG 決定與獨立 Redis 核對完成；B1 progress HELD 保留作歷史，已受理 intent 以正式 decision／ack 判斷是否解決。詳見 [STOCK_PURCHASE_DECISIONS](STOCK_PURCHASE_DECISIONS.md)。M4D2B3 已新增 V10 durable recovery queue／租約／有限 retry／自動排程與程序重啟補做，詳見 [STOCK_RECOVERY](STOCK_RECOVERY.md)。M4D2B4 已接 verified 顧客預扣 coordinator、原 UUID PG-only 進度 GET、預設關閉旗標與未知操作 UI，見 [STOCK_CHECKOUT](STOCK_CHECKOUT.md)。投影仍需內部 READY maintenance，沒有自動重建或持續吞吐宣稱。
