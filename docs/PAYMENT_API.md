# M2C 免費模擬付款

這是本地持久模擬器，不接任何真金流、不輸入卡號、不收款；付款端點本身不出票，M4E1另由本人明確[領取票券](TICKETS_API.md)。`TICKET_MOCK_PAYMENTS_ENABLED=false`預設關閉，明確true及API重啟才允許新付款。關閉後仍可讀取／重放／核對既有付款。只用隔離合成資料。

## 合約

所有端點需顧客session；POST需顧客CSRF。owner只來自principal，別人訂單／attempt回404。不能提交provider event、owner、貨幣或provider金額，未知欄位400。後續正式adapter需獨立驗簽，不能把模擬端點當公開callback。

| POST 路徑（前綴/api/customer/orders） | body |
|---|---|
| `/{orderId}/payments` | `{requestId,expectedVersion,expectedTotalTwd,scenario}`；scenario為SUCCESS或DECLINED |
| `/{orderId}/payments/{attemptId}/reconcile` | `{requestId}`；讀取持久模擬receipt，不接受client付款結果 |

回覆本人Order目前狀態，新增`mockPaymentsEnabled`與nullable `payment:{id,requestId,status,reason,amountTwd,createdAt,processedAt}`；GET訂單同樣可核對。指令UUID命名空間與PLACE/CANCEL共用，fingerprint包含動作、order／attempt scope與完整body。相同UUID/body重放目前訂單，不建立新付款。每筆訂單最多一個attempt；拒絕後也不建立第二次付款，後續重試付款／退款政策另外實作。

## 狀態與票額

建立付款只寫入attempt(PENDING)、server金額與持久mock receipt，提升order version；不改held/sold。核對結果才執行：

- 期限內SUCCESS且TWD／金額精確吻合：PENDING_PAYMENT→PAID，held-q、sold+q，attempt SUCCEEDED。order PAID、票額轉換、唯一conversion ledger、provider event及command同交易。
- DECLINED：attempt DECLINED，未到期的order仍保票，可取消或待到期。不增加sold。
- 已取消／已到期／鎖後跨截止的SUCCESS：attempt REVIEW/LATE_SUCCESS，終態保持；尚未掃描的過期保票在核對交易內釋放EXPIRED，不重新佔票。
- 金額／貨幣不符：REVIEW/AMOUNT_MISMATCH，不轉sold；未到期保票仍持有至取消／截止，避免盲目補庫存。
- 另一個不同ID事件晚到：REVIEW/EXTRA_EVENT，既有PAID不重複轉sold；待未來人工核對／退款。同ID內容一致重放跳過，內容被修改則409 EVENT_CONFLICT，保留既有帳務，需營運核對。

PAID計入跨訂單限購，不能直接取消保票；回409 REFUND_REQUIRED，退款另做。訂單version隨付款建立／事件處理提升，終態不復活。REVIEW是付款狀態，並非可重新買票的order狀態。PAID也可能因額外事件有payment REVIEW，原已售票額保留。

鎖順序customer→session(shared)→event(shared)→ticket→order→attempt→receipt(shared)，鎖後DB clock重新核對身份／期限。背景expiry仍走同owner／ticket／order；不取得catalog_guard。所有變更及audit失敗整筆rollback，原指令可重試；沒有補回或exactly-once宣稱。

## 模擬器邊界

receipt在建立attempt同交易持久保存，人工按核對代表延遲處理；不是外部支付協定，也不模擬真正扣款的跨系統原子性。每次核對最多讀20筆receipt，正常端點只產生一筆；沒有公開事件注入API。額外事件／金額不符由隔離DB故障fixture驗證。PENDING付款須由顧客核對，尚無自動payment sweeper或人工REVIEW管理工具。

V4新增，不修改V1/V2/V3；DB FK約束order金額與owner／原指令、receipt/attempt/event/order關聯及唯一conversion。稽核表不刪除。退款、QR／實體驗票設備、真provider webhook／簽章、真支付unknown結果、人工核對作業仍列本地待辦。

## 顧客介面與未知回覆

本人`/account/orders`選模擬成功／拒絕，先建立付款再核對原付款；終態PENDING付款仍可核對，late success顯示REVIEW，PAID沒有普通取消按鈕。mock旗標false不顯示新付款表單，既有PENDING仍可核對。

與保票共用同分頁sessionStorage待確認指令；只存owner UUID/path/原request UUID/body，沒有token／密碼／卡號。付款ACK丟失、不完整、5xx或401/403均保留原指令，凍結新增保票／取消／付款；原顧客才能按「核對尚未確認的付款操作」。回覆須符合order ID、attempt/request ID／金額／合法狀態，才清pending。已確認PAYMENT version receipt不被延遲舊GET覆寫，後續GET失敗亦不重建付款；换帳號不呈現原收據。

loopback正式JAR驗收`scripts/smoke_payments.py`，Chromium＋production dist驗收`scripts/smoke_payments_browser.cjs`。HTTP支援prepare-restart／verify-restart：前者保存0600私有fixture，在保票到期前停API，確認port關閉；停機跨截止後以mock/新保票關閉、expiry開啟重啟，驗PAID持久保存、原attempt/receipt重放與late success REVIEW。原fixture期限不人工更改。Safe結果見verification，真支付unknown行為仍需正式adapter另驗。
