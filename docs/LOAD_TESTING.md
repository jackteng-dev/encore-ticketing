# M6B 有界 HTTP 負載驗收

目標是量測目前免費 Cloud 環境中的真實請求與業務效果，不承諾最大容量或正式 SLA。
使用 production JAR、兩個直接 loopback API、兩個 nonweb Worker，及全新隔離 PostgreSQL／Redis／單 RabbitMQ。
不修改業務期限、migration、名額限制或 gate，不使用 production debug endpoint。

## 工作負載與證據

`scripts/run_load_runtime.py` 以真實 HTTP 建立合成顧客、活動與票種，明確建立預扣 READY 投影。
準備帳號、BCrypt、登入與初始化不計入購票負載；沒有用 SQL 手工建立業務結果。
預設每組60個已登入顧客、12張票、8 VU；CLI最多100顧客、16 VU，capacity必須小於顧客數。
k6 購票／候位使用 closed `shared-iterations`，每顧客一次原操作及一次 exact replay；VU數不是RPS。
公開讀取另用 open `constant-arrival-rate`：20次／秒、10秒、名義200次，最多16 VU，必須無dropped iterations。
k6起始／結束邊界可能實際排程200或201次，報告保留實際數；購票／候位仍嚴格60次，不容許缺少iteration。
每組最長40秒，HTTP timeout10秒；保票10分鐘、購票處理期限60秒沿用原設定。

| 組別 | 負載 | 最終確認 |
|---|---|---|
| read | 固定到達率公開活動詳情 | 真實200與活動身分、200–201次且未丟棄排程 |
| sync | 同步保票＋原內容重送到另一API | PG保票數恰等於名額、其餘SOLD_OUT、無重複訂單 |
| async | 持久購票受理＋跨API原請求重送＋結果查詢 | 202不等於成功；排空後PG RESERVED／REJECTED與正式訂單核對 |
| predebit | 單一fresh generation Lua預扣＋跨API重送＋進度查詢 | 實際成立訂單、有界效果、gate與未解決intent；不重建投影來美化吞吐 |
| waiting | 另啟用候位資格，原序號跨API重送 | WAITING／ADMITTED分開；加入等候室不建立訂單 |

購票三組 admission=false，以隔離交易路徑比較；候位室另測，不聲稱已完成候位→預扣的完整負載驗收。
主要POST、原內容重送、HTTP總請求、輪詢及觀察到正式保票的耗時分開記錄。
HTTP409已確認業務拒絕與非預期失敗分開；check／iterations門檻必須全部通過。
兩API的原操作與重送使用相反節點；兩Worker共享正式服務與資料庫，publisher與consumer都啟用。
監控只在負載前後取樣，不混入k6請求；最後READ ONLY執行原付款／購票／傳遞／預扣／恢復不變條件。
另用每個路徑一個已成功UUID，實際驗證改內容返回REQUEST_CONFLICT。

## 啟動

先依 README 在自己的拋棄式 scope 完整 `backend mvn package` 與前端型別／production build。
停止及清理該自有測試scope，確保25432／26379／25672／19110／19111空閒；工具不會停止其他服務。
安裝免費開源k6並核對官方SHA256；JDK21沿用已安裝版本。

```bash
python scripts/run_load_runtime.py \
  --output work/<全新負載證據目錄> \
  --java-home <完整JDK21目錄> --k6 <k6執行檔>
```

工具拒絕重用證據目錄／既有scope，初始化私有env與自己的namespace、data volumes。
秘密、原UUID、cookies、完整k6摘要與程序logs只存ignored work，0600；不得提交fixture。
finally停止自有API／Worker，僅刪自己的拋棄式data scope，核對container／volume／network為0與ports關閉。
失敗也保留原證據，不自動重跑；修正後用另一新目錄驗證。

Cloud VFS可選`--compose-override work/<image-only-overlay>.yml`，僅允許自有`ticket-k6-flat-*`資料映像。
這是磁碟限制下保留官方clean root filesystem與啟動設定的一層映像；不能用它冒充原部署容器效能。
若採用，報告須列來源映像、轉換、實際image IDs與限制；不能清其他作品或使用global prune。

## 邊界

- 固定低到達率讀取與小型closed購票只證明這個環境及模型，不能外推十萬人、長期容量或SLA。
- 預扣協調仍可能DIRTY／REVIEW；持續READY投影與授權輪替不是這輪新功能。
- HTTP吞吐、持久受理率與成功訂單率不是同一數字；輪詢等待受退避／publisher排程影響。
- 這輪不測Nginx／HTTPS、Redis Cluster、多broker quorum、跨主機HA、付款或現場设备。
- 完整原交易測試和故障證據仍以對應HANDOFF為準；k6不是那些測試的替代。

實際結果將保存於 `docs/verification/`，測試未完成前不以工具存在宣稱通過。
