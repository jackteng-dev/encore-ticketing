# 場次入場時間窗（M4E4）

免費自架、PostgreSQL 裁定。新 CHECK_IN 必須有已設定且開放的每場時間窗；不假設開演前後固定時數。開始含、結束不含（`opensAt <= DB clock < closesAt`）。inspect／QR／畫面預覽不保證可入場，真正提交會重新判定。

## 設定與版本

MANAGER 在 `/staff/admissions` 選活動／場次，於「入場時間窗」輸入**台北時間 UTC+8**並保存。表單以分鐘輸入，送出 UTC 整秒字串；API 可精確到秒。舊設定不改／刪，每次明確設定保存新的 immutable policy version；未設定版本0，首設定1，後續精確 expectedVersion+1。允許已發布場次與已結束窗口的明確調整，原 catalog 開演／販售條件與 version 不變。窗口調整不是活動取消、重入場、退款或庫存返還。

`TICKET_ADMISSION_WINDOW_ENABLED=false` 預設拒絕**新設定**；設定需明確 true 並重啟。原 read／exact APPLIED replay／receipt／stop 不依賴此旗標或 Redis。`TICKET_CHECK_IN_ENABLED` 仍控制新入場／撤銷，但開啟它也不能跳過窗口；窗口強制 gate 沒有旁路旗標。VIEWER 只讀目前窗口／設定紀錄，不能設定／核對原寫入／永久停止。

設定命令有獨立 `ticket-window:` UUID 全域 namespace，綁原 actor／session／exact body fingerprint；不與既有 `ticket-admission:` 或 catalog UUID 合併。前端仍共用全站 staff pending，所有新寫入 freeze。UUID 相同而 scope／body 不同409，其他原 actor404。current enabled MANAGER 與原 identity header／CSRF 每次再次核對；停用／降權後不能接管原操作。

## API

| 路徑 | 契約 |
|---|---|
| GET `/api/admin/admissions/windows/settings` | `{enabled}`，設定旗標 |
| GET `/api/admin/admissions/windows/{sessionId}` | Status：sessionId／version／requestId／opensAt／closesAt／state／observedAt |
| POST 同上 | `{requestId,expectedVersion,opensAt,closesAt}`，回完整原 Recovery（APPLIED） |
| POST 同上 `/receipt` | exact 原 body，純核對；APPLIED／FENCED／UNKNOWN |
| POST 同上 `/stop` | exact 原 body，明確永久停止；已有成功回 APPLIED，未套用保存 FENCED，不改目前窗口 |
| GET 同上 `/audit?page=1&size=12` | 全 actor 的有界窗口設定／停止歷史；page1..100000，size1..20 |
| GET `/api/customer/orders/{orderId}/admission-window` | 本人有效 customer／session，其他 owner404；不回 code |

`opensAt`／`closesAt` body 必須是完整 UTC 整秒 `YYYY-MM-DDTHH:mm:ssZ`，真日期可解析。新設定開始須早於結束；stop 只需有效原日期格式，不要求原 version 仍正確或區間仍可套用，因此永久 version conflict 仍可結案。不把語意錯誤 body 改成另一個 body 再套用。strict JSON／對應 CSRF／X-Expected-Staff-Username 或顧客 header 沿用既有安全邊界。

Status 的 `UNCONFIGURED`／`NOT_OPEN`／`OPEN`／`CLOSED` 是 `observedAt` 的 DB clock 快照。管理員 UI 以這個時間加 monotonic elapsed 預覽是否可按 CHECK_IN，瀏覽器時計與時區不授權入場。重新核對票碼也重讀窗口；設定已被其他管理員改變時，服務端仍以提交時最新 policy 裁定。

Recovery 固定回原 requestId／sessionId／expectedVersion／原兩字串／bodyFingerprint／state／policy／recordedAt。APPLIED 必須是原完整 Policy（sessionId／version／requestId／兩實際時點／createdAt），FENCED policy null 有原 recordedAt，UNKNOWN 兩者 null，沒有持久效果。前端 WebCrypto 核對 canonical `ADMISSION_WINDOW:<sessionId>:<JSON原body>` SHA256、原 scope／版本／字串與完整 policy／時間，只有 APPLIED／FENCED完整證據才清 pending。缺欄、錯 hash、4xx、UNKNOWN或超時都保留。原 UNKNOWN 只代表未找到結果；明確選重試原操作或永久停止，停止再確認一次。不刪 storage、換 UUID 或改原 body 繞過。

## 交易與時間證據

