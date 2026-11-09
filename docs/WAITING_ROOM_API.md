# M3 票種等候室與資格

Redis 7單實例，免費自架；PostgreSQL仍為票額／訂單唯一來源。這階段不是Lua票額預扣、MQ削峰、cluster或效能承諾。`TICKET_ADMISSION_ENABLED=false`預設關閉；明確true後所有**新**保票需本人該票種的Redis短效資格。新旗標需API重啟。關閉為營運設定，不是Redis故障自動降級。

## API

| 路徑 | 行為 |
|---|---|
| POST `/api/customer/waiting-room/{ticketId}/join` | `{requestId}`，顧客CSRF；明確加入、原UUID重送 |
| GET `/api/customer/waiting-room/{ticketId}` | 僅本人排隊狀態、等待心跳與到期清理；不會替未加入者建立排隊 |

僅顧客session，owner來自principal；未知owner/position/token欄位400，未登入401、CSRF403。票種須屬已發布且在售票窗口；否則404。公開Detail新增waitingRoomEnabled（旗標及保票開放時true），不暴露排隊顧客名單。

回覆`{ticketId,state,requestId,position,expiresAt,serverNow}`。state為NOT_JOINED、WAITING、ADMITTED、EXPIRED。position僅等待時為1-based，代表目前前方未釋放的等待位置；不是一定買得到票或完成時間預估。expiresAt/serverNow為Redis TIME UTC milliseconds；沒有可轉送的bearer admission token。

## 規則

- 每票種獨立；Redis原子INCR序號／ZSET先接受的有效等待者先入場，並非承諾Internet到達公平。入場由join/poll清理與補位；沒有獨立發號worker，沒人呼叫就不會主動推播。
- 預設同時2顧客入場、100等待；配置active1–20、queue1–200。等待需20秒內心跳，最長300秒；入場30秒，poll／重送不續期，deadline不超售票截止。合法範圍见.env.example與AdmissionSettings。
- 同owner/sessionVersion、同票種、原join UUID在保留期間重放同排隊，不另插入／延長入場。仍WAITING/ADMITTED時不同UUID409；EXPIRED原UUID不复活，必須明確新UUID才排回隊尾。明確重排後保留最多20個retired UUID；舊UUID仍回EXPIRED，不替換新資格。達上限會429，等紀錄TTL清理，不把旧UUID踢掉後當新加入。同UUID可在別票種使用，queue UUID命名空間與PG訂單指令分開。
- 原顧客身份在PG交易內重查，停用／登出不能心跳或保票；停用再啟用sessionVersion變更，新登入須重新排隊。舊lease按短期限自然釋放，不盲改票額。
- Redis Lua只管入口，五個room key共用`{ticketId}` hash tag；最多220活躍、1000有期限紀錄，每次有界清理。terminal紀錄在最近明確join最大等待期後30分鐘清掉，整個room idle一小時到期；不是永久audit／永久idempotency。state遺失時GET回NOT_JOINED，須明確重新join，沒有從DB票額補回Redis的步驟。
- 每owner token bucket：join/poll共30次/60秒（burst30），新保票5次/10秒（burst5）。Redis TIME補充，不因倒退時鐘增加tokens。429帶Retry-After秒數；容量滿也429。限流不是queue公平或防bot完整方案，匿名註冊登入/IP/分散式防禦另補。

## 保票最後檢查與故障

PG身份核對→原PLACE fingerprint重放→新操作限流→event/ticket lock→PG窗口/價格/限購→Redis資格CHECK→重新PG時間核對→held/order/command/audit同交易。實際等待ticket lock跨permit截止會拒絕；資格不能跳過PG價格、限購、可售票額及售票deadline。資格非一次性付款或保票token，短期限內可依PG規則保票；不扣Redis ticket庫存。

資格驗證是Redis讀取時的入口決策，不能宣稱Redis與PG commit同一原子交易；驗證後斷線不回滾已成功的PG保票。500ms connect/command timeout，Redis不能確認／資料型別錯誤回503，**不自動放行新保票、不盲INCR補票**。原已成功PLACE UUID在資格／限流之前PG重放，cancel、expiry、付款核對與本人歷史無需Redis。

Redis私有密碼／loopback端口26379，獨立volume/AOF everysec、128MB/noeviction。單機AOF不是容錯或零遺失；loss後只重建入口資格，原PG訂單與held/sold不變。room/限流key TTL限制資料留存，單ticket為熱key，後續壓測／Cluster／故障驗收另做。

## 前端與驗收

checkout先顯示等候室，未入場／到期時不能送新保票。poll每3秒，只保持等待心跳，不續入場期限；以serverNow校正本機倒數，真正資格仍由backend核對。429 Retry-After會暫停讀取與join；503不顯示可保票。關分頁、背景節流或網路中断可能令等待過期，需新join回隊尾。

sessionStorage待確認join只保存依customer UUID/ticket UUID隔離的原request UUID，沒有password、token或bearer permit。join ACK遺失/不完整或401/403/5xx保留原UUID，重整後明確重送；未核對時不開新保票。換顧客不呈現／接管前顧客的join。GET NOT_JOINED不自動join；state loss須明確按加入。

`scripts/smoke_waiting_room.py`正常HTTP及prepare-fault/while-down/after-restart/state-lost多階段；操作者只停自己Redis、只刪fixture的五個room keys，不執行FLUSHALL/FLUSHDB、不改PG庫存。`smoke_waiting_room_browser.cjs`驗真JAR/production dist多人排隊、lost ACK、帳號切換、原UUID/固定期限、429 backoff與393px手機。舊活動／保票／付款回歸在admission關閉設定；詳見HANDOFF/verification。

多人順序smoke使用自有測試profile：active=1、permit=12秒、idle=10秒，orders/admission=true；browser依TICKET_RUNTIME_ENV讀取該private env。正式預設仍active=2、permit=30秒、idle=20秒。啟動API後先確認public/events回200再跑socket/browser；Redis啟動後還需API client實際恢復，不能僅用容器PONG當API已恢復。
