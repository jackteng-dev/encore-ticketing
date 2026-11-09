# Encore 售票平台交接

更新：2026-10-11（Asia/Taipei）。起點 actual remote main `53647edd957b74c362a2c1af069d2db2b053ec23`。fetch→remote HANDOFF→AGENTS／README→status／branch／log／diff含staged→ff-only；原clean，只改ticket-platform。免費自架、managed connected／observations_current／enforced unrestricted revision245、無configured secrets或outbound identity；保留proxy／CA／local Docker socket，host2CPU／8GB cap。無context／usage精確介面，分實作保存與正式runtime證據兩里程碑。上一輪：[M5A交接](handoff-m5a-2026-10-11.md)。

## 本次完成內容

M5B部署基礎：同一JAR api／nonweb worker profiles；API不註冊publisher、order/purchase expiry、stock recovery／return recovery排程，MQ listener不啟動，能力旗標仍保留給原checkout／predebit gate。Worker無HTTP、不bootstrap、不做Flyway，旗標opt-in；混用profiles／錯web mode／worker bootstrap fail closed。未設profile維持原單機demo。

Nginx直接提供production dist、啟動／reload時解析Docker DNS的雙API round-robin／passive failover，只有gateway映射loopback15184，其餘PG／Redis／MQ／API／Worker不publish host port。App／gateway nonroot／rootfs唯讀／tmpfs／cap_drop／資源cap；基底image固定digest。Dockerfile僅COPY verified產物，不下載依賴／不傳work／secrets，沿用daemonproxy；外部HTTPS仍需另外read-only CA及JVM truststore。PG-backed readiness只回狀態／no-store；不是MQ／Redis或Worker健康承諾。

未知POST不對timeout／HTTP5xx自動改投其他API，不啟用non_idempotent。私有HTTP gateway覆寫Forwarded headers、API不信任scheme／身份；TLS公開部署另驗收。Cloud收尾固定為部署／持續庫存與多Worker／監控k6／備份隔離還原／展示管理及文件，不把完整商用退款／真金流／MFA／跨主機HA作切換下一專案的前置。

## 重要修改檔案

- TicketApplication／MqConfiguration與五個排程component：profile角色分工。
- application-api.yml／application-worker.yml、runtime DeploymentProfileGuard／ReadinessController。
- ApiDeploymentIntegrationTest4／DeploymentProfileGuardTest7；既有StaffSessionIntegrationTest並行fixture補真交易／同步起跑，不修改正式登入行為或限額。
- infra/compose.deploy.yml／app.Dockerfile／gateway.Dockerfile／nginx.conf、.dockerignore。
- init_deployment.py／run_deployment_runtime.py／smoke_deployment_browser.cjs：private scope／真container故障／production Chromium／原UUID恢復。
- DEPLOYMENT／ROADMAP／ARCHITECTURE／README／CLOUD_LOCAL_FOLLOWUP與交接archive。

## 測試 / build 結果

**最終完整 backend mvn package成功，597項／28 suites、failure/error/skip0，無自動rerun／flaky。** 真隔離PG／Redis／MQ，新空scope ticket-deploy-full-m5b-final；Maven256MB／suite384MB／SerialGC／ActiveProcessorCount2／forkCount1／reuseForks=false，不跳tests或放寬timeout。final JAR SHA256 `f5d9cc294c114ffd19c107c86985c64a061a66e6b227cd55d399aa146729e7a0`。正式containers runtime已完成API／瀏覽器部分，Worker端到端尚未通過。