設定／核對／stop：current staff shared → 原 window UUID transaction advisory lock。新設定再鎖 event_session UPDATE，核對目前版本後 command、policy、audit同交易；stop無新 policy，audit故障全部rollback。直接 SQL 也同 UUID／session guard，policy 必須綁 exact APPLIED command與連續版本，deferred guard拒絕只有 command 沒 policy／audit。四張證據表 UPDATE／DELETE全部拒絕。

新驗票延續 staff→admission UUID→customer→order→attempt→issued_ticket locks；最後 event_session SHARE，排斥並發設定，取得全部鎖後再以 **DB `clock_timestamp()`**判窗口。DB BEFORE UPDATE guard再次以真正套用時刻核對最新 policy；關窗邊界即使在服務核對之後到達，也拒絕並共同rollback。CHECKED_IN 的 changedAt與 proof.checkedAt同一時點，原 command／ticket／admission audit／time proof同 PG交易。不能預先插一筆較早 proof再晚到關窗後使用；真正 transition總會新插 proof，unique＋deferred guard拒絕預插／缺證據／錯票或場次／時間越界。

CHECK_IN Receipt 新增 `windowProof`：原 requestId／sessionId／policyVersion／原 opensAt／closesAt／checkedAt，沒有 bearer碼。重播或 M4E2 receipt／stop 返回當初immutable proof，後來關窗／改窗口／旗標 false不重新套用。REVOKE仍對未使用票可明確執行，不要求開窗，proof null；不退款、不補庫存。financial `StaffTicket.eligible` 保留付款／帳號意義，與窗口狀態分開，避免把尚未開窗說成付款異常。

新的前端 CHECK_IN pending 記錄 `admissionWindowRequired=true`，完整 receipt還須精確原 proof，缺失或 null不能清 pending。升級前原 pending無此標記，只能接受服務端明確 null的歷史 proof；undefined／畸形 proof仍拒絕。

## V20 → V21 切換

V1–V20 bytes／checksum不改。V21新增 command／policy／audit／proof，以及 `ticket_window_legacy`一次分類。migration只把當時已存在 CHECK_IN的 request ID列入不可變分類表，不更改原 command／ticket／audit，不寫 policy或虛構時間證據；此表在分類完成後 INSERT／UPDATE／DELETE全拒絕。

升級前成功 receipt明確 `windowProof:null`，只在legacy registry才能如此返回；新命令 deferred guard要求proof，不能冒充歷史。未設定的歷史場次**立即拒絕新入場**，部署者須先明確設定；既有出票／原碼與成功歷史保留。先升級後端再部署前端，不能以新版前端的強證據驗證配舊版 API並自動清 pending。

## 驗證與限制

`AdmissionWindowIntegrationTest`21項真 PG，含整秒／microsecond exact邊界、未設定／未開／關窗、原 receipt不受policy更新影響、raw SQL拒絕、真鎖等待跨DB時間關窗及未committed replacement後最新policy、原 UUID競爭、audit／proof故障回滾、CSRF／strict JSON／owner／actor／VIEWER。既有票券／恢復38項 fixture明確配置OPEN窗口，保持原業務測試意義，不跳過窗口。

readonly：`scripts/verify_admission_window_invariants.sql`六項；與原20項 invariants／來源 signal一起核對。正式 runtime `run_admission_window_runtime.py --env work/<own>/runtime.env --baseline-jar work/<own>/baseline-v20.jar --output work/<own>/proof`要求新空 owned `ticket-window-runtime-e4[a-z]?`，固定loopback端口；真 V20 seed → V21 upgrade，比原public rows不變、registry精確原 ID／新四表0；再正式 JAR／dist／Chromium／原 QR／窗口ACK損失／原 receipt／stop、真正 API terminate／wait／flags false restart／Redis TCP關閉，以及真DBclock關窗驗收。原碼／cookie／PNG／logs／fixture只留 private ignored work。

實際執行結果以 HANDOFF與 verification為準，工具存在不當作通過。舊 M4E3 QR／M4E2 recovery工具固定對應V20，不對V21宣稱已回歸；新工具包含相關入口的正式驗收。當輪使用mock付款與單機staff session，沒有真現場設備／公網部署／HA或負載驗收；後續M5A共享staff session另見SHARED_STAFF_SESSIONS；實體手機／時鐘運維／營運窗口設定流程留 CLOUD_LOCAL_FOLLOWUP。

本輪正式驗收已完成：561項／25 suites package、最終 frontend production build及四phase／14組 JAR／Chromium升級與停機測試；26 invariants＋來源 signal全0。完整時間戳核對包含毫秒以下變更拒絕；首次中途重建 dist失敗如實保留。最終產物全程hash固定，詳見 [M4E4證據](verification/admission-windows-m4e4-2026-10-11.json)與 HANDOFF；不包含真手機／相機或公網／負載驗收。
