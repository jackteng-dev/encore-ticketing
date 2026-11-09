# 原決定的人工 REVIEW 核對（M4D2B6）

員工 `/staff/stock` 的問題清單新增兩種原決定核對：未使用預扣的已提交 ABORTING／ABANDON 決定，與已正式受理的 immutable COMMIT／RELEASE 決定。這是另存紀錄的人工 domain reconcile；不改 REVIEW job，不重設 attempts／budget，不覆寫正式決定，也不建立訂單或重建 Redis 投影。

## 權限與啟用

`TICKET_STOCK_REVIEW_ENABLED=false` 預設，與維護、顧客預扣及 recovery 排程各自獨立。MANAGER＋staff Cookie／CSRF 才能提交；VIEWER 可讀設定／既有問題，僅原 actor 能讀本人人工 operation progress／audit。每個原 domain PG phase 先鎖 enabled staff row SHARE、重驗 MANAGER、鎖原 operation progress UPDATE，再依原 owner／ticket／purchase 或 event／progress 鎖順序執行。domain fence 在票種鎖取得後核對原 REVIEW attempt／decision／generation。

POST 和原 progress GET 的 `X-Expected-Staff-Username` 只核對實際登入，不提供 actor。角色改變使既有 staff cookie 失效；降為 VIEWER 的原 actor 重新登入後只能讀，canResume=false。旗標關閉不建立新 command；原 terminal 回放／progress GET 仍只讀 PG，不能接管別人的 UUID。

## API

所有路徑前綴 `/api`。GET 只查 PG，不觸發 Redis、重新核對或寫 domain；audit page 1..100000、size 1..20，預設20。

| 方法 | 路徑 | 行為 |
| --- | --- | --- |
| GET | `/admin/stock/review-settings` | 實際人工核對啟用旗標，員工可讀。 |
| POST | `/admin/stock/intents/{intentId}/reviews` | body：原 `requestId` UUID、`kind` UNUSED／SETTLEMENT、`expectedAttempt` 1..20、`expectedDecision` ABANDON／COMMIT／RELEASE。 |
| GET | `/admin/stock/reviews/{requestId}` | 原 actor 的人工操作進度；未知／他人404。 |
| GET | `/admin/stock/reviews/{requestId}/audit` | 本人原 command 的不可變 phase 歷史。 |

UUID 在 review command 表全域唯一，綁原 actor／intent／kind／attempt／decision；同 UUID/body 才能恢復，變內容／換 actor／換 intent 409。body 不接受 owner／claim／session／generation／補票數量。新 command 要求原 job 為 REVIEW 且 attempt 精確匹配；UNUSED 必須已 ABORTING、無 acceptance，SETTLEMENT 必須有原 immutable decision且未 ack。尚未 REVIEW 的 WAITING／LEASED、已解決或錯誤 expected decision 不能登記新人工工作。

Receipt 僅回原 request／intent／ticket／kind／attempt／decision、state／version／resolution／安全 failure、canResume／PG updatedAt，以及目前 intent state／ack；不回 customer／session hash／token／Redis receipt。REGISTERED／PENDING 回202，RESOLVED／BLOCKED回200，Location 指向原 UUID GET。canResume表示旗標與本人角色允許核對原 pending，不代表服務／ledger／source 一定符合。

## 原決定與同交易 audit

V12 新增 immutable `stock_review_command`、guarded `stock_review_progress`、immutable `stock_review_audit`。原 V1–V11 bytes/checksum 不改；不回填或改寫原 recovery job／claim／decision／ack／維護操作。

先獨立 commit REGISTERED＋audit version1。UNUSED 只呼叫既有 `reconcileAbandonment`：原 ABORTING 已提交，不新增放棄決定，不使用顧客原 session 建立新購票。原鎖下 CLOSE→原 claim LOOKUP／RELEASE→PG ABORTED，操作 RESOLVED＋audit 與 ABORTED 同交易。audit rollback 可留下 Lua RELEASED、PG ABORTING／REGISTERED；原 UUID 重試重新 LOOKUP 既有 receipt，不再補票。

