# Encore 展示操作手冊

範圍：免費mock、全新隔離合成資料、loopback HTTP，約15分鐘操作。進度／證據見[FEATURE_STATUS](FEATURE_STATUS.md)，本輪核對見[M6D](verification/showcase-m6d-2026-10-12.md)。真手機、HTTPS與正式營運另驗。不要使用真帳號／個資／票碼公開截圖。

## 1. 全新環境與完整測試

需JDK21、Maven3.9、Node>=22.12、Python3、Docker Compose v2；下列在repository根目錄的bash／WSL2執行。確認25432／26379／25672／19110／15180未被其他作品占用；不足磁碟／記憶體先停下，VFS尤其不能只看image標示大小。

```bash
python scripts/init_demo.py --loopback-http --env work/showcase/runtime.env
python scripts/init_mq.py --env work/showcase/runtime.env
docker compose --env-file work/showcase/runtime.env -f infra/compose.yml -f infra/compose.mq.yml up -d --wait
set -a
. work/showcase/runtime.env
set +a
mvn -f backend/pom.xml package
(cd frontend && npm ci && npm run build)
```

生成器拒絕覆寫，帳密0600留在private env，不打印。測試只跑拋棄式scope，不對展示保留資料跑fault constraints；本輪Cloud使用自有flat PG/MQ overlay避免VFS空間不足，這不是本機必備設定，普通本機用官方compose即可。

測試後停止自己的scope並**保留volumes**：
```bash
docker compose --env-file work/showcase/runtime.env -f infra/compose.yml -f infra/compose.mq.yml down
```

测试fixture可能有殘留，展示另建新的路徑／project：
```bash
python scripts/init_demo.py --loopback-http --env work/showcase-demo/runtime.env
python scripts/init_mq.py --env work/showcase-demo/runtime.env
```

同一路徑不能重跑初始化；既有展示重啟直接用原env。private env以本地編輯器設定下列**每個key只保留一個值**，別將範例覆寫整份帳密。建立活動與購票之前即可開啟：

```dotenv
TICKET_ORDERS_ENABLED=true
TICKET_PURCHASES_ENABLED=true
TICKET_DELIVERY_ENABLED=true
TICKET_PUBLISHER_ENABLED=true
TICKET_CONSUMER_ENABLED=true
TICKET_EXPIRY_ENABLED=true
TICKET_ADMISSION_ENABLED=true
TICKET_MOCK_PAYMENTS_ENABLED=true
TICKET_ISSUANCE_ENABLED=true
TICKET_CHECK_IN_ENABLED=true
TICKET_ADMISSION_WINDOW_ENABLED=true
TICKET_MONITORING_ENABLED=true
TICKET_MONITORING_EXPECTED_APIS=2
TICKET_MONITORING_EXPECTED_WORKERS=2
TICKET_PREDEBIT_ENABLED=false
```

這份簡單展示只有1個EMBEDDED。監控保留預期API2／Worker2，所以會顯示未啟動獨立角色的警示；不是全部節點健康。預期數只接受1–16，不能填0。雙API／Worker另走[DEPLOYMENT](DEPLOYMENT.md)＋[MULTI_WORKER](MULTI_WORKER.md)，預期counts仍2／2；部署generator的async選項不自动開mock／票券等旗標，Compose新增旗標須核對app environment是否傳入。

```bash
docker compose --env-file work/showcase-demo/runtime.env -f infra/compose.yml -f infra/compose.mq.yml up -d --wait
set -a
. work/showcase-demo/runtime.env
set +a
java -jar backend/target/ticket-platform-0.1.0.jar
```

另一終端：
```bash
cd frontend
npm run preview
```

網站 http://127.0.0.1:15180 ，API http://127.0.0.1:19110/api 。實際瀏覽器以15180同源代理登入，不能混用localhost與127.0.0.1。Secure=false僅明確loopback；Vite preview不是正式公開代理。

## 2. 員工建立活動與窗口

1. /staff/login 使用自己private env的manager，點「登入後台」。
2. /staff 點「＋ 新增」，例如名稱「Encore 合成展示」、代碼唯一小寫短橫、合成場地／介紹，點「建立草稿」。
3. 「新增場次」：活動開始設明天，售票開始設現在前1小時、結束設現在後1小時。**這裡輸入是瀏覽器本地時區**，公開頁顯示台北；不用過期固定日期。
4. 展開「＋ 新增票種」，票價1200、總額12、每人限購2，新增後發布活動，確認發布視窗。發布後條件鎖定，不直接SQL改票額。
5. /staff/admissions 選此活動／場次，設定「開放入場（台北時間）」為現在前5分鐘、「結束入場」為後1小時，點「保存場次時間窗」。**此輸入固定UTC+8**，與場次表單不同；等OPEN後再驗票。未設定會拒絕新入場。

