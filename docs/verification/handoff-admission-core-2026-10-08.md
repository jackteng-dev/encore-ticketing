# Encore 售票平台交接

更新：2026-10-08（Asia/Taipei）。本輪起點遠端main `74c3d851682e80aca31edc94135738e186226a34`。先fetch遠端、讀HANDOFF/AGENTS/README、核對status/branch/log/diff並ff-only；只操作ticket-platform。前階段交接保存verification/handoff-m2c-2026-10-08.md。

## 本次完成內容
M3後端獨立checkpoint：Redis票種等候室、owner token bucket、新保票短效資格。join同UUID重送、FIFO序號／有界等待、固定permit不續期、到期原指令不復活；明確新join回隊尾。GET只讀本人狀態／等待心跳，不替未加入者join。owner+sessionVersion資格隔離，身份仍於PG交易內核對。

新保票先PG重放原指令，再限流；ticket鎖後核對資格並重取PG時間。資格不能跳過price/version/限購/庫存。Redis失效或型別異常503保守拒絕新保票，已成功PLACE重放／cancel／expiry／payment不依賴Redis。旗標預設false，500ms Redis timeout，固定2+2 lettuce threads。無Redis票額預扣、MQ、cluster或吞吐承諾。

## 重要修改檔案
backend/admission的Settings/Service/Controller/RedisResources、redis/*.lua；CustomerAccess與OrderService共享身份及gate；catalog Detail.waitingRoomEnabled；ApiErrors Retry-After；application/pom、AdmissionIntegrationTest及原tests明確admission=false。infra/compose Redis自有AOF/volume/loopback/私有config；scripts/init_demo新增安全Redis生成與--redis-only保留舊DB/staff設定；WAITING_ROOM_API、本交接。

## 測試／build 結果
完整JDK21/Maven package：**110通過、0failure/error/skip**（Redis＋PG等候室22及既有88）。使用PostgreSQL17/ticket_demo與Redis7.4.11，自有scope ticket-admission-20261008，非H2/mock Redis。frontend production vue-tsc＋Vite build通過（此checkpoint仍原M2C介面）。22涵蓋FIFO、固定截止、同UUID6並發、容量、head過期、等待absolute截止、會員／票種、PG限購、state loss／wrongtype、rate bucket、原PLACE重放不耗新限流、身份撤銷、CSRF/unknown owner、真正ticket lock wait跨permit到期拒絕。generator新檔0600、拒覆寫、--redis-only不覆蓋舊DB/staff實測成功。

Redis最初無法讀root600 bind file，已改容器root啟動時複製到私有600 runtime config並chown redis，然後由原docker-entrypoint以redis身份跑server；不放寬host秘密檔。TLS/proxy沿用cloud環境設定，不繞過網路。已核保留正式V4 JAR SHA並於新PG先造catalog/4audit；本輪無migration，V1–V4 bytes不變，safe證據見verification/admission-core-m3-2026-10-08.json。

## 尚未完成事項
本輪下一checkpoint：顧客等候室介面、未知join ACK/重整原UUID、429 backoff、固定permit倒數與重排；正式JAR HTTP/Chromium；實際Redis stop/start與state loss演練、舊V4投影flag兼容核對、PG ledger invariants、清理自有資源。之後M4 Lua/outbox/MQ/worker/出票，M5 cluster與M6壓測。真服務／本機部署仍列CLOUD_LOCAL_FOLLOWUP。

## 已知問題
此checkpoint只有後端，原checkout沒有join介面，啟用admission=true須先經API取得資格；不要宣稱全M3驗收完成。Redis只為入口決策，不能保證與PG commit原子；qualified-check後斷線不盲回滾PG。GET會更新等待心跳並補位，沒有獨立发号worker。每room最多1000有TTL紀錄，terminal保留最大等待期後30分鐘，idle一小時清掉，不是永久audit或永久去重。沒人poll時不主動補位／推播。匿名登入/註冊限流、防bot、IP/跨region公平等仍待補。

自有PG/Redis留給下一驗收；API/preview未運行。其他作品服務／修改未碰。沒有精確usage/context介面，先保存後端checkpoint，不擴大M4。

## 下一步建議
先同步main、重讀此交接、核對真實狀態，再完成上述M3 UI/故障驗收。保持V1–V4 immutable；如需schema用V5以上。原PLACE結果未知先重放同UUID，不換UUID／補Redis票額。