SETTLEMENT 只呼叫既有 `StockSettlementService.reconcile`：第一 phase 驗正式 PG decision／purchase／order 證據，commit DIRTY＋operation PENDING／audit；第二 phase CLOSE→原 receipt COMMIT／RELEASE→PG ack，operation RESOLVED＋audit 與 ack 同交易。audit rollback 使 ack 與該 progress phase 一起 rollback，先前 PENDING 保留；Lua 已成功仍以原 receipt 恢復，不建新 order／decision。

RESOLVED resolution 必須有相同原 domain 的 durable ABORTED 或正式 ack 證據，DB trigger 守門；成功不等於恢復原 REVIEW job。原 job 完整列／attempt／claim 紀錄保持不可變；operational review_history 仍存在，但 ack／ABORTED 已解決的 case 不再計 unresolved，也不再阻擋新維護。需管理員**另行明確提出 B5 maintenance command**取得新 generation，本工具不自動重建／開售。

原 domain 的 ABSENT／KEY_MISSING 證據沿用其鎖、freeze與正式決定規則，不 INIT、INCR 或修改 PG 正式票額。未知 IO 保存 PENDING／REDIS_UNKNOWN，可用原 body 重試；corrupt／反向 receipt／generation或正式證據衝突保存 BLOCKED，仍未解除 intent。BLOCKED 是此人工操作的已確認結果，原 body terminal 回放只讀 PG；問題查明後若原 REVIEW 仍未解決，必須明確提出另一人工 command，不自動換 UUID或忽略失敗。

人工工具不修復損壞台帳、不刪 receipt、不重播 journal或直接接受顧客預扣。真實 corrupt／失配須另行調查與恢復可信原資料；沒有通用強制修復按鈕。核對未解決原 intent 的 generation mismatch 時，先在原 ticket lock 下拒絕 Redis mutation；已 domain resolved 的原 operation 可只憑 PG evidence 回放，不操作舊 epoch。

## 未知操作 UI

與活動管理、B5重建共用 `encore-catalog-pending-v1` freeze。POST 前保存原 staff／path／UUID／完整 body；重整後先 GET 原人工 UUID，只有本人404或 canResume=true才 POST **原body**。完整匹配 RESOLVED／BLOCKED 收據才解除freeze；REGISTERED／PENDING、HTTP401／403／409／429／5xx、malformed ACK與網路未知都保留。

verified GET 的 PENDING 不因下一個 POST 失敗而消失。寫入關閉時只GET、不換 UUID或刪 command；實際換帳號不能讀／接管。沒有自動 POST polling。sessionStorage 不存 Cookie／密碼；關整個分頁會丟失clientpending，目前沒有本人人工操作歷史列表／跨裝置恢復。PG 原 command／audit 仍在。

## 驗證與限制

StockReviewIntegrationTest 真 PG／Redis：unused／正式 COMMIT／RELEASE、六並發原 UUID、actor／body／attempt／decision／角色／旗標、SQL immutable／terminal／resolution proof、domain effect與audit rollback後原 receipt 恢復、key missing不初始化、corrupt／反向 receipt／generation fence、原成功後新 epoch 與PG-only replay、外層 transaction 拒絕。

`scripts/smoke_stock_review_browser.cjs` 分 prepare／offline／flag-off／resume，必須 loopback正式JAR＋dist與自有隔離fixture。`TICKET_RUNTIME_ENV`／`TICKET_REVIEW_FIXTURE`／`TICKET_REVIEW_OUTPUT` 必須 work/，PG容器參數 `TICKET_TEST_POSTGRES_CONTAINER` 只接受 `ticket-review-...-postgres-1`。operator 以 final-JAR 原 domain／worker 建立 REVIEW，故障復原fixture用**相同完整原 ledger snapshot**，不冒充通用生產修復功能；只有拋棄式資料可做 audit CHECK fault／Redis停機。

`verify_stock_review_invariants.sql` 是只讀PG八項證據核對，未解決 REVIEW 是 operational訊號而非integrity違規。實測counts／hashes／old upgrade preservation 見 HANDOFF與verification JSON。

Browser route.abort／移除 ACK 字段是 client模擬；CHECK是提交前PG rollback。未驗PG TCP commit ACK drop／Redis TCP成功回覆丟失、未登記receipt全ledger核對、Cluster／HA／吞吐。PENDING是人工可重試，沒有另一套自動處置queue／新budget。投影增量journal apply／自動協調、epoch GC／壓縮、票券／驗票仍待後續。
