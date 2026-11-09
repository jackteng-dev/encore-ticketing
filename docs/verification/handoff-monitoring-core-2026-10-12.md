# Encore 售票平台交接

更新：2026-10-12（Asia/Taipei；private logs UTC）。以 actual remote main `10d37c14681a79f4067d5441bd4ea68beb2df06b`起步，fetch→remote HANDOFF→AGENTS/README→status/branch/log/diff含staged→ff-only，乾淨main；僅ticket-platform。套用cloud-environment-runtime，observed revision256 connected/current/enforced unrestricted、無 configured identities/secrets；沿用proxy/CA及local Docker socket/VFS、2CPU。無精確context/usage介面，先保存已驗證核心再續runtime。[上一輪M5E](handoff-m5e-2026-10-12.md)。

## 本次完成內容

M6A基本監控核心完成：private boot UUID／PG clock operational heartbeat、預期API/Worker數／recent/stale、readonly repeatable-read business backlog/gate/lease/REVIEW/discovery counts，獨立Redis PING／raw MQ passive-only probe（不借Spring shared connection，無declare/publish/consume/ACK）。GET僅current staff READ；不改business/audit、claim/job/budget或pending，flags不當成健康。API心跳不是業務worker。監控預設false；新V24只建runtime_heartbeat操作表，V1–V23不改。操作舊紀錄每pulse最多清100份24h前資料，不動audit。UI `/staff/monitoring`，來源不完整/讀取中/error/monotonic過期都標舊資料，沒有performance/HA宣稱。[契約](../MONITORING.md)。

## 重要修改檔案

monitoring/MonitoringSettings、RuntimeHeartbeat、HeartbeatStore、MonitoringStore、DependencyProbe、MonitoringService、MonitoringController；V24__runtime_monitoring.sql、application.yml/compose.deploy.yml；MonitoringIntegrationTest10項、Catalog migration-count24；frontend monitoring.ts/views/Monitoring.vue/router/Admin連結。scripts/run_monitoring_runtime.py及smoke_monitoring_browser.cjs已準備，尚未正式執行；沿用test-classpath-only舊核心seed，不新增production debug endpoint。新監控文件及[核心證據](monitoring-core-m6a-2026-10-12.json)。

## 測試 / build 結果

完整backend mvn package **624項/30 suites、failure/error/skip0**，新空隔離PG/Redis/RabbitMQ、fresh reports、無automatic rerun/flaky；frontend vue-tsc/Vite production build通過，assets index-DUqxFJ2E.js/index-C2FRQd4L.css。Maven256MB/suite384MB/SerialGC/CPU2/fork1/reuseForks=false，不跳測試或改業務deadline。Node strict syntax/Python compile/git whitespace/V1–V23 bytes核對通過；test probes不在JAR。正式runtime未驗證，不套M5E結果。

首輪624項/30 suites失敗2、error/skip0：fixture未初始化topology，passive probe正確回MISSING；另一案例在publish後未等confirm/可見量就預期1，實際0發生在probe之前。保留所有XML/log/cleanup，明確自有fixture初始化＋real confirm/3s可見性barrier後以全新scope完整重跑成功；不放寬business期限、改queues種類或让監控declare。兩full scopes containers/volumes/network0。JAR hash見核心JSON。

## 尚未完成事項

本階段接續：真V23→24 old public rows不變，兩API/兩host Workers/production Chromium、genuine PROCESSING/outbox/REVIEW/gate，Worker SIGKILL→PG TTL/broker consumers下降→新boot回歸、Redis/MQ真TCP stop/same-AOF、PG真stop/start/readiness及snapshot503/舊UI，API monitoring-off不假零。成功再更新最終HANDOFF/證據/commit/push。

Cloud仍剩k6環境限定report、備份隔離還原、操作文件與本地清單；本輪不是Cloud全完成。

## 已知問題

心跳只證明最近PG回報；consumer-running僅lifecycle，broker consumers另讀。MQ ready不含unACKed，不宣稱無積壓或exactly-once；不同dependency取樣非跨服務原子。Global聚合不授予操作其他manager的UUID。PG statement1.5s/transaction4s，IO在PG交易外、probe有界；disabled不探測、不把空counter當零。新Worker先等API升V24。監控沒有Prometheus/Grafana/告警或HTTPserver latency counters，k6/SLA/Cluster/HA另做。VFS只用data containers/hostJVM、不新增完整app fleet。work/m6a-20261012保留private raw/fixture/SDK/cache/reports；不公開、不修改其他repo/下載model/付費/global prune。

## 下一步建議

先fetch main/read本交接/status/diff確認，再完成這一個M6A正式runtime。保留任何失敗與owned cleanup，不改原UUID/budget/deadline；成功後保存最終證據。下一階段k6有界負載及業務效果核對→備份隔離還原→操作/本地清單。不要再追加大型功能才第一次保存。
