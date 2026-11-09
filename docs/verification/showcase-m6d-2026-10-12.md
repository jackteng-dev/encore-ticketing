# M6D 受控展示收尾驗收

2026-10-12（Asia/Taipei；private logs UTC）。由 main `9e9e088d57e54eba73f1b447520b1276a0f15e39` 開始。[去識別JSON](showcase-m6d-2026-10-12.json)、[操作手冊](../SHOWCASE.md)、[功能矩陣](../FEATURE_STATUS.md)。

## 本輪成果

README／ROADMAP／架構／本地清單改為目前狀態與證據，移除過期「k6／備份／窗口待做」的入口描述；舊架構與M6C交接完整封存。四個顧客頁的過期「付款未開放／沒有出票」文字改為免費mock及付款確認後明確領票，不改交易／旗標。手冊包含新scope、完整測試與另一展示scope、旗標、時區、正常流程、故障證據、停止保留volume。

完整package發現既有人工REVIEW同UUID競爭，已修復：首個READ COMMITTED command查詢為空，peer之後提交原command並完成domain核對，fresh validation原本誤報REVIEW_CHANGED。現在只在此錯誤時重新鎖讀**原UUID／本人actor／同intent／相同body**，matching返回原receipt；其他actor／body、新UUID／非REVIEW仍拒絕。沒有新增全域鎖、改票額／deadline／job budget、清REVIEW或改已發布migration。

## 實測

- 全新PG／Redis／RabbitMQ：**629項／30 suites，failure/error/skip皆0**，mvn package成功；Maven256MB、suite384MB、SerialGC／CPU2／fork1／reuseForks=false。已發布V1–V24 source與packaged bytes均等於baseline。
- Vue型別與production build通過，最新JS `index-CH8ZALSJ.js`、CSS `index-C2FRQd4L.css`，hash在JSON。
- 同一629項package JAR＋最新dist，host EMBEDDED JVM／Vite production preview，資料服務是真隔離containers；**Chromium151真UI10組全部通過**。這不是Nginx／雙API／雙Worker最新版fleet重验。
- 真UI活動／場次／12票種／發布與窗口OPEN，顧客註冊與原員工cookie並存；等候室→202請求→真MQ→正式order；mock手動核對PAID→本人明確2張領票／QR；第三張PG限購拒絕；另一合成顧客保票／取消；原production QR圖片解碼只填碼、明確核對／入場、重掃无第二入場按鈕；第二張明確撤銷；readonly監控與393×852 viewport無水平溢出。
- 七項readonly PG不變條件零違規：24 migrations、capacity12/held0/sold2、PAID1/CANCELLED1、RESERVED purchase1、issuance1／issued units2、CHECKED_IN1/REVOKED1、terminal admission audits2。重掃是UI核對terminal狀態，原UUID重送契約另由完整tests與歷史runtime證明。
- EMBEDDED監控保留API2／Worker2預期，因此缺少split角色警示是預期；不宣稱全部節點健康。

## 失敗及修正保留

首輪完整627項／30 suites，0fail／1error為上述六執行緒race。新增固定latch順序，UNUSED與SETTLEMENT兩項在舊production碼均error；修正後StockReview整組22項全過，再用另一新空scope完整629／0。合法actor/body、fresh拒絕、audit rollback、budget與域效果測試仍通過，沒有靠重跑掩蓋競爭。

展示runtime1的harness錯填監控expected=0，啟動檢查拒絕，改valid2/2與文件警示；runtime2漏接受真發布confirm，無發布POST，補上明確確認；runtime3已過前7組，早期擷取的PNG沒有完整QR，獨立local decoder亦拒絕，改等待完整票券頁GET及讀取控制unblocked後截實際SVG。runtime4通過10組與七不變條件。未放寬解碼／業務gate或用另外產的QR替代，不宣稱已查清所有渲染時序機制。各次是明確改正harness後新owned disposable scope，不是production原請求自動換UUID重試。

原失敗與成功logs／XML／PNG／private fixtures留ignored work/m6d-*，手冊操作可重現；本輪臨時harness不當通用CI工具。各輪finally停止自己的JVM／preview及data scope，containers/volumes/networks皆0；最终5個ports關閉，保留的失敗資料不公開。

## 結案與限制

**受控Cloud展示範圍結案，可以切換下一作品。** M6B持續預扣8/12後DIRTY、有限授權輪替／production incremental writer未完成；本輪沒有新效能或備份改善宣稱。M6B/C是當時JAR與環境證據。真手機／HTTPS／本機容量／最新版Nginx fleet、Cluster／多broker／跨主機HA、正式服務／完整restore另列本地清單；不宣稱商用售票完成。