- focused角色11及修復後員工＋角色36均通過，failure/error/skip0；真PG／Redis／MQ。
- frontend vue-tsc＋Vite production build成功，source／lock不改；JS index-C7NWNqKc.js、CSS index-2jdYrfsH.css。
- Python compile／Node syntax／Compose config／diff whitespace通過；generator Secure預設true、不bootstrap／不開checkout、0600；拒絕覆寫既有env並驗證hash不變。
- 首次focused因JAVA_HOME雖為JRE21但javac不完整，compiler報release21不支持，尚未跑tests。換成本倉庫ignored完整JDK後手動新log通過，保留失敗log。Maven/JDK及依賴cache複製至本repo work；只讀取既有tools、不修改其他repo或cache。
- 首次完整package實際597／28 suites、failure1/error0/skip0；原員工並行測試new出的第二service未經Spring@Transactional代理，account lock未持到整個login，cap有7。修fixture用真TransactionTemplate包兩instance與latch同步；36focused通過。保留首次failure／全部reports，非自動rerun或放寬timeout。第二次完整package另建新空隔離scope，597項全通過，無rerun/flaky。
- 第一次nginx -t發現未使用的FastCGI／uwsgi／scgi module仍要建立預設temp dir，與readonly rootfs衝突；將所有temp path明確放/tmp後重建gateway並另存驗證log，不放寬readonly。
- Flyway V1–V22 bytes與起點一致，無新增schema；production測試helpers不打包，runtime工具／cookies／body／logs都在private work。正式containers runtime另列實測，不能套用歷史M5A proof假稱已驗。

## 尚未完成事項

M5B剩餘正式Worker處理／POST timeout／PG停機／並發登入runtime待驗；多Worker／持續stock來源自動協調、監控k6、備份隔離還原與展示管理另排。HTTPS／DB least privilege與migration帳號分離、跨主機HA／Cluster、本地實機與真金流另驗收。

- 後續實測修正init_deployment的Redis namespace為既有encore-demo-*契約，不放寬AdmissionSettings；第一次啟動失敗的scope／logs與cleanup0保留。
- 動態resolve停API後多輪首次／連續GET504；不是只有首次。改採啟動／明確reload解析上游，API-a停機後8秒有界探測、其後20筆嚴格成功、原browser pending／cookie／UUID及一個effect、API-a回歸經reload不重啟gateway皆通過；本地驗收界線非SLA，不宣稱無縫或自動service discovery。
- 真API／Nginx container共8項checks及browser5項已通過（runtime-static-proof）；两API無Worker仍durable受理且6秒後零保票／零published。原helper預設版本與覆寫keyword重複TypeError已修成dictionary override，保留該失敗log。
- Worker真處理仍未通過：首次完整場景broker連線reset／queue未建，60秒無RESERVED，保留worker私有log；不把running當成功、不延長deadline／放寬原購票timeout。另建隔離診斷在原384MB broker cap成功建3quorum queues，broker／worker均restart0、OOM=false，不能宣稱OOM或調額度掩蓋；診斷工具因log命名碰撞未寫worker log，finally已dispose。下一次正式場景新增broker／PG／Redis logs及restart/OOM狀態保存以確認實際原因，當前仍在驗收中。

## 已知問題

Nginx只有passive failure，不會active readiness-aware負載分流；單PG／Redis／MQ及restart policy不是HA。Worker停機時配置能力仍true，API可受理PROCESSING，由原deadline約束，不能宣稱保票成功。gateway僅private loopback HTTP、Secure=false只因explicit fixture選項；不能直接公開。Worker無healthcheck，Compose running不等於已完成初始化，正式驗收需確認真訊息處理。

各先前失敗runtime／診斷scope均dispose，cleanup報告剩餘0；當前只有本輪明確own scope持續驗收，不清其他資源。首個full scope已dispose；最終full scope亦已dispose，兩scope各3containers／3volumes／1network剩餘0；runtime另建新空scope；不清其他repo或global Docker。私有work/m5b-20261011保存0600/0700失敗與成功log／fixture／tools，不提交秘密。

## 下一步建議

實作／597 tests已保存cb68a41；本段static route／generator修正及誠實未完成狀態獨立保存。先確認正式場景Worker broker reset原因，再用固定產物完成Worker／真正POST timeout／PG stop及六個並發登入runtime；更新證據與清理再獨立commit／push。M5B完成後下一輪先依remote main／HANDOFF，建議多Worker競爭與冪等恢復；不要直接宣稱Cluster／HA／大量真流量。
