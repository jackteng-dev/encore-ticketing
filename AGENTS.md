# Ticket Platform 工作規則

本倉庫為獨立售票作品，包含 frontend Vue、backend Spring Boot、infra 與 docs。只修改本倉庫；不修改或清理 ELPSY Shop、發呆亭、Atlas、日日選物與展示平台。使用免費自架方案；正式付款、真人通知、實體設備與公開部署另列 docs/CLOUD_LOCAL_FOLLOWUP.md。

## 每輪流程
1. 先 git fetch origin main；以遠端 main 為基準，保留未提交修改，不 reset 或 force push。首次建立沒有遠端／歷史交接時如實記錄。
2. 先讀遠端 docs/HANDOFF.md，再讀 AGENTS／README 與相關原始碼。
3. 核對 git status、branch、log、diff（含 staged），正常後以 ff-only 同步；分歧先查明。
4. 每個獨立里程碑跑相關測試與 production build：backend mvn package 不跳測試，frontend npm run build 含型別檢查；交易使用真正隔離 PostgreSQL，不用 H2 假稱驗證。
5. 通過後更新 HANDOFF，至少包含完成內容、重要檔案、測試/build、未完成、已知問題、下一步；實測和歷史證據分開。
6. 每個里程碑在功能分支 commit／push 並開 PR；main 受分支保護（2026-11-09 起：必須 PR、`verify` CI 通過才能 merge，管理員同受限，禁止 force push），CI 通過後一般 merge，由自架主機 CD 自動部署。
7. 最後確認乾淨 status、PR 已合併或狀態已記錄，合併後 HEAD 與遠端 main 一致；受阻就記錄，不偽稱已保存。

## Context 安全
- 長任務若可取得 context/token/usage 主動檢查，不捏造數字；沒有介面就保守分段。
- 不等耗盡才交接；下一階段前預留測試、build、HANDOFF、commit、push 與遠端核對的額度。
- 判斷不足時立即停止新增大型修改；交接保存優先於繼續實作。
- 每個可獨立驗證的里程碑即保存，不等大型任務結束。
- compaction／資訊遺失／狀態不明時從 repository、status、diff、log、HANDOFF 與遠端重建實際狀態。

## 安全與一致性
- 遵守當前 proxy／CA／網路政策，不關 TLS 驗證或繞過限制。
- 帳密、token、原始日誌、fixture、資料、依賴、SDK、build 均留 ignored 路徑，不打印秘密。
- DB／Redis／MQ／bucket 都使用自己明確隔離的資源；只清理自己建立的 scope，不套原資料、不 baseline／clean／repair。
- 已發布 Flyway migration 不改 bytes/checksum；schema 用新增版本。
- owner/actor 只取驗證身分。寫入權限、版本、業務及audit同交易；未知回應重試原 UUID/body，衝突先讀，不盲目改 ID 重送。
- PostgreSQL 是正式票額／訂單的來源；Redis Lua 不等於跨 DB/MQ 原子交易。未知結果不盲目 INCR，TTL 不等於自動補回。
- 多個 API／Worker 共用業務程式，不先拆完整微服務；Redis Cluster 熱 key、same-slot Lua、非同步複寫與恢復必須實測。
- RabbitMQ 以至少一次處理與去重設計，ACK 在 DB commit 後；不宣稱 exactly-once 或單機容器等於跨主機容錯。
- 依 docs/ARCHITECTURE.md 分階段。購票尚未實作時必須明示 orderingEnabled=false，不把頁面或 mock 當真實收款。
