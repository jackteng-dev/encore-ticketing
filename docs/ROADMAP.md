# 開發路線與 Cloud 收尾界線

更新：2026-10-12（Asia/Taipei）。證據詳見 [FEATURE_STATUS](FEATURE_STATUS.md)，操作見 [SHOWCASE](SHOWCASE.md)。

| 里程碑 | 成果 | 狀態 |
|---|---|---|
| M1 | 活動／場次／票種、發布／封存、版本／audit | 已完成展示 |
| M2A/B/C | 顧客持久登入、PG保票／限購／取消／到期、mock／REVIEW | 已完成；沒有真收款 |
| M3 | 單 Redis 等候室、會員限流、短效資格 | 已完成單節點；Cluster／持久排隊 failover 未完成 |
| M4A/B/C | 持久請求、outbox、MQ confirm／重試／去重、非同步 UI | 已完成 |
| M4D1／D2A/B | Lua ledger、PG generation／intent／decision、有限恢復／管理 | 有界安全與原命令恢復完成；持續預扣部分完成 |
| M4D2C1–C8 | revision來源、返還接棒／結案／fence／rebind／有界恢復 | 正式流程完成各自契約；C1/C3增量實驗僅 test classpath |
| M4E1–E4 | 明確領票、一次驗票／未用撤銷、QR／掃描、窗口／原命令停止 | Cloud展示完成；真手機待驗 |
| M5A/B/C | PG共享session、Nginx／雙API、nonweb雙Worker／故障接手 | 隔離單機完成；原fleet容器證據V22，最新V24全套未重驗 |
| M5D/E | 有限未來RELEASE發現、原管理員／固定budget／UI／停止 | 已完成有界範圍；不等於持續READY |
| M6A | 心跳、backlog／gate／REVIEW、readonly依賴探測 | 已完成基本監控；無Prometheus／Grafana／告警 |
| M6B | 五組有界k6與正式業務效果核對 | 807 HTTP／819 checks、55 invariants零違規；預扣8/12後DIRTY |
| M6C | 有界PG備份、隔離還原、原pending恢復／真rollback | 10組／67 invariants零違規 |
| M6D | 展示手冊、現況矩陣、架構／本地清單、最新JAR／dist流程核對 | 已完成受控展示收尾，見[結果](verification/showcase-m6d-2026-10-12.md)／HANDOFF |

## 結案範圍

2026-10-11確認：雙API代理／程序故障、有界庫存協調與多Worker恢復、基本監控／有界k6、隔離備份還原、展示操作及本地清單。M6D通過後可結束**受控展示的Cloud階段**、切換下一個作品；持續預扣／高吞吐仍部分完成，不由展示結案推論已解決。

正式公開部署、真手機、實際容量、Cluster／多broker quorum／跨主機HA、完整退款／轉讓／MFA／真金流另排，不全部變成換作品前置，沒有100%商用完成度。

## 後續優先順序

1. 本機可用後：最新完整fleet、HTTPS／Secure Cookie、DB最小權限、private volumes／资源與復原，通過才外部試用。
2. 真手機／場次／UTC+8／人員授權演練，通過才討論現場使用。
3. 若主打持續大量搶票：先解決DIRTY接續、有限授權輪替與完整來源協調，再做warmup／多輪／長時間負載及不變條件；不能只增加VU。
4. 按產品需求做通知／海報／帳號安全／退款／真服務，Cluster／HA需各自拓撲與failover證據。
