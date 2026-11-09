# 本地與正式服務驗收清單

更新：2026-10-12。Cloud成果見 [FEATURE_STATUS](FEATURE_STATUS.md)。未勾選表示**未在使用者本機／正式環境驗收**，不將已完成Cloud功能重列為未開發。現在不要求下載到使用者電腦，本機可用後按序處理。

## P0：外部試用前

- [ ] 最新main重新package／build／images，驗Nginx＋雙API＋雙Worker最新V24或後續版本；V22歷史容器證據不可直接沿用。
- [ ] 各作品env／namespace／port／volumes隔離；24–32GB可用記憶體下核對總資源、swap、磁碟／Docker driver／持久化。
- [ ] HTTPS、可信proxy、正式host、Secure／HttpOnly／SameSite Cookie、CSRF、跨API session、深連結；不可用loopback Secure=false對外。
- [ ] migration與runtime DB最小權限帳號分離、Redis ACL／TLS、MQ專用vhost／權限、固定依賴及更新政策。
- [ ] 註冊／登入限流、會員驗證／密碼重設需求、全域有界過期session清理、個資／票碼日志遮罩、session／bearer code存取保護。
- [ ] 備份排程／保留／加密／離機副本、可信來源驗證、憑證切換、全服務隔離restore與單邊切換。M6C只驗V24 ticket_demo有界PG dump，不含Redis／MQ／物件volume、PITR／跨PG版本；非空sequence未實測。
- [ ] 程序／PG／Redis／MQ故障、原UUID恢復、gateway reload／IP變動、REVIEW人工處理；來源與目標不得同時對外履約。

## P1：手機與現場

- [ ] 真手機HTTPS相機權限、前後鏡頭、旋轉、背景恢復／track停止、反光／低光／對焦、QR圖片／手動fallback。
- [ ] 指定活動／場次、台北UTC+8、DB同步與時鐘跳變、窗口開始含／結束不含／未設定拒絕、錯場次／重掃。
- [ ] MANAGER／VIEWER、停用／付款REVIEW／未用撤銷、原pending遺失回覆／永久停止；掃碼不自動驗票。
- [ ] 現場網路失聯／交班／票碼洩漏／資料保留政策；離線入場未實作。

## P2：容量與長期架構

- [ ] 持續預扣／授權輪替：M6B8/12後DIRTY仍在，不盲補Redis、不清REVIEW、不重設原budget。
- [ ] 獨立warmup／多輪／長時間負載、成功orders/s與acceptance/s分開、P95/P99、queue age/depth、CPU／heap／locks／IO、恢復時間／不變條件，每輪記錄版本／資源／失敗。
- [ ] fairness／防bot／clock skew／資料遺失政策；排隊順序與資格在Redis，same-AOF重啟不是PG durable queue或failover fairness。
- [ ] Redis Cluster slots／same-slot Lua／replica lag／failover、三broker quorum／分區／跨主機HA；同機容器非主機容錯。
- [ ] 正式metrics／告警，目前只有基本readonly監控，無Prometheus／Grafana／通知告警。

## 按產品需求另排

- [ ] 真金流sandbox／商家資格／驗簽／輪替／未知核對、REVIEW解決工具、退款／轉讓；目前mock不收款。
- [ ] 真Email／LINE／推播免費配額／同意／送達；目前未接售票通知。
- [ ] 活動海報SeaweedFS／S3／素材授權／檔案備份，不使用其他作品bucket。
- [ ] 完整員工管理／MFA、指定座位、售票App；未完成不阻擋此次Cloud展示收尾。

每項留下版本／環境／真設備／預期與實際／失敗及private原始證據；未做保持未勾選，不以舊對話取代驗收。
