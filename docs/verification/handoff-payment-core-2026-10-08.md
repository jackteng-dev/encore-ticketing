# Encore 售票平台交接

更新：2026-10-08（Asia/Taipei）。起點為遠端main `6bbfae239b2640dc97d61bb76fcfa96ac8dea8ea`；本次先fetch、讀遠端HANDOFF／AGENTS／README、核對乾淨status/log/diff並ff-only。只操作ticket-platform。M2B交接保存verification/handoff-m2b-2026-10-08.md，歷史證據保留。

## 本次完成內容
- M2C第一個可驗證里程碑：免費持久mock付款後端。V4擴充order PAID、付款attempt、持久mock receipt、去重event及唯一held→sold conversion ledger；V1/V2/V3 bytes不變。
- 本人PAY／RECONCILE API，獨立顧客CSRF、交易內身份／金額／版本／期限核對，共用PLACE/CANCEL UUID namespace。每訂單限一attempt，replay回目前order，不重新付款。
- 正常期限內成功轉sold；PAID計入限購並拒絕普通取消。拒絕不轉sold。晚到成功payment REVIEW，已cancel/expired不復活；尚未釋放的過期保票同交易EXPIRED。金額／貨幣不符、另一ID額外事件待核對，不雙重售出。
- 付款狀態與訂單票額狀態分開；Order讀取新增payment及mockPaymentsEnabled，version涵蓋付款變更。default mock旗標false，關閉後既有指令／結果仍可核對。沒有真收款／卡號／出票。

## 重要修改檔案
V4__mock_payments.sql；orders/PaymentModels、PaymentService、OrderModels、OrderService、OrderController；application/.env.example；PaymentIntegrationTest、原三組tests隔離mock旗標及schema版本核對；PAYMENT_API、README、ROADMAP、本交接。

## 測試／build 結果
- 完整JDK21/Maven package，真PostgreSQL17自有scope ticket-payments-20261008 / ticket_demo；88項（付款26＋既有62），0失敗／error／skip。frontend vue-tsc＋Vite production build成功（此checkpoint介面仍M2B）。實際結果以verification/payment-core-m2c-2026-10-08.json為準。
- 付款6並發重送／6不同核對指令同event、UUID/body/action衝突、本人隔離、quote、拒絕、PAID限購與拒取消、晚到/到期、額外成功不重賣、金額/貨幣不符、altered event拒絕、feature-off／登出／停用、payment-vs-cancel／3expiry workers競爭。
- mock receipt、event audit、conversion audit故障整筆rollback，原UUID重試成功；未知receipt後原attempt保持。真正ticket鎖等待跨過持久deadline，鎖後核對轉REVIEW不售出。
- 開發初輪僅舊catalog schema核對仍預期V3而失敗，已改V4。V4未發布前補強FK後只重建自己拋棄scope，重新用保留且SHA驗證的正式V3 JAR造舊catalog／audit／顧客session／保票，再跑migration與完整package；不用repair/baseline/clean。V3→V4 migration實際成功，HTTP投影升級核對下一checkpoint補做。

## 尚未完成事項
本輪下一小階段：付款顧客介面、原付款UUID／未知ACK保留、production JAR socket/Chromium付款及回歸驗收、原V3持久session／保票HTTP核對、DB invariant proof、關閉自有API/preview與清理自有scope。之後M3 Redis等待室／限流、M4 Lua/outbox/MQ/worker/出票、M5集群與M6壓測；真服務／正式本機部署見CLOUD_LOCAL_FOLLOWUP。

## 已知問題
- 此checkpoint後端已實作，介面尚無付款按鈕，勿宣稱全M2C驗收完成。沒有真provider或公開callback，receipt與attempt同DB是免費模擬器；真支付跨系統unknown／簽章另驗。
- 每筆最多一次付款；DECLINED／REVIEW不自動開第二次付款，沒有退款或人工REVIEW解決工具。不盲目改庫存。付款PENDING由顧客手動核對，無payment背景掃描；reservation expiry照常運行。
- 付款reconcile最多20receipt，正常只有一筆；異常大量event的營運掃描／警告待後續。相同event改內容409且不改既有帳務，需人工核對。
- 本里程碑自有DB仍保留供下一小階段socket／browser／upgrade；其他作品容器／未提交修改未碰。無精確usage/context介面，按獨立後端checkpoint保存。

## 下一步建議
續作前同步遠端main、讀本交接重建，V4發布後不再改bytes。完成上述介面與外部socket/瀏覽器驗收，更新交接commit/push；完成後才做M3。最終部署由用戶本機承擔，不現在下載到其電腦，不接付費服務。
