# 電子票券與一次驗票（M4E1）

免費自架、PostgreSQL持久票券。使用已存在的模擬付款成功證據，不接真金流／退款，不自動出票。V19新增五表及guard；V1–V18不改、不backfill。兩個新旗標預設false。

## 出票與票額

本人明確領取既有PAID訂單，須current enabled customer／session、精確order version，以及同原訂單的payment_attempt SUCCEEDED、payment_event SUCCEEDED與payment_inventory_event原轉售證據；金額／owner／ticket type／quantity全部吻合。PENDING／DECLINED／CANCELLED／EXPIRED／payment REVIEW拒絕新出票。PAID字串單獨不能證明可出票。

每order至多一個immutable issuance，按照原quantity一次建立1..quantity共1–10張票券；每張有獨立ID及SecureRandom 32 bytes、43字元base64url票券碼。header、所有units、原ISSUE command與issuance audit同PG transaction；deferred constraint拒絕缺票批次，失敗全部rollback。出票不改order version／庫存／revision／Redis／MQ，避免第二次轉售。原PAID仍計入限購。

顧客request UUID與既有訂單／購票／預扣命令共用防重邊界：ISSUE保存於order_command，fingerprint含動作、order scope與原body。exact replay返回原全部碼及目前票券狀態；原UUID改body／order／動作409。已出票的新UUID409 ISSUANCE_EXISTS，應讀取原票券，不發第二批。旗標關閉仍能read／exact replay既有issuance；每次仍驗目前原顧客身份與DB clock session期限。

## API

所有POST需對應CSRF、strict JSON。顧客owner僅principal；其他人的order404。staff actor僅principal，new check-in／revoke／inspect須current enabled MANAGER；VIEWER只能讀清單／settings／audit。原帳號變更會使舊session失效。可帶X-Expected-Customer-ID／X-Expected-Staff-Username防cookie切換；不能用它授權。

| 路徑 | body／回覆 |
|---|---|
| GET `/api/customer/orders/{orderId}/tickets` | 本人Bundle；未出票requestId／issuedAt null、tickets空陣列 |
| POST 同上 | `{requestId,expectedVersion}`；完整Bundle／原orderVersionAtIssue／原quantity |
| GET `/api/admin/admissions/settings` | `{enabled}` |
| GET `/api/admin/admissions/tickets?sessionId=...&page=1&size=12` | 指定場次metadata，不回票券碼／customer身份 |
| POST `/api/admin/admissions/inspect` | `{code,expectedSessionId}`；核對當下metadata／eligible，不保存入場效果 |
| POST `/api/admin/admissions/check-ins` | `{requestId,code,expectedSessionId,expectedVersion}`；Receipt原requestId／action／ticket |
| POST `/api/admin/admissions/revocations` | `{requestId,ticketId,expectedSessionId,expectedVersion,reason}`；reason=LOST_CODE／SECURITY／OPERATION_ERROR |
| GET `/api/admin/admissions/tickets/{ticketId}/audit?page=1&size=12` | durable action／reason／原request UUID／state／version；不回code |

分頁page1..100000／size1..20。票券碼只在本人Bundle內回傳，或由MANAGER於POST body提交驗票；不放URL／列表／staff receipt／audit。不提供匿名驗票。

## 狀態與並發

每張ACTIVE version1只能到CHECKED_IN或REVOKED version2，原code／unit／order不可改、不可delete／reset／再發。驗票須正確場次與原code、目前完整paid proof、精確版本；inspection的eligible只是當下資訊，真正寫入會再次加鎖核對。付款追加事件導致REVIEW或customer停用後，原票券與歷史保留，暫停新入場；不自動將歷史已入場票券退回ACTIVE。

撤銷只作用於未使用票券，由MANAGER明確給原ticket ID／場次／版本與有界reason；付款REVIEW仍可撤銷。已使用票券409；撤銷不退款、不改sold、不補庫存，碼不能再使用。沒有換碼／轉讓／重入場／undo端點。

