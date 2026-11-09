# 共用 PostgreSQL 員工 session（M5A）

兩 API 使用同一份已 migration 的 PostgreSQL，不依賴 sticky session、Redis 或 JVM 記憶體。免費自架；這是多 API 的登入前置，並不是完整負載分流、跨主機 HA 或壓測完成。

## 契約與切換

原 `/api/auth/csrf`、form-urlencoded `/login`、`/me`、`/logout` 路徑與登入 UI 保留。login 僅接受唯一 username/password 欄位，API 回 `{authenticated:true}`，不回 token。me 僅回 username/canWrite。HTTP Basic、Spring form filter、HttpSession context／request cache停用；沒有 Java serialized session attributes。顧客 chain／cookie／CSRF 與既有顧客 PG session 分開。

V22 新增 staff_account.session_version 與 staff_session；V1–V21不改。新增欄位初值1，原帳密／角色／業務 command／audit 不改；不把舊 memory session 匯入或假稱能移轉。**所有舊 API 先停止，再部署全部新版 API**；不要混用 V21 記憶體與 V22 PG 登入。舊 cookie 需重新登入，原 sessionStorage pending保留，仍須原 current MANAGER核對原 UUID/body。前端 production source 本輪無更動。

新的 `TICKET_STAFF_SESSION` 是 32 隨機 bytes／43字元的 base64url bearer，DB只存 SHA256。cookie HttpOnly／SameSite=Strict／Path=/api／Max-Age=1800／Secure預設true；明確 loopback測試才設定 TICKET_COOKIE_SECURE=false。login／logout 同時清理舊 Path=/ cookie，避免重複路徑歧義；重複 session cookies不能取得員工身分。原 Tomcat cookie／匿名 cookie不授權。

session是 **DB clock 起算的固定30分鐘**，開始登入時建立，expiresAt不含；讀取不滑動續期。與過去容器 inactivity timeout不同，改 server.servlet.session.timeout不改此DB期限。新登入重新生成token，成功同交易撤銷目前瀏覽器的原token；未知login回覆可重新登入，不把它當業務 command重播。每帳號最多5筆session，account FOR UPDATE跨API序列化cap／清理過期與舊version。INSERT guard再次核對 current enabled account／role／version，createdAt／expiresAt均DB stamp。session寫入失敗時token輪替與刪除一起rollback。

## 跨 API CSRF 與權限

staff CSRF 改為 CookieCsrfTokenRepository，cookie `TICKET_STAFF_CSRF` HttpOnly／Secure／Strict／Path=/api，header `X-STAFF-CSRF`；`GET /csrf`回 Spring XOR masked token及headerName。token不寫localStorage，也不靠JavaScript讀cookie。A取得的token可配同瀏覽器cookie在B驗證，沒有節點專屬記憶體密鑰。login／logout成功清CSRF cookie，之後重新GET；缺cookie／header或顧客CSRF不能跨realm通過。

每次protected request從token hash重新JOIN current enabled staff／session_version／role_snapshot與DB expiry，不缓存或fallback。MANAGER有CATALOG_READ／WRITE，VIEWER只有READ／VIEW；原業務service仍同交易鎖current staff及核對原actor、版本與audit。`X-Expected-Staff-Username`只能比對已驗證身分，不提供actor；改cookie、匿名或過期不能接管pending。測試注入的MockMvc user不是production登入來源。

停用／再啟用、password hash／username／role變更，DB trigger增加session_version並刪該帳號全部session；未曾在中間發request也不會復活。explicit version增加可撤銷，不能倒退。登出刪原hash，其他API立即拒絕該cookie；不登出顧客。這裡的「立即」指撤銷 **commit後的新驗證**，不是取消已經被接受的in-flight交易；原交易的current角色鎖及業務規則仍有效。

有效格式cookie但PG無法核對時，filter回503 AUTH_UNAVAILABLE、保留原操作，沒有memory／Redis fallback。未登入不能靠header或舊HttpSession授權。CSRF驗證仍可能先回403；不把所有失敗都說成session已不存在，不自動清業務pending或換UUID。

## 驗證與範圍

StaffSessionIntegrationTest 25項真PG，含不同service instance共用、固定DB expiry、disabled／role／password／username round trip、不倒退version、並發cap、atomic rotation rollback、直接SQL錯role/version拒絕、real form／cookie／CSRF、realm隔離、original expectedactor、legacy／duplicatecookie拒絕、PG表不可用時fail closed。既有MockMvc／交易suite維持原業務意義；正式JAR雙進程另外驗收。

`run_staff_session_runtime.py`／`smoke_staff_session_browser.cjs` 只接受新空 owned `ticket-staff-runtime-m5a[a-z]?`，PG25434／Redis26381；真V21 JAR seed→V22→兩final JAR（19112／19113）與final dist15182。19114為loopback test proxy，只供驗收固定A／B或逐request交替，**不是production負載平衡器**。驗收固定JAR／dist／script hash；比較原public columns（staff新version欄另核對1）、拒絕memory cookie、原UI pending重播、A→B CSRF、VIEWER與expectedactor、actual兩API restart／Redis TCP down／PG stop＆restart、撤銷／過期與原command。工具存在不當成通過，實際結果以HANDOFF／verification為準。

runtime SQL寫入只供隔離staff fixture（VIEWER、改角色／帳密、過期），不等於已提供員工管理API。帳密／cookie／body／截图／fixture均留ignored private work，不公開。原M4 runtime工具固定舊migration版本；本輪不冒稱原QR／window整條runtime再跑，完整backend package仍包含原suite。

尚待：全域有界過期session清理、登入限流／MFA／員工管理、TLS／proxy trust／DB授權、部署profile／正式反向proxy／Worker雙節點／Cluster／MQ quorum／跨主機故障／k6。PG成為共同登入依賴，單PG容器不代表HA；cookie bearer与DB hash仍需保護。

本輪正式驗收完成：586項／26 suites完整package、前端production build；真V21→V22／兩final JAR／Chromium／最終dist的五phase／13組、27 invariants＋來源signal全0，無runtime重跑。詳見 [M5A證據](verification/shared-staff-sessions-m5a-2026-10-11.json)及HANDOFF。