## 3. 顧客→mock→領票

1. /account/login 點「還沒有帳號？建立帳號」，獨立合成帳號／12–72字元密碼；不要使用真密碼。顧客與員工cookie分開，同瀏覽器可並存。
2. 首頁→活動→「選擇張數」，「加入等候室」，資格有效期間內選2張與「先送出購票請求」，送出。
3. /account/purchases 顯示PROCESSING尚未保票；輪詢到RESERVED才點「查看本人訂單與付款」。若Worker未存活，可能逾原受理deadline，不能另送同一筆購票。
4. /account/orders 選模擬成功→「建立模擬付款」→「核對原模擬付款」，PAID後點「查看電子票券」→「領取電子票券」。預期2張原票券，頁面可看QR；不輸入卡號／不真扣款。
5. 用另一個合成顧客另建1張未付款保票（原顧客已達限購2張），點取消，核對CANCELLED及可售回升；取消原訂單不再保票，未知結果先核對原UUID。

## 4. 員工核對與一次驗票

1. /staff/admissions 選正確活動／場次，確認窗口OPEN。
2. 以顧客QR圖片讀碼，或手動填原票碼，點「核對票券」。圖片／相機只填碼，不能自動入場。
3. 點「確認驗票入場」，預期CHECKED_IN。重掃同碼應顯示已入場，不能再新增效果；不拿新UUID當第二次驗票。
4. 第二張未用票可明確撤銷，預期REVOKED；不退款、不補票額。VIEWER只看metadata，不取得bearer票碼或寫入。
5. /staff/monitoring 重讀：EMBEDDED心跳、PG backlog／gate、Redis／MQ探測。心跳與ready count不是所有工作或跨服務原子健康證明。手機相機留本地驗收。

## 5. 工程故障與限制展示

先展示正常流程，再展示去識別歷史證據；**不要在保留資料上隨手故障注入**。要重跑腳本先讀契約並另建owned disposable scope：

| 展示項目 | 文件／證據 | 必須說明 |
|---|---|---|
| 雙API／Worker停機、未知POST／原UUID恢復 | [Deployment](DEPLOYMENT.md)、[Multi worker](MULTI_WORKER.md) | 舊V22容器證據；IP變動reload，初始502/504可能，不承諾無縫HA |
| 同步／非同步／預扣有界比較 | [k6](LOAD_TESTING.md)、[M6B](verification/load-m6b-2026-10-12.md) | 60人12票小批量；預扣8張後DIRTY、餘4不接續，不當持續吞吐 |
| 基本監控與依賴故障 | [Monitoring](MONITORING.md) | readonly，不修改票額／job，不清REVIEW |
| offline備份／隔離還原／pending重送 | [Backup](BACKUP_RESTORE.md)、[M6C](verification/backup-m6c-2026-10-12.md) | 停來源、空目標、新流量disabled、KEY_MISSING不開gate，不雙邊履約 |

預扣診斷另依[STOCK_CHECKOUT](STOCK_CHECKOUT.md)啟用preDebit／maintenance／recovery等旗標，票種須由原管理員明確建立READY；普通展示預設不開。DIRTY拒絕是目前已知限制，不能reseed／INCR、換UUID、放寬deadline或重設budget包裝成功。

## 6. 停止、保留、再啟動

Ctrl+C停止自己API與preview，再對**原env的project**執行：
```bash
docker compose --env-file work/showcase-demo/runtime.env -f infra/compose.yml -f infra/compose.mq.yml down
```

不要加-v；下次up用同env及volumes，重新export旗標，再啟動同版本JAR／preview。cookie最多30分鐘到期須重新登入，保票仍用原DB期限；跨停機到期由expiry補掃，不改deadline。公開展示不可提交env／cookie／session／QR bearer code／raw logs／backup。

測試工具在明確owned disposable scope才可down -v；本手冊保留展示資料不用-v。最新fleet／HTTPS／真手機／容量／完整restore的本地清單見[CLOUD_LOCAL_FOLLOWUP](CLOUD_LOCAL_FOLLOWUP.md)。
