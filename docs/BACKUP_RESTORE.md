# M6C PostgreSQL 備份與隔離還原

目前支援本倉庫自有、停止應用程式的 `ticket_demo` V24 示範資料庫與同版本PostgreSQL。
使用private `work/`設定、local Docker socket與具scope label的容器，不接受任意外部DB或覆寫來源。
這是有界邏輯備份／還原與工作恢復；不等於正式主機、跨版本、PITR、角色／密碼管理或高可用演練。

## 備份契約

先停止該scope的API／Worker及其他DB client，並關閉新流量；工具會檢查當時沒有其他連線。
操作者仍須維持整個窗口離線，單次連線檢查不能阻止後續有人重新啟動服務。
`pg_dump --format=custom --no-owner --no-privileges`保存資料／schema／constraints／functions與序列。
來源前後在單一READ ONLY取樣中核對每表row count／SHA256、sequence state及function count，任何變動拒絕保存成功manifest。
V24 Flyway版本／checksum另列manifest；不baseline／repair／改舊migration。
示範上限：100 user tables／100 sequences、64MiB archive；更大正式資料需另外規劃流式核對與備份資源。

```bash
python scripts/backup_restore.py backup --env work/<來源>/runtime.env \
  --output work/<全新備份目錄>
```

備份含密碼hash、Session證據、個資與bearer票碼；directory0700、dump／manifest0600，禁止提交或公開。
checksum檢查完整性，不提供簽章或防惡意替換；只還原可信來源的private bundle。
本輪沒有加密、排程、離機副本與保留政策；正式部署前另補。

## 新空目標還原

目標必須是另一個明確自有scope與不同PG container，停止所有應用程式；不能有user tables／views／sequences／functions。
先驗custom archive header／SHA256／TOC、major version與來源ID，再執行
`pg_restore --single-transaction --exit-on-error --no-owner --no-privileges`。
不使用`--clean`或`--create`，不刪目標既有資料；非空目標／來源目標立即拒絕。
中途restore失敗應整筆rollback、目標仍空；成功後完整row／sequence指紋必須相等才保存新receipt。
成功SQL但核對不符時保持服務停止，保存目標與日誌調查，不強制覆寫或假稱恢復完成。

```bash
python scripts/backup_restore.py restore --env work/<全新目標>/runtime.env \
  --backup work/<備份目錄> --receipt work/<全新還原收據>.json
```

## Redis／RabbitMQ 與未完成工作

此次PG邏輯備份不包含Redis AOF、MQ磁碟、檔案儲存或外部provider。
還原使用新的Redis namespace與新的MQ vhost／namespace，不把空快取當READY，也不從舊快照盲目SET／INCR。
新購票、預扣、付款／出票／驗票寫入等旗標先保持false；既有結果仍可讀／用原UUID核對。

未發布Outbox：由正常publisher依原receipt／lease處理，不重設attempt／deadline。
已確認發布但仍PROCESSING：新broker不會有原訊息，而PG `published_at`仍保留。
這時以**原eventId／purchaseId／schemaVersion**重送，不把receipt改回未發布、不換UUID。
先用原RESTORED receipt核對實際target container、loopback PG/MQ映射與離線指紋，再啟動獨立nonweb CLI。
CLI不做migration／bootstrap／業務排程／消費／HTTP；每輪最多100個仍在原期限內的已確認pending事件。
新publication、terminal、到期或不存在事件不重送；未知confirm須保留原身分再核對，不能當已成功。

```bash
python scripts/backup_restore.py replay --env work/<目標>/runtime.env \
  --receipt work/<還原收據>.json --java-home <完整JDK21> \
  --output work/<全新重送收據>.json
```

CLI是operator操作，沒有public debug endpoint。不得繞過Python目標驗證直接對來源執行。
重送後PG原資料必須不變；接著才啟動目標Worker（publisher／consumer／expiry／stock recovery），API仍關新購票。
至少一次訊息可能重複，正式consumer按原事件去重；只有PG保票commit才是訂單成立。
還原後的原deadline照DB clock判定，逾時就EXPIRED，絕不延長期限讓恢復看起來成功。
原Redis generation缺失時，既有PG決定可取得KEY_MISSING證據，gate仍DIRTY；這不是重新開賣或重建原Redis收據。
未完成job保留原UUID／租約／預算，不能一律改DONE、清REVIEW或加重試次數。

## 實際驗收

```bash
python scripts/run_backup_runtime.py --output work/<全新驗收目錄> \
  --java-home <完整JDK21> [--compose-override work/<自有image-only-overlay>.yml]
```

使用正式JAR與真實HTTP建立中文資料、PAID／CANCELLED／PENDING訂單、兩張票及一次驗票、已發布／未發布pending、真正Redis預扣。
停止來源後備份，還原到另一PG／Redis／MQ；兩次重送同一個已發布原事件，再由正式Worker恢復。
檢查原票碼／驗票proof、原owner Session、關閉新寫入、一次保票、真實跨20秒sales deadline補掃、缺失Redis證據與READ ONLY不變條件。
來源PG rows／sequence及原Redis keys／MQ queue counts須不變。
另建第三空PG驗損壞checksum及真正payload restore錯誤／rollback；非空及來源目標拒絕。
finally只停止自有程序／清自己的拋棄式scope，保留private fixture、backup及失敗日誌。
工具存在不代表通過；實際結果見HANDOFF與verification。正式本地完整服務／持久資料還原仍另驗收。


本輪已完成10組正式驗收／67項零違規，完整627項package與前端build通過；[結果](verification/backup-m6c-2026-10-12.md)。來源74表／178筆且沒有sequence，不宣稱非空sequence恢復已測。演練期間來源API／Worker始終停止，正式切換也須避免兩份資料庫同時對外履約。
