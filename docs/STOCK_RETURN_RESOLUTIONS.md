# 原返還受阻的已發布快照結案（M4D2C6）

這是人工證據核對。`ABSORBED` 表示原返還效果已包含於指定的完整 PG 票額快照，且該快照先前有持久發布證據。結案只寫 PostgreSQL 關聯與 audit，不發起返還、不新增世代、不改 READY、不讀寫 Redis、不改原 COMMIT／FROZEN receipt。原 C5 `BLOCKED` 與 phase audit 永久保留。歷史結案不保證目前投影可預扣。

## 條件與邊界

- 原返還 UUID／order／actor 固定；只能由原 actor、當前 enabled MANAGER 結案。本人 VIEWER 可讀歷史，其他 manager 不可接管。權限、原 progress、ticket／projection 的鎖與 INSERT／audit 在同交易。
- 原操作必須 `BLOCKED`；`REGISTERED`／`CLOSED` 要先以原 C5 UUID/body 核對、建立明確 fence，不能以新 UUID 重做。`APPLIED` 不可改標 ABSORBED。
- 原來源必須是取消／expiry 的 V13 ORDER_RELEASE、原 COMMIT 與 COMMITTED ack；不能已有原 domain return ack。
- 替換快照必須已提交，且是目前 PG `READY` 的 exact rebuild UUID／sourceRevision／generation／preparedAvailable；票額基準必須等於 PG `capacity-held-sold`。sourceRevision 至少包含原 RELEASE，generation 大於原 RELEASE context generation。
- 發布證據是 B5 `stock_maintenance_audit READY` 或另一返還的 V14 `stock_return_ack`。只有內部 `StockProjectionService.complete()` READY 不夠。資料庫 trigger 比對完整 snapshot image 並拒絕同交易建立的 rebuild／publication，不能自行偽造已提交證據。
- 任何未解決 intent（包含 PREPARED／HELD／尚未 ack COMMIT）都阻擋新結案；不強制 abort、刪除 REVIEW 或跳過正式決定。
- 新結案預設關閉：`TICKET_STOCK_RETURN_RESOLUTION_ENABLED=false`。此旗標獨立於 C5 return 旗標。已保存的本人歷史 GET 與 current manager 的 exact-body POST replay 是 PG-only，旗標關閉／Redis 停機／key 已使用或遺失也不重新初始化。

## API

需要已驗證員工 session；POST 需要 CSRF。actor 只由 Principal 取得，未知 JSON 欄位拒絕；可傳 `X-Expected-Staff-Username` 防帳號切換。

| 方法／路徑（前綴 `/api`） | 結果 |
| --- | --- |
| GET `/admin/stock/returns/{originalUuid}/resolution` | 原 returnVersion／returnState、旗標、reason、符合條件的 replacement、已保存 resolution |
| POST 同一路徑 | body `{requestId: originalUuid, expectedVersion, replacementRequestId}`；200 ABSORBED receipt |
| GET 同一路徑 `/audit?page=1&size=12` | 本人紀錄；page 1..100000、size 1..20，最多一筆 |

body 中的 requestId 必須等於 path 原返還 UUID；不是新的結案／返還 UUID。receipt 綁原 ticket／order／returnVersion／releaseRevision／sourceGeneration、replacement UUID／revision／generation／available／proofKind／publishedAt，且有 createdAt。V16 新增兩個 immutable table 與 proof view，V1–V15 不改 checksum。audit 失敗會 rollback 整筆結案，原 BLOCKED 不變。6 個並發相同 body 只保存一筆結案及一筆 audit。

fresh POST 遇來源、世代或未解決 intent 變更會409；已有結案後 exact body 回同一 receipt，變 version／replacement 回 REQUEST_CONFLICT。preview 是某個時間點的證據；POST 重新鎖定驗證，不能用過期預覽保證成功。

## 員工操作與未知回覆

`/staff/stock` 選原票種，在取消／到期來源按「讀取原返還操作」。原 BLOCKED 下方顯示「返還結案證據」與目前符合條件的已發布快照；管理員明確按「依已發布快照結案」。原返還紀錄與結案紀錄分開展示。

送出前將原 UUID／version／replacement body 保存於同 tab sessionStorage。client ACK 遺失、缺欄或 HTTP 失敗都保留 pending，凍結全站員工寫入。重新整理後按「核對原維護操作」先 GET；已有完整、exact-body ABSORBED 證據就清 pending、不再 POST。尚無結案且仍可核對時才 POST 原 body；換帳號不能接管。

C7 已補上 [永久停止與明確換綁](STOCK_RETURN_RESOLUTION_RECOVERY.md)。未知原內容仍不自動更換 body：明確核對後，以永久 fence證明未結案並拒絕晚到請求；新快照換綁／ABSORBED同交易。不要刪 sessionStorage 或換返還 UUID。未建立fence前的C6 Begin保持原契約；有fence後必須走明確rebind。

## 驗證與重現

23 項真 PG／Redis交易測試涵蓋 source／generation／active fences、CLOSED 原命令核對、取消／expiry、兩種持久發布證據、偽造 snapshot、並發／權限／immutable／audit rollback、旗標與歷史 key 已使用／遺失。完整 backend package 與 frontend production build 結果見 [HANDOFF](HANDOFF.md)。

`run_stock_return_resolutions.py` 只接受自己的新空 PG／Redis `ticket-resolution-runtime-*`、loopback ports與私有 work/ env。這是歷史V16驗收 harness；不接受current V17 artifact。需先完整 package/build、保留真 V15 JAR，禁止 schema clean／repair／重用舊證據。舊 JAR core 建立六組 original BLOCKED＋已發布 B5 snapshot；用最終 V16 JAR 升級並核對原業務表不變，再對 production dist 跑真 HTTP／Chromium。腳本實際停自己 Redis、確認 AOF、同 volume 重啟；權限／CSRF／client ACK loss／缺欄 ACK／真 audit CHECK rollback／來源前進／PREPARED阻擋也由實際 API 核對。

```bash
python scripts/run_stock_return_resolutions.py \
  --env work/<own-scope>/runtime.env \
  --baseline-jar work/<own-scope>/baseline-v15.jar \
  --output work/<own-scope>/proof \
  --java <jdk21>/bin/java
```

test-only seed／storage verifier 不進 production JAR。verifier 先核對完整 g1／g2 Redis HASH 不變、原 BLOCKED／audit／PG business，再真正 HOLD 現有 g2 一張，證明基準3→available2／held1，沒有依結案補票。stale fixture 是另一次明確顧客下單造成 PG source 前進，不能宣稱該 fixture 的全部 business rows 不變。只讀 `verify_stock_return_resolution_invariants.sql` 核對歷史 binding／來源／publication／audit；歷史不要求 current READY、目前 role 或 key 尚在。

此單機 isolated proof 不等於公開 HTTPS、壓測、Cluster、TCP commit ACK drop、kill-9、跨主機 HA、持續吞吐、背景 recovery 或出票。
