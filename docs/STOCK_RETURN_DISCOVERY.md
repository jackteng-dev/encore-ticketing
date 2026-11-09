# M5D 新返還來源的有界自動發現

原 MANAGER 明確授權某票種的**未來**取消／到期 RELEASE，Worker 在 PostgreSQL 登記既有 [員工返還](STOCK_RETURN_STAFF.md) 與 [六次背景恢復](STOCK_RETURN_RECOVERY.md)。不需要逐筆先建立返還 UUID。V23 新增 policy、永久停止、不可變來源映射及 policy audit；V1–V22 不改。PG 是票額來源；發現交易不連 Redis。

## 授權與 API

同源員工 Session／CSRF，寫入由驗證身分取得 actor；可帶 `X-Expected-Staff-Username` 核對原操作員。body 不接受 actor／數量／Redis balance。

| 方法與路徑（前綴 `/api/admin/stock/return-discovery`） | 內容 |
|---|---|
| POST `/tickets/{ticketId}/policies` | `{requestId,maxSources}`，current MANAGER 建立，maxSources 1–100 |
| GET `/policies/{requestId}` | 本人 PG-only 狀態／version／startRevision／discovered／enabled |
| GET `/policies/{requestId}/sources?page=1&size=12` | 本人 immutable order／releaseRevision／disposition／原 returnRequestId；size≤20、page≤100000 |
| POST `/policies/{requestId}/stop` | `{requestId,expectedVersion}`，原 current MANAGER 永久停止 |

授權將當時 stock_projection source_revision 固定為 startRevision；只找更晚的 ORDER_RELEASE，而且必須有精確原 revision/context、同訂單的 COMMIT 決定與 COMMITTED ack。較早來源永不回填，未有 ack 的未來來源稍後才可發現。每票種一份永久 policy，不能改 manager／邊界／預算、重新啟用或 reset。此限制讓展示工作有固定上限；持續長期營運的授權輪替另做。

新 UUID 在單一 PG 交易中建立 staff command、REGISTERED progress、固定六次 recovery job、來源映射及 audit；其中任一失敗整筆 rollback。兩 Worker 透過 policy row lock／SKIP LOCKED 及 order/revision 唯一限制去重。已有人手動或內部返還的來源記為 EXISTING_RETURN 並耗一份來源預算，不接管別人的 UUID；returnRequestId 為 null。ENROLLED 才回傳自己的原 UUID。每輪批次有界，EXHAUSTED 不再找新來源，但已登記工作仍可完成。

未知 enrollment／stop 回覆先用原 UUID 查 PG，再 exact body 重送；不換 UUID、加 budget 或改 version 猜測。精確 enrollment／stop replay 在旗標關閉時仍可核對。其他 MANAGER 不能讀本人 policy；本人降為 VIEWER 只能讀，不能寫或代為自動續做。M5E已接 [本人管理畫面及原命令查證](STOCK_RETURN_DISCOVERY_UI.md)。

## 自動工作與停止邊界

新功能預設 false，API 與 Worker 需一致配置及重啟：

| 設定 | 預設 | 範圍 |
|---|---|---|
| TICKET_STOCK_RETURN_DISCOVERY_ENABLED | false | 新 policy、來源發現及 discovery-origin 自動恢復 fence |
| TICKET_STOCK_RETURN_DISCOVERY_BATCH | 4 | 每輪 1–20 個來源，逐筆交易 |
| TICKET_STOCK_RETURN_DISCOVERY_POLL_MS | 1000 | fixed delay，首次延遲 5 秒 |

另需 `TICKET_STOCK_RETURN_ENABLED=true` 及 `TICKET_STOCK_RETURN_RECOVERY_ENABLED=true`；每個 job 沿用六次 attempt、PG clock lease、有限退避、不可變 claim/audit。Compose 已傳遞 discovery flag；要改 batch/poll 需明確將環境設定傳給 app。API profile 沒有 discovery scheduler；Worker 無 HTTP、不做 migration/bootstrap，先由新 API 完成 V23，再啟動新 Worker。

