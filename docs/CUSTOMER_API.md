# M2A 顧客身分

顧客 realm 為 `/api/customer/**`，與 `/api/auth/**` 員工登入分開。僅同源 Cookie，不接收 bearer token、staff principal、customerId 或角色欄位。本文件記錄M2A身分合約；後續M2B展示保票／限購／訂單見[RESERVATION_API](RESERVATION_API.md)，免費mock付款見[PAYMENT_API](PAYMENT_API.md)，沒有真收款。

| 方法／路徑 | 內容 |
|---|---|
| GET `/api/customer/auth/csrf` | 匿名可讀 `{headerName,token}`，寫入前取得 |
| POST `/api/customer/auth/register` | JSON `{handle,displayName,password}`；建立顧客並登入，回安全 Profile |
| POST `/api/customer/auth/login` | JSON `{handle,password}`；回 Profile，輪替目前瀏覽器顧客 session |
| GET `/api/customer/auth/me` | 需顧客登入，回 `{id,handle,displayName}` |
| POST `/api/customer/auth/logout` | 帶顧客 CSRF，回 `{authenticated:false}`；匿名也可，支援登出 ACK 遺失後重試 |

所有 POST 必須把顧客 csrf 回傳的 header/token 帶回。員工 CSRF 不適用。登入／註冊／登出會清除顧客 CSRF Cookie，下次寫入重新 GET。錯誤與其他 API 相同 `{code,message}`；400 格式／未知欄位、401 無登入／錯密碼／停用、403 CSRF、409 重複 handle。

## 帳號與登入合約
- handle 不自動轉換：3–40 個小寫 ASCII 字母、數字、底線、連字號，首字須為字母或數字。唯一不可修改；displayName 非空且最多80字元，Vue安全跳脫顯示。
- 密碼12–72字元，UTF-8最多72 bytes，bcrypt保存。展示使用獨立合成密碼，不收email／真人個資；正式會員驗證與重設待補。
- 顧客 `TICKET_CUSTOMER_SESSION`：256-bit 隨機值，HttpOnly、SameSite=Strict、Path=/api/customer、絕對有效期30分鐘，Secure預設true。DB只存SHA-256 token hash；頁面/API DTO不回token。
- 顧客CSRF使用獨立HttpOnly `TICKET_CUSTOMER_CSRF`／`X-CUSTOMER-CSRF`；Spring XOR token，前端使用GET回傳值，不讀Cookie。顧客鏈stateless，不使用員工HttpSession。
- PostgreSQL每次請求核對expiresAt、enabled、sessionVersion。DB trigger在停用／啟用或密碼hash更改時提升version，舊session不會因重新啟用而復活。session跨API重啟保留；這不代表員工已具多實例共享session。
- 每位顧客最多5筆session；登入以帳號row lock序列化，刪除該帳號過期／舊version與最舊session後發新token。全域過期session清理與資料保留策略尚未實作。
- 註冊的account及session同交易；token保存失敗必須全回滾。唯一handle並發註冊僅一方成功。沒有把註冊密碼或請求內容放入sessionStorage/localStorage。

## 未知回覆
註冊已commit但ACK遺失時，使用相同handle／密碼登入確認；不自動重複註冊、不保存密碼、不假稱註冊支援UUID重放。登入可重新驗證取得新session；登出已刪session但ACK遺失時，新取得CSRF後匿名重試也成功。

## 驗收與限制
本輪JDK21完整package：35項真PostgreSQL測試；顧客HTTP17＋實際JAR重啟2、Chromium14；活動回歸HTTP27／Chromium16；前端型別檢查與production build成功。具體證據：[customer-m2a](verification/customer-m2a-2026-10-08.json)。V1正式JAR先建立已發布活動、場次、票種與4筆audit，再升V2核對投影相同；V1 checksum未變。

Secure=true另在loopback核對實際Set-Cookie標頭；沒有實際公開HTTPS或登入安全壓測。缺少註冊／登入限流、驗證、密碼重設、MFA、正式監控與備份；不能將本輪登入頁當成已完成正式會員營運。Redis與RabbitMQ尚未接入。