staff先鎖自己的帳號shared與原request UUID的PG transaction advisory lock，再customer shared→order shared→attempt shared→個別issued_ticket update；與付款／出票customer→session→order→attempt順序一致。最後鎖後重新讀state與paid proof。兩個不同UUID競爭同票，只一個terminal effect，其餘409；同UUID／body重放原receipt，不二次入場。admission UUID使用獨立全域namespace，綁actor／action／原body fingerprint；其他actor不能接管，CHECK_IN不能改為REVOKE。

command、ticket transition與immutable admission audit同TX，deferred guard拒絕沒有票券效果／audit的command。audit CHECK故障全部rollback，原request可精確重試。不能推論跨Redis／PG exactly-once。

## 旗標與畫面

`TICKET_ISSUANCE_ENABLED=false`控制新領取；`TICKET_CHECK_IN_ENABLED=false`控制新驗票及撤銷。既有read／exact replay可用；首次出票另外需要既有模擬付款流程，並非開旗標就自動產生票券。重啟API才讀取新設定。

本人訂單→查看電子票券→領取／重讀。員工 `/staff/admissions` 選活動與場次、核對票券碼、確認入場；VIEWER沒有核對碼或寫入按鈕，仍能看場次metadata／歷史。清單選票只能撤銷，不能跳過驗碼入場。

出票與所有顧客寫入共用原pending；原reply未知、缺欄、401／403／409／5xx均保留原body，凍結新寫入，不盲換UUID。員工同樣共用全站pending，保存原actor／body／expected ticket ID，驗完整receipt後才清除；重整由原帳號核對。M4E2已新增[原請求核對與明確永久停止](TICKET_REQUEST_RECOVERY.md)：UNKNOWN仍保留pending，只有APPLIED完整receipt或FENCED精確停止證據才能解除；不能自行刪storage或換UUID繞過。原CHECK_IN pending含入場碼，僅保存在同源同tab sessionStorage並在確認後清除；不要公開原fixture、瀏覽器storage或截圖。

碼為bearer憑證，為了本人PG持久重讀，目前以可讀欄位保存在DB；備份與DB存取須受控，不應記錄完整request body。正式公開前再補靜態加密／輪替策略與日誌遮罩、登入限流、TLS；這輪沒有公網部署。[QR展示與掃描入口](TICKET_QR.md)已在 M4E3 接上；實體設備、入場時間窗、活動取消與退款、付款人工REVIEW管理及換碼政策另做。活動ARCHIVED表示隱藏catalog，尚不是正式取消，不自動取消已付票券。

## 驗證

20項真PG整合測試涵蓋完整batch／immutable／secure code格式、原指令／並發出票、不同UUID驗票與撤銷競爭、來源／角色／session期限、audit rollback、deferred部分出票拒絕及HTTP CSRF／strict JSON／expected identity。實際package／build／正式JAR／browser與重啟結果以HANDOFF及verification為準；工具存在不等於已執行。只讀核對：`scripts/verify_ticket_invariants.sql`。

本輪已完成真V18→V19 upgrade（53表／109筆原資料不變、新表零backfill）、四phase／10組正式HTTP／browser；真API stop/restart、旗標false原指令核對、Redis TCP實際關閉仍可PG-only出票歷史／驗票，六並發只一成功、稽核與原碼持久。16項invariants＋unattributed signal全0，最終四張票兩入場／兩撤銷，票額及revision不改。詳見 [驗收JSON](verification/tickets-m4e1-2026-10-11.json)；沒有QR、現場設備、時間窗、負載／HA或真金流宣稱。

M4E3 為前端延伸，API／V1–V20 不變；原 ACTIVE／eligible code 產生本地 QR，MANAGER 掃描只填碼再明確 inspect／CHECK_IN。實體設備與入場時間窗仍未驗收／實作，見 [TICKET_QR](TICKET_QR.md)。

M4E4 起新增 [場次入場時間窗](ADMISSION_WINDOWS.md)：未設定拒絕新增 CHECK_IN，鎖後 DB clock與最新policy同交易判定；Receipt新增windowProof，歷史分類／REVOKE明確null，原新CHECK_IN必須有proof。原成功 replay／receipt／stop與REVOKE不受當前關窗限制；eligible仍僅付款／帳號判斷。
