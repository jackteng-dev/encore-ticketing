# 出票與驗票原請求核對／停止（M4E2）

免費自架、PG-only，沿用 M4E1 本人領票與 MANAGER 驗票。V20 新增兩種不可變 fence 與各自同交易 audit，V1–V19 bytes 不改；不回填歷史、不出票、不變更票券狀態、訂單、付款、庫存、revision、Redis 或 MQ。

## 使用流程

未知回覆／永久409仍保存原帳號、原 UUID、原 body；員工另保存原 ticket ID。其他寫入保持凍結，不能刪 sessionStorage 或盲換 UUID。

1. 「核對原出票／票券操作」只查原收據，不重新執行原操作。
2. `APPLIED`：已提交原操作，返回完整原 Bundle／Admission Receipt。驗證原內容及完整證據後清除 pending；不保存 fence。
3. `UNKNOWN`：尚未看到原成功收據，也沒有停止證據。這不是未成功的證明；保留 pending，可重試原操作。
4. 使用者可選「停止原請求」，再明確確認永久停止。服務會互斥核對原結果；若已提交，仍返回 `APPLIED`；若確實沒有原命令，在同一交易保存永久 fence＋audit，返回 `FENCED`。
5. `FENCED`：該 UUID 已永久停止，之後任何晚到／重送的原 UUID 都被拒絕。完整驗證 fence receipt 才清除 pending。停止 ACK 遺失時，以原 UUID／body 再核對或停止，不重開 UUID、不移除 fence。

解除後可以重新讀當前業務狀態，再由使用者明確發起新的合法操作；不自動生成下一筆出票／入場。停止不取消訂單、不退款、不撤回其他已成功入場或撤銷的操作。

## API

全部 POST，因核對 body 含原票券碼，不放 URL／query；`receipt` 沒有業務寫入，仍要求 CSRF、目前原身分和 strict JSON。

| 路徑 | 原 body | 權限 |
| --- | --- | --- |
| `/api/customer/orders/{orderId}/tickets/receipt` | 原 `Issue`：requestId、expectedVersion | 目前有效的本人顧客 session |
| `/api/customer/orders/{orderId}/tickets/stop` | 同上 | 同上 |
| `/api/admin/admissions/check-ins/receipt` | 原 `CheckIn`：requestId、code、expectedSessionId、expectedVersion | 目前有效的原 MANAGER |
| `/api/admin/admissions/check-ins/stop` | 同上 | 同上 |
| `/api/admin/admissions/revocations/receipt` | 原 `Revoke`：requestId、ticketId、expectedSessionId、expectedVersion、reason | 目前有效的原 MANAGER |
| `/api/admin/admissions/revocations/stop` | 同上 | 同上 |

前端沿用 `X-Expected-Customer-ID`／`X-Expected-Staff-Username`，寫入回覆另核對原 owner／actor、scope、version、action、expected ticket ID、reason、body fingerprint、state 及 fencedAt／完整原 receipt。UNKNOWN、不完整回覆、錯誤身分、衝突、5xx 全部保留 pending。VIEWER 不能查含原碼 body 的核對端點或停止；既有 metadata／audit GET 仍唯讀。

Issue recovery 回覆：`requestId, orderId, expectedVersion, bodyFingerprint, state, bundle, fencedAt`。
Admission recovery 回覆：`requestId, action, ticketId, expectedSessionId, expectedVersion, reason, bodyFingerprint, state, receipt, fencedAt`，沒有 bearer code。
`APPLIED` 只有完整 receipt、fencedAt=null；`FENCED` 只有有效 fencedAt、receipt=null；`UNKNOWN` 兩者皆 null。

