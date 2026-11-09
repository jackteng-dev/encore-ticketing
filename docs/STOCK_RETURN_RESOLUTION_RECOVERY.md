# 原返還結案未知結果：永久停止與明確換綁（M4D2C7）

承接 [C6 結案](STOCK_RETURN_RESOLUTIONS.md)。原 C5 返還 UUID、BLOCKED progress／audit、原 RELEASE／COMMIT、舊 Redis receipt 不變。不新增返還 UUID、不補票、不重新初始化 key。新增 V17，V1–V16 bytes 不改。

## 為什麼不能只清掉 pending

GET 沒查到 ABSORBED 或 POST 回 409，不代表另一個原請求不會稍後 commit。管理員必須明確選擇「核對並永久停止原結案內容」：同一 PG transaction 先驗當前原 MANAGER，鎖原 return progress，再查正式 receipt。若原內容已 commit，就回原 ABSORBED；未 commit 時才保存 immutable NOT_APPLIED fence 與 exact audit。原 submit 使用同一把鎖且檢查 fence，包含 V17 的 SQL insert trigger，因此晚到原內容永久被拒絕。

attempt identity 是 `(原返還 UUID, 原 progress version, replacement snapshot UUID)`，沒有另外的返還 ID。Fence 保存整份 C6 Begin 的三個欄位、原 actor、時間。它只證明這份內容沒有結案；不宣稱正式返還未發生，不證明 Redis 健康。snapshot UUID 可沒有實體快照（原未知內容可能無效），不以猜測代替證據。

## API 與交易

路徑均為 `/api/admin/stock/returns/{原UUID}/resolution`，actor 來自已驗證 session。expected staff header、CSRF、嚴格 JSON 與既有 current role/session checks 適用。

- GET `/recovery?expectedVersion=…&replacementRequestId=…`：本人可讀 `UNKNOWN`／`FENCED`／`ABSORBED`。UNKNOWN 不能清 pending。FENCED 附完整 NOT_APPLIED fence；ABSORBED 附原 receipt 與成功換綁的 previousReplacementRequestId（普通 C6 為 null）。不同已提交內容且沒有這份 fence 時回 conflict。
- POST `/reconcile`：原 C6 Begin `{requestId,expectedVersion,replacementRequestId}`。原 current MANAGER 才可新建／重播核對；flag off、Redis down、active intent或已無 READY仍可核對。不能停止另一個已提交結案。Exact replay一份 fence、一筆 audit。
- POST `/rebind`：Begin 加 `previousReplacementRequestId`。同原 version、精確舊 fence、不同未 fenced 新內容、C6 current完整 READY／已提交發布／source／generation／零 unresolved checks。保存 REBOUND、audit及 C6 ABSORBED／audit在同一 TX。deferred constraint 禁止只 commit 換綁。任一檢查或 audit失敗全回滾；舊 fence不變。`TICKET_STOCK_RETURN_RESOLUTION_ENABLED` 必須 true。歷史 exact rebind POST在關旗標／Redis down仍回原 receipt，previous不同則 conflict。
- GET `/recovery-audit?page=1&size=12`：本人 bounded history，size≤20，page≤100000。原 VIEWER可讀，不能寫；跨 actor404。每項 NOT_APPLIED／REBOUND永久保留。

一旦有 fence，新 Begin POST不能繞過明確 rebind。新 body 的 fence也不能解除。GET歷史不要求目前 READY／原 manager權限／Redis可用。舊 fence在另一份結案成功後仍可讀；原 body晚到仍拒絕。

## 畫面與未知結果

送出前同 tab sessionStorage保存原UUID／version／snapshot／owner。錯誤、ACK loss、缺欄維持全站寫入 freeze。retry先GET完整 recovery，完整 exact receipt或 fence才解除；UNKNOWN才可原內容繼續，preview換快照不會自動更換 body。

明確核對會將 pending路徑改為 `/reconcile`，保留原 three-field identity與換綁時的 previous snapshot metadata；POST只送 Begin三欄。若原是rebind且已 commit，GET仍核對 previous binding。核對ACK未知時重新登入原帳號／reload後先GET，不能自行刪 sessionStorage。

取得永久 fence後，讀取原返還並檢查新的已發布快照。畫面顯示「明確換綁新快照並結案」。若現在仍同一份被停止的快照，先用既有維護流程發布另一世代；active intent必須先依原決定核對。每次失敗換綁的新 body也可依同樣流程永久停止，保留整段歷史。資料庫不得刪 fence或改原 command。

## 驗證與限制

新增13項真PG／Redis測試，包含原 submit vs reconcile競爭、四並發exact核對／換綁、audit回滾、direct SQL bypass／半提交拒絕、角色／owner／source／active fences、immutability。完整488項package／production build及11組實際HTTP／Chromium／Redis停機／V16→V17升級證據見 HANDOFF與[去秘密報告](verification/stock-return-resolution-recovery-m4d2c7-2026-10-11.json)。production harness：`scripts/run_stock_return_resolution_recovery.py`，使用自己新空PG／Redis、真V16 JAR及最終V17 JAR/dist；private配置與fixture留 work/，不下載瀏覽器或模型。

這是人工恢復閉環。沒有 scheduler／自動重試、跨主機保證、TCP DB commit ACK drop或公開部署；員工 session仍在單 API記憶體，重啟需原帳號登入。同 tab pending以外的持久紀錄可從本人原返還讀取。每頁 bounded audit；畫面目前只顯示第一頁。
