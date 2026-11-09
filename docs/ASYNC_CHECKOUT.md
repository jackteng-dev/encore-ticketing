# M4C 非同步購票畫面

免費自架，啟動API publisher與非HTTPworker依 [MQ_DELIVERY](MQ_DELIVERY.md)。`GET /api/public/events/{id}` 的 `purchaseRequestsEnabled` 只有published活動及 orders／purchases／delivery／publisher皆啟用才true；它是設定投影，不保證worker或broker即時健康。consumer可在另一程序，故不以API的consumer旗標決定此欄位。

未開啟有效 pre-debit 旗標時，啟用後checkout預設「先送出購票請求」，保留「直接確認保票」選項作同步比較；其餘設定保留原同步入口。兩者共用正式PG庫存／限購核心及同一個未知操作鎖。等候室若啟用仍需先取得資格；原已受理UUID重送不需要再取得permit。

## 顧客流程

會員「我的購票紀錄」在 `/account/purchases`。送出後202／PROCESSING只顯示處理中、原受理截止，**尚未保票，也沒有付款倒數**。本人頁每3秒讀取含PROCESSING的紀錄，讀取失敗至少3秒退避，429依Retry-After；不自動再次POST、不自動換UUID。到原期限只顯示等待伺服器核對，不能用瀏覽器時鐘自行標記EXPIRED或補票。

RESERVED顯示連結到本人orderId：`/account/orders?orderId=...#order-...`，訂單頁從受保護的本人detail API讀目標，因此歷史訂單不必恰好在第一頁。付款倒數只在正式PENDING_PAYMENT訂單頁顯示；RESERVED receipt不是訂單即時狀態。REJECTED／CANCELLED／EXPIRED顯示原因，不復活、不自動買第二筆。

PROCESSING可提交新取消UUID／expectedVersion；取消与worker競爭仍由PG決定。取消先成功則沒有order；worker先保票則原請求不能釋放訂單，讀最新結果後走原訂單取消。受理時原session若登出／撤銷，未完成請求可能REJECTED，已成功保票則不因receipt原session變更而改成取消。

## 未知回覆與帳號隔離

既有 `encore-reservation-pending-v1` sessionStorage key保留；customerCommands統一訂單／付款／購票請求的未知命令，pending只保存owner、path、原UUID/body，不保存cookie或密碼。POST前先保存；回覆遺失／不完整、401/403/408/429/5xx保留原操作，新的同步／非同步保票、取消／付款都暫停。手動重試只能原顧客送原path/body。收到明確其他4xx才釋放未知命令，再讀最新資料；讀取成功本身不能冒充取消command已ACK。

購票ACK驗證canonical IDs、原quote／quantity／request、期限、版本與狀態／orderId／closedAt一致性；不完整的成功JSON仍當未知。確認後的receipt依owner/id保存在有界100筆記憶體cache；較舊GET不能倒退已確認版本。ACK後GET失敗仍显示確認結果，只重讀，不重POST。換帳號／卸載頁面不把舊GET覆寫新畫面；cache不會展示給別的owner。

購票與訂單命令及歷史讀取加 `X-Expected-Customer-ID`。後端只將此可選header與已驗證principal比對；不作owner來源、不能授權或代替cookie／CSRF。即使另一分頁切cookie，舊畫面也不能把原UUID當另一會員的新請求。mismatch或無有效cookie為401，原pending保留。API不用此header的既有客戶端仍以principal授權；body注入owner仍400。登入可返回本人購票紀錄。

新受理／delivery關閉後，既有本人購票讀取與已知原UUID仍按原規則可用；前端不因旗標關閉丟掉unknown操作。正常頁面與訂單仍註明免費模擬，沒有真收款／出票。

## 驗收與邊界

scripts/smoke_purchases_browser.cjs的prepare／verify-worker／prepare-expiry／verify-expiry／race對正式JAR＋dist、真PG/MQ測試；維運在階段間控制自己的worker，而非公開假processing端點。race另驗真worker先保票後的取消409，以及頁面載入後實際切換cookie的寫入隔離。fixture/storageState只有private ignored work0600，不進git。browser攔截僅注入遺失ACK、401、不完整JSON、失敗／舊GET，真正接受／取消／保票仍經正式API與PG/MQ。worker跨期限演練不patch原deadline。

本輪結果見 [HANDOFF](HANDOFF.md) 與verification/async-checkout-m4c-2026-10-09.json。目前單機，沒有Lua票額預扣、cluster、queue-age監控、負載承諾、票券／驗票或真付款。歷史receipt維持安全投影，僅票種ID／原價格數量，尚無活動名稱快照；可在取得訂單後核對正式活動／場次名稱。關閉分頁後sessionStorage不保證保留，仍可登入讀PG本人紀錄；這不是跨裝置保存未知命令的功能。

M4D2B4 新增預設關閉的預扣方式／原 UUID PG-only 進度核對，共用原未知命令鎖。預扣 pending 對任何失敗（含4xx）都保留命令，只有有效正式 purchase／ABORTED解鎖；本文件前述其他4xx清除規則只適用原 PG command。詳見 [STOCK_CHECKOUT](STOCK_CHECKOUT.md)。
