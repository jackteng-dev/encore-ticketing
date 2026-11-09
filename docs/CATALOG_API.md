# M1 活動管理合約

JSON直接回傳物件（無額外envelope）；錯誤 `{code,message}`，400格式/未知欄位，401未登入/存取變更，403權限/CSRF，404不可見/不存在，409版本/重送衝突/條件鎖定。沒有顧客下單API。

## 身分
GET `/api/auth/csrf` → `{headerName,token}`，產生獨立HttpOnly `TICKET_STAFF_CSRF` cookie，不建立HttpSession。POST `/api/auth/login` 使用form-urlencoded唯一username/password與CSRF header；GET `/api/auth/me`回username/canWrite；POST `/api/auth/logout`需CSRF。Cookie `TICKET_STAFF_SESSION` SameSite=Strict、HttpOnly、Secure預設true、Path=/api。M5A共用PG session，固定DB clock30分鐘，登入換token、登出跨API失效；舊memory session不匯入，切換後重新登入且原pending保留。詳見 [共享員工session](SHARED_STAFF_SESSIONS.md)，runtime以HANDOFF為準。

staff_account角色MANAGER/VIEWER；讀取CATALOG_READ，寫入CATALOG_WRITE。每次已登入請求DB核對啟用/角色，變更使舊session無效；寫入在交易內再查啟用manager與鎖定actor。没有員工CRUD/改密碼API；原員工管理/MFA/登入限流另階段。

## 端點
| Method | Path | 意義 |
|---|---|---|
| GET | /api/public/events?page=1&size=12 | 已發布活動 |
| GET | /api/public/events/{eventId} | 活動+場次+票種；orderingEnabled／waitingRoomEnabled／purchaseRequestsEnabled設定投影 |
| GET | /api/admin/events | 全狀態活動 |
| GET | /api/admin/events/{eventId} | 管理詳情 |
| POST | /api/admin/events | 建草稿 |
| PUT | /api/admin/events/{eventId} | 修改草稿 |
| POST | /api/admin/events/{eventId}/publish | 發布完整有效草稿 |
| POST | /api/admin/events/{eventId}/archive | 封存，不能重開 |
| POST | /api/admin/events/{eventId}/sessions | 新增草稿場次 |
| PUT | /api/admin/sessions/{sessionId} | 修改草稿場次 |
| POST | /api/admin/sessions/{sessionId}/tickets | 新增草稿票種 |
| PUT | /api/admin/tickets/{ticketId} | 修改草稿票種 |
| GET | /api/admin/audit?page=1&size=20 | 只讀操作紀錄 |

page1–100000，size1–20；沒有刪資料/auditAPI。發布至少一場次，每場次至少一票種且售票結束在未来。發布鎖全部條件；封存保留資料、公開404。場次20/活動，票種10/場次。

## 寫入欄位
所有body必填requestId UUID及expectedVersion；新增=0，修改/transition=目前resource版本。Event: slug（80、lowercase英數hyphen）、title120、description3000、venue160；Session: name120、startsAt/salesStart/salesEnd ISO8601含offset，salesStart<salesEnd<=startsAt；Ticket: name100、priceTwd整數1–1000000、capacity1–100000、maxPerCustomer1–10且<=capacity；transition只有共用兩欄。server產生resource UUID，actor只取principal；未知owner/actor/status/version等欄位拒絕。

Event/Session/Ticket各自版本，子項修改不提升parent版本；catalog_guard使修改與發布互斥，不會發布到中間狀態。管理鎖只用少量管理流量，未來票額交易另設。

## 冪等與回覆
全域requestId核對actor/action/path/body canonical指紋；同操作回原成功快照（可能為舊版本），必須另GET目前狀態，不能當成version倒退。改內容/actor/path/action同UUID409。業務、版本與catalog_command audit一個DB交易；audit失敗全回滾。指紋不公開，audit回requestId/action/resourceId/createdAt，不回hash/密碼/actor私有資料。

前端先保存原owner/path/method/body及parentEventId到sessionStorage；結果未知（network/5xx/408/429/格式不完整）冻结新寫入，原UUID/body重試，重新整理也保留。不同員工不得接管原pending；已確認4xx可清pending，但保留表單，重讀版本再明確編輯。ACK後GET失敗不能重建待寫操作。catalog pending只含活動設定，無密碼/token；完整裝置/個資策略另階段。

售票時段UPCOMING/SELLING/CLOSED是資訊，不表示購票已啟用；orderingEnabled預設false，需明確開放且活動published；purchaseRequestsEnabled另需purchases／delivery／publisher全啟用，不是worker健康保證。時間存timestamptz，公開顯示台北，管理輸入使用瀏覽器本地時區。