STOPPED 不可逆，停止找新來源，並在 discovery-origin recovery 每個 PG phase 的 Redis 前／後核對原 policy。旗標關閉、原 manager 停用／降權同樣阻止自動續做；工作保留原 UUID／attempt，進 REVIEW 供人工核對。EXHAUSTED 只停止新來源。停止不撤銷已 APPLIED 的返還，也不能取消已送出的 Redis 命令；可能仍有已送命令結果待核對，PG 原 CLOSED gate／lease／immutable epoch 仍是安全邊界。手動原 C5/C6/C7 核對途徑保留，不重開 job budget。

返還使用原 RELEASE 與完整 PG snapshot 建立 fresh generation，原 COMMITTED epoch 保持不可變。來源／generation 推進、active intent、缺少證據仍 BLOCKED／REVIEW，不自動改 snapshot、增加票額或換 UUID。沒有通用增量 writer／任意損壞台帳修復，沒有證明持續高吞吐，也不自動啟用顧客預扣。

## 可重現驗收

12 項新增真 PG／Redis integration cases 驗證未來邊界、ack、exact replay、停止、預算、既有手動來源、並行發現、交易 rollback、角色／來源 fence、不可變歷史及批次界線。完整 package／build、正式 runtime 結果以 [HANDOFF](HANDOFF.md) 為準。

`scripts/run_return_discovery_runtime.py` 只接受新空 owned scope `ticket-discovery-runtime-YYYYMMDDhhmmss`、private work env、明確 loopback HTTP、新 package 和已核對 SHA 的原 V22 JAR。先用 V22 真核心服務建合成資料，再以新 API 升 V23；正式 cancellation 由 test-classpath-only helper 呼叫真核心，不新增 debug endpoint。流程涵蓋 real Session/CSRF API、兩個實際 nonweb Worker JVM、Redis 真停機／same-AOF 恢復、未來／歷史／永久停止 policy、原 UUID／attempt、舊 epoch 不變及 readonly invariants。

此工具使用 host JVM 和兩個資料容器，降低 Cloud VFS 磁碟複製；M5C 的雙 Worker 容器證據為另一輪，不當成本輪新功能的容器驗收。原 V22 JAR 必須在 package 前保留於本倉庫 work，SHA 必須為 `f5d9cc294c114ffd19c107c86985c64a061a66e6b227cd55d399aa146729e7a0`。完整 JDK21 可指定已安裝的唯讀路徑。先用 `init_demo.py --loopback-http --env work/<新目錄>/runtime.env` 建 private env，再明確設上述唯一 project 及 `encore-demo-discovery-<時間戳>` namespace；保留原帳密／proxy／CA，不覆寫舊 env。

```bash
python scripts/run_return_discovery_runtime.py \
  --env work/<新目錄>/runtime.env \
  --baseline-jar work/<保留目錄>/baseline-v22.jar \
  --java-home <已安裝完整JDK21> --output work/<新的證據目錄>
```

結果、原 body/session、logs 全在 ignored work／0600。工具 finally 停止自己啟動的 JVM，只 dispose 自己的新空 PG／Redis project，核對 container／volume／network 為 0；不 prune 或操作其他作品。readonly `verify_stock_return_discovery_invariants.sql` 在預定 DB 的 READ ONLY 交易核對原來源／job／audit／停止／預算，需同時跑原 return／recovery／origin 檢查。這是 correctness/fault 驗收，公開 TLS、負載／跨主機 HA、管理 UI 已由 M5E另驗收。

本輪正式6組首次通過：真V22→V23的68原表／三raw epochs不變、同源員工Session/CSRF、兩真nonweb JVM、Redis真TCP停機與same-AOF、原job attempt2 DONE／APPLIED／READY、雙Worker重啟不reset UUID／budget，30 readonly checks零違規。完整609項／29 suites、型別檢查與production build成功；所有自有程序停止、container／volume／network0。[正式證據](verification/return-discovery-runtime-m5d-2026-10-12.json)。不是新版本雙Worker容器或負載驗收。
