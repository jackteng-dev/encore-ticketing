# M5E 自動返還管理畫面與原命令恢復

員工 `/staff/stock` 選擇票種後，在「自動返還授權」讀取**本人** policy、固定起點／額度、來源及原背景工作。MANAGER 明確授權未來來源，永久停止須再確認；VIEWER 只能讀，不能建立或停止。設定預設關閉，API／Worker 仍依 [M5D 契約](STOCK_RETURN_DISCOVERY.md) 配置，不因畫面出現而自動啟用預扣或背景功能。

## 操作與完成證據

- 最多來源數 1–100；保存時固定 source revision，不能重設／輪替，也不回填歷史。每票種一份 policy，其他員工的授權不能接管。
- ACTIVE 持續尋找有限未來來源；EXHAUSTED 只停止新來源，既有工作仍可完成；STOPPED 永久停止自動續做。不撤銷已完成返還或取消已送出的 Redis 指令。
- 每筆 ENROLLED 來源可讀原 C5 返還及 C8 六次背景工作。REGISTERED／WAITING／額度耗盡不代表返還完成；畫面必須核對原 UUID／ticket／order／releaseRevision、完整 receipt 與 job。DONE 與 APPLIED 不一致會顯示查證錯誤。EXISTING_RETURN 僅展示原來源，不接管其 UUID。
- 來源 size12、有界分頁；新讀取清除舊詳細結果，切票種／離開頁面的遲到回覆不覆寫現在的票種。讀取錯誤時禁用新的授權／停止，仍可重讀／核對原pending。不自動輪詢或重送寫入；用重讀按鈕核對。

## Pending 與未知結果

提交前將原 owner、path、UUID、maxSources／expectedVersion 寫入既有 sessionStorage pending。未知或不完整回覆不清除 pending，不換 UUID；跨活動管理／庫存／驗票共用凍結。重新整理後即使尚未選票種，仍可從管理 panel 核對原操作；其他登入員工不能接管。

授權 POST 有完整且匹配原 body 的 policy 回覆才確認。回覆遺失後先讀 enrollment proof：APPLIED 須完整本人 policy／原 body；UNKNOWN 才由原 current MANAGER 重送**同一份** body；SUPERSEDED 表示永久唯一 policy／UUID 綁定使這份 body 已不能套用，不當成本命令成功、不暴露另一份 policy。

停止須核對**原 stop UUID**，不能只看到 policy STOPPED 就猜是自己成功。先讀 stop proof；UNKNOWN 才能重送原 body。POST 後也以原 stop proof 查證；APPLIED 才確認原命令。SUPERSEDED 表示版本已不可逆推進或 policy 已永久停止，原未找到的內容永不可能匹配；解除 freeze 後必須重讀，由使用者明確決定新的停止操作，不自動改 expectedVersion／UUID。

owner VIEWER 可做 PG-only 查證，若已 APPLIED／SUPERSEDED 可以解除這筆已查證 pending；UNKNOWN 仍保留，不能 POST。旗標關閉仍可讀歷史及原命令、exact replay／明確停止。成功 ACK 後 GET 錯誤不重新建立 pending。

## 新增 PG-only 讀取 API

前綴 `/api/admin/stock/return-discovery`，GET 需同源員工登入／CATALOG_READ；actor 取驗證身分。沒有新 migration，V1–V23 bytes 不改。

| 路徑 | 回覆與限制 |
|---|---|
| `/settings` | 三個返還旗標的共同 enabled；不表示 Worker 健康 |
| `/tickets/{ticket}/policy` | 本人 policy，沒有可讀本人授權為404；其他 MANAGER 同樣不能讀 |
| `/tickets/{ticket}/policy-requests/{request}?maxSources=N` | `{requestId,ticketId,maxSources,state,policy}`，state UNKNOWN／APPLIED／SUPERSEDED；只有 APPLIED 有原本人 policy，其他為 null，N1–100 |
| `/policies/{policy}/stop-requests/{request}?expectedVersion=N` | `{requestId,policyId,expectedVersion,state,policy}`，N≥1；已存在 stop body 不符409；其他owner404 |

核對 API 不建 policy、stop、job、audit 或預算。stop proof 在同一 repeatable-read 交易鎖原 policy 後核對 immutable stop 與 monotonic version；遇 concurrent snapshot衝突保留 pending 再讀。缺少原 stop 的 SUPERSEDED 是既有 policy 不可逆狀態的證明，不是刪除／取消歷史或新增 fence。所有新寫入仍使用既有 CSRF／expected staff／current MANAGER 驗證。

## 驗收工具

`run_return_discovery_ui_runtime.py` 只接受自己新空 `ticket-discovery-ui-runtime-*`、private work env與loopback15180／19110／25432／26379。用已核對SHA的原V22 JAR及test-classpath-only helper seed，正式新JAR／已build dist／Chromium操作；Vite preview僅提供production dist與測試同源proxy，不宣稱正式反向代理或公開TLS。兩Worker為真nonweb host JVM／PG Redis為自有資料容器，避免Cloud VFS完整fleet複製；不套M5C舊容器證據。

```bash
python scripts/run_return_discovery_ui_runtime.py \
  --env work/<新目錄>/runtime.env --baseline-jar work/<保留目錄>/baseline-v22.jar \
  --java-home <已安裝完整JDK21> --output work/<全新證據目錄>
```

配合 `smoke_return_discovery_ui_browser.cjs` 驗證真 committed POST後的瀏覽器回覆遺失／不完整證據、跨頁／reload原pending、GET-only核對、明確停止／返回、VIEWER／owner／CSRF、手機版面、實際Redis TCP停機／same-AOF及原job完成。另包含恢復舊版本pending的fixture案例，不能稱為真的HTTP stale-write race。原body、session、screenshots／logs只存ignored work／0600；finally只停止自有JVM／preview與dispose自有scope，核對零遺留。完整package／build與實測結果以 [HANDOFF](HANDOFF.md) 和 verification 為準，工具存在不等於通過。

本輪614項／29 suites完整package與Vue型別／production build通過；最終4phase／11組真API／Chromium／Redis故障通過，30 readonly invariants及unattributed來源signal全0，自有scope／程序清理為0。[正式證據](verification/return-discovery-ui-m5e-2026-10-12.json)。初版亦11組通過，補read-error禁用寫入後以新scope完整驗收；兩次成功與原logs均保留，backend JAR不變。
