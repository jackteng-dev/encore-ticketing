# 員工返還接棒（M4D2C5）

正式取消／到期的 PostgreSQL RELEASE 是票額來源。員工明確建立返還操作，原 V14 core 用完整 `capacity − held − sold` snapshot 配置新世代；原 FROZEN 台帳及 COMMITTED receipt 保留。詳細核心條件見 [STOCK_RETURN_HANDOFF](STOCK_RETURN_HANDOFF.md)。沒有自動 consumer、排程或 Redis INCR 補票。

## 啟動及權限

`TICKET_STOCK_RETURN_ENABLED=true` 才允許建立／恢復；預設 false。旗標關閉仍可讀來源／本人歷史，pending 回覆 `canResume=false`。既有員工 cookie + CSRF；actor 由已驗證 Principal 決定，body 不接受 actor。MANAGER 可寫，VIEWER 可看來源與本人的原操作。其他管理員也不能讀取／接管他人 receipt。改角色或停用會使既有員工 session 失效；原員工降為 VIEWER 後重新登入可讀歷史，不能 POST。

每個 PG phase 都再次鎖住／核對当前 enabled MANAGER 和原操作進度。員工 checkpoint／不可變 audit 與該 phase 的原 domain 寫入同交易；不能用外層 transaction 包整段跨 PG／Redis 呼叫。登記先以 namespaced UUID 的 PG transaction advisory lock 序列化並發同命令；order unique 拒絕其他 UUID 競爭。各 phase 遵循 actor → progress → ticket 鎖順序。

## API

所有路徑以 `/api/admin/stock` 開頭。清單／audit `page=1..100000`、`size=1..20`，不返回顧客 token／session／原 Redis receipt。

| Method | 路徑 | 用途 |
| --- | --- | --- |
| GET | `/return-settings` | 返還旗標 |
| GET | `/tickets/{ticketId}/return-candidates` | 原已 ack COMMIT 的正式 RELEASE 來源；可建立狀態僅為提示 |
| POST | `/tickets/{ticketId}/orders/{orderId}/returns` | 原命令建立／核對 |
| GET | `/returns/{requestId}` | 原員工 PG-only 進度 |
| GET | `/returns/{requestId}/audit` | 原員工 phase 紀錄 |

POST body：`{"requestId":"原 UUID","expectedReleaseRevision":3}`。寫入及 progress GET 支援 `X-Expected-Staff-Username`，帳號不符先 401。原 UUID 綁 actor／ticket／order／releaseRevision；變 body／換 actor／換 UUID 採用同 order 回 409。尚無正式來源時不消耗 order 的登記槽位；內部已有 return 不能冒用或轉成員工命令。

Receipt 包含原 identity、state／generation／version／failure、`canResume`、updatedAt 與獨立 currentSourceRevision／currentGeneration／currentState。REGISTERED／CLOSED 回 202；APPLIED／BLOCKED 回 200；Location 指向原 progress。APPLIED 為歷史已完成證據，不保證現在 READY，不重新初始化已使用／遺失 key。

## 狀態及恢復

正常 checkpoint 為 REGISTERED v1 → CLOSED v2（原 domain command 與 fresh CLOSED generation 已提交）→ APPLIED v3（READY／domain ack／staff audit 同提交）。未知 Redis 結果保留 CLOSED／REDIS_UNKNOWN，可追加核對 checkpoint。source／generation fence 或不可恢復損壞會保存 BLOCKED；不能刪原紀錄或換 UUID 再試。

Redis 已 OPEN，但 PG 或員工 APPLIED audit 失敗時，PG 整個完成 phase 回滾為 CLOSED，domain ack 不存在。恢復使用原 UUID／body，核對原準備的 key，不另配世代。已有 domain historical ack、員工仍 CLOSED 時，補員工 APPLIED checkpoint 是受權限保護的獨立 PG-only transaction，不觸碰 Redis。

## 畫面

員工「庫存維護與核對」頁顯示原取消／到期訂單與 release revision。建立前持久保存原 UUID／body；未知、缺欄或不符 identity 的回覆不解除全站員工寫入暫停。reload／導航仍可核對；恢復先 GET，只有完整、可恢復的原 receipt 才 POST 原 body。APPLIED／BLOCKED 經驗證才解除 pending；終止後可讀原 audit，無新命令。

旗標關閉不 POST；換帳號不接管。角色改變使用伺服器最新權限。GET 已驗證的 CLOSED 進度即使後續 POST 失敗，仍保留在畫面，不能以較舊 version 覆寫新 receipt。

## 驗收及界線

新增 21 項真 PG／Redis JUnit；完整 package／frontend build 與本輪實測見 [HANDOFF](HANDOFF.md)。`scripts/run_stock_returns_staff.py` 需要新空的自有 PG／Redis scope、成功 package／dist、原 V14 JAR；執行 V14→V15、四 phase production HTTP／Chromium、真 Redis stop／same AOF restart，以及 test-only core 新 HOLD 核對。原 fixture／帳密／日志／截圖只留 ignored work。腳本不下載、不清 schema、不清理其他 scope。只讀核對見 `scripts/verify_stock_return_staff_invariants.sql`。

尚未設計 pending 被較新 snapshot 吸收後的人工 terminal review／replacement 政策。BLOCKED 是原操作受阻紀錄，不能代表已另行返還或安全放棄。下一步先做此政策及驗證，再加有界背景恢復。active intent 仍阻擋接棒，尚無持續吞吐／live consumer、多 API 共用員工 session 或公開部署。

C6 已新增 [原 BLOCKED 與已發布快照結案](STOCK_RETURN_RESOLUTIONS.md)。保留本文件原 C5 progress／audit，ABSORBED 以獨立 immutable 關聯表示；REGISTERED／CLOSED 要先核對原命令。永久 fenced 未知結案 attempt 的顯式解除／重新綁定仍待做。