fingerprint 為 SHA-256 UTF-8：`action + ':' + JSON.stringify(canonical original body)`。Issue action 為 `ISSUE:{orderId}`，欄位順序 requestId→expectedVersion；CHECK_IN 為 requestId→code→expectedSessionId→expectedVersion；REVOKE 為 requestId→ticketId→expectedSessionId→expectedVersion→reason。UUID 使用 canonical lowercase；不用原 storage 物件的欄位插入順序當依據。服務使用同 record 的 JSON serialization，前端以 WebCrypto 重新計算，不能只看到 FENCED 字串就清除 pending。

## 同交易互斥與永久拒絕

顧客沿用 current owner→session 加鎖及 DB clock 期限核對。fence 的 `(customer_id,request_id)` 共用既有顧客命令 namespace；保存前確認 order_command、purchase_command、stock_hold_intent 均無此編號。三者的 BEFORE INSERT guard 也先鎖 owner、拒絕已 fence UUID；stock intent 在 Redis 預扣之前落 PG，不能繞過停止證據。其他顧客可使用同 UUID；本人換 body／order／action 不得復活原 UUID。

驗票 UUID 原本全域唯一，所有正常 apply／receipt／stop 在 current staff shared lock 後，先取得原 UUID 的 PG transaction advisory lock，再核對 command／fence。DB BEFORE INSERT guard 也共用這個 lock；不同 UUID 不共用全站鎖，hash collision 只會多序列化、不削弱防重。原 command 存在時只讓原 actor／exact fingerprint 查原 receipt；其他 actor404、不同 body409。無 command 才能由目前 MANAGER 保存對已存在票券的原 body fence。

停止不要求當前業務 version／paid proof／票券仍 ACTIVE，也不要求 expectedSessionId 正確：這正是停止失敗／過時請求需要的行為。它仍要求原 order 本人／已存在票券及目前原存取權限；不是修改錯誤請求後再執行。新領票與驗票旗標為 false，或 Redis 停機時，仍能 lookup／stop；原新操作受原旗標限制。

fence＋audit 同 PG transaction，稽核失敗整筆 rollback，不產生「已停止」假證據。兩者不可 UPDATE／DELETE。fence 不存原 code，只存 fingerprint 與識別欄位；原 CHECK_IN pending 仍含 bearer code，僅同源同 tab 私有 storage，完成後清除，不能公開 fixture／截圖／cookie。

## 邊界與驗證

同 UUID 已有不同 body／其他 namespace 命令時409；其他 actor404、停用／降權帳號拒絕，不能藉停止接管。未知 code／不存在票券仍404；前端正常待確認操作源自已核對票券，若 private storage 毀損、expected ticket ID 不一致或證據不完整，繼續凍結而非放行。原身分需恢復後才能核對；沒有管理員代清或通用損壞修復。

18項新增真 PostgreSQL 整合測試，涵蓋 UNKNOWN 無效果、原成功收據、停止原內容、旗標 false／stale version、全顧客 namespace SQL guard、角色及 CSRF、兩種 audit rollback、永久 immutable proof、原 UUID 競爭與真正 uncommitted fence 下延遲 SQL lock／commit 後拒絕。原票券20項仍回歸。只讀核對 `scripts/verify_ticket_recovery_invariants.sql`，配合原 source 與票券 checks；它不能取代正式停止交易。

正式 JAR/dist／Chromium、真 V19→V20 升級與停止 ACK 遺失、API stop/restart／旗標false／Redis TCP down，由 `run_ticket_recovery_runtime.py`／`smoke_ticket_recovery_browser.cjs` 執行。工具存在不等於已驗收；本輪結果以 HANDOFF／verification JSON 為準。沒有真收款、退款、QR／相機、入場時間窗、公開部署、多API／HA或負載宣稱。

本輪正式验收已完成：真V19→V20保留58表／78筆資料、四新表零backfill；11組HTTP／browser、stop ACK遺失、原pending恢復、flags false／Redis TCP down、client錯誤證據保持freeze及六個apply／stop並發。20項invariants＋1 operational signal皆0，參閱[驗收JSON](verification/ticket-request-recovery-m4e2-2026-10-11.json)。
