# Encore 售票平台交接

更新：2026-10-12（Asia/Taipei；private backend log為UTC）。以 actual remote main `47d87d0955faede538fc8f08764575efb8dbe741`起步，fetch／HANDOFF／AGENTS／README／status／branch／log／diff含staged／ff-only；compaction後從repository與原log再確認，未覆寫既有修改。僅 ticket-platform；免費自架、proxy／CA／local Docker socket；managed revision251 unrestricted／current／enforced，無 configured identities/secrets。無精確context／usage介面。先保存可獨立驗證核心，再繼續正式runtime。[上一輪M5C](handoff-m5c-2026-10-12.md)。

## 本次完成內容

M5D核心完成：原current MANAGER明確有限授權票種**未來**已ack正式RELEASE；同PG交易建立原C5 command/progress、C8六次job、不可變source mapping，失敗全rollback。原source boundary／budget不可變、每票種一份policy；ACTIVE→EXHAUSTED／STOPPED，永久停止不能重新啟用。兩Worker policy lock／SKIP LOCKED與source唯一限制去重，發現不連Redis；STOPPED／旗標及current actor在原recovery各獨立phase fence。EXHAUSTED仍可完成已登記job。舊來源不回填，既有人工return只記EXISTING_RETURN，不接管原UUID。尚未提供新管理UI或證明持續高吞吐。操作契約：[STOCK_RETURN_DISCOVERY](../STOCK_RETURN_DISCOVERY.md)。

## 重要修改檔案

- inventory/StockReturnDiscoveryService／Controller／Worker；V23__stock_return_discovery.sql（V1–V22不改）。
- StockReturnStaffService新增register-only交易入口；StockReturnRecoveryStore保留原lease／budget並加policy fence；application.yml／compose.deploy.yml預設關閉及角色旗標。
- StockReturnDiscoveryIntegrationTest新增12項；ApiDeploymentIntegrationTest核對API沒有新scheduler；CatalogIntegrationTest正確要求23份migration。
- run_return_discovery_runtime.py及test-only ReturnDiscoverySeedProbe，準備真正舊artifact升級／故障工具，**尚未跑正式runtime**；test helper不打包。readonly verify_stock_return_discovery_invariants.sql。
- 新操作文件、README／ROADMAP／DEPLOYMENT／C8連結；[核心證據](return-discovery-core-m5d-2026-10-12.json)。原deployment／multi-worker工具由actual JAR核對完整migration序列，不能用舊固定22誤擋新artifact；舊M5B/C容器runtime沒有本輪重跑。

## 測試 / build 結果

全新隔離PG／Redis／MQ上完整backend mvn package成功：**609項／29 suites，failure/error/skip0**、fresh reports、无automatic rerun/flaky；frontend vue-tsc＋Vite production build成功。Maven256MB／suite384MB／SerialGC／CPU2／fork1／reuseForks=false，無跳tests或放寬deadline。3 suites focused30項通過。Python compile、兩份Compose config、git diff whitespace、V1–V22 bytes核對通過；原V22 JAR完整migration序列22通過。

保留兩次失敗：首次focused fixture namespace不符既有test實驗prefix（19項／error1），改own namespace不放寬guard；首次完整609項只有Catalog migration-count expected22／actual23失敗（fail1/error0/skip0），修正assert23後移存全部XML至private first-full-reports、全新scope重跑成功。所有logs與owned cleanup都保留，三個測試scope containers／volumes／networks均0。核心JAR hash見JSON。正式V22→23／兩Worker／停機證據未完成，不套用M5C舊容器結果。

## 尚未完成事項

本階段接續跑新工具：actual V22→V23 old public rows／三raw epochs不變、real staff Session/CSRF、雙nonweb JVM、Redis實際TCP關閉／same-AOF恢復、原source UUID／attempt／CLOSED gate及immutable歷史。Cloud之後仍剩展示操作UI、基本監控／k6、備份隔離還原及本地清單；不是Cloud全完成。

## 已知問題

單PG／Redis、固定一份有限policy／ticket，不能重新授權或reset；來源/generation進展及activeintent仍BLOCKED／REVIEW，不通用自動修復／任意增量同步。停止不撤銷APPLIED、不取消已送Redis指令，原CLOSED gate與immutable receipts供核對。管理員降權不能冒名續做，原手動C5/C6/C7仍可核對，job預算不重開。新UI未接，預扣仍預設false。新runtime採host JVM／雙data容器，避免VFS新增完整app fleet占磁碟；不是新Worker容器驗收／公開TLS／負載／HA。舊V22 baseline、SDK/cache/env/logs在本repoprivate work；完整JDK唯讀使用已安裝路徑，不修改其他repo、不global prune。

## 下一步建議

先以main/HANDOFF與實際status確認後完成M5D正式隔離runtime；成功再更新最終證據與HANDOFF、commit／push。若工具失敗保留log／cleanup，查明後以新空scope再測，不改原UUID、預算、公開TLS或deadline來通過。接著展示操作與監控／k6、備份還原、本地清單。
