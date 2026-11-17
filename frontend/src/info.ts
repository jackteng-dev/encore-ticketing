export interface InfoSection { heading: string; body: string[] }
export interface InfoPage { slug: string; eyebrow: string; title: string; lead: string; sections: InfoSection[] }

const demo = '本站為展示作品：付款僅限免費模擬，不會真收款；以下說明以展示版實際行為為準。'

export const infoPages: InfoPage[] = [
  {
    slug: 'faq', eyebrow: 'FAQ', title: '常見問題', lead: '購票前最常被問到的事。', sections: [
      { heading: '一定要註冊會員才能買票嗎？', body: ['是。保票、付款與電子票券都綁定本人會員帳號，以便核對限購與入場資格。'] },
      { heading: '開賣時為什麼要排隊？', body: ['熱門場次會啟用等候室，依加入順序發放短時間的入場資格，取得資格後才能保票。重新整理不會讓你插隊，也不會讓你掉到隊尾；資格到期後重新加入才會回到隊尾。'] },
      { heading: '保票後多久要付款？', body: ['保票最多保留 10 分鐘，且不會晚於售票截止。逾時未付款會自動釋出票額給其他人。'] },
      { heading: '可以選座位嗎？', body: ['目前依票種（區域）購買。座位選擇功能規劃中。'] },
      { heading: '每個人最多可以買幾張？', body: ['每個票種有每人限購張數，已付款與尚未釋出的保票都會計入。'] },
      { heading: '電子票券怎麼用？', body: ['付款確認後，到「我的票券」領取 QR 電子票。入場時由現場人員掃描，每張票只能使用一次。'] },
    ],
  },
  {
    slug: 'guide', eyebrow: 'HOW TO BUY', title: '購票須知', lead: '四個步驟完成購票。', sections: [
      { heading: '01 選擇場次與區域', body: ['在活動頁查看各場次的票種、票價與剩餘張數，選擇想要的票種。'] },
      { heading: '02 等候室與張數', body: ['若場次啟用等候室，先加入排隊；取得入場資格後選擇張數。'] },
      { heading: '03 保票與付款', body: ['確認保票後在期限內完成付款。', demo] },
      { heading: '04 領取 QR 票', body: ['付款確認後於「我的票券」領取電子票，入場時出示 QR Code。'] },
    ],
  },
  {
    slug: 'refund', eyebrow: 'REFUND POLICY', title: '退換票規定', lead: '退票、換票與轉讓的規則。', sections: [
      { heading: '如何申請', body: ['已付款訂單在開演前 24 小時前，可於「我的保票訂單」申請退票並填寫原因；審核前可自行撤回。已有票券入場的訂單無法退票。'] },
      { heading: '審核與退款', body: ['主辦方審核通過後記錄退款並撤銷票券。本展示沒有真實收款，核准後為模擬退款，不會實際退款；退回的票額不會自動重新開賣。未付款的保票可直接取消，票額立即釋出。'] },
      { heading: '轉讓', body: ['票券轉讓功能規劃中。'] },
    ],
  },
  {
    slug: 'privacy', eyebrow: 'PRIVACY', title: '隱私權政策', lead: '我們如何處理你的資料。', sections: [
      { heading: '蒐集的資料', body: ['會員帳號、顯示名稱、訂單與票券紀錄。展示版不蒐集真實姓名、身分證字號、電話或付款卡號。'] },
      { heading: '使用方式', body: ['僅用於保票、限購核對、電子票券與入場驗票。不提供給第三方，不用於廣告。'] },
      { heading: 'Cookie', body: ['僅使用登入所需的必要 Cookie，不使用追蹤或廣告 Cookie。'] },
    ],
  },
  {
    slug: 'terms', eyebrow: 'TERMS', title: '服務條款', lead: '使用本站前請先閱讀。', sections: [
      { heading: '展示性質', body: [demo, '本站不代表任何真實活動、主辦單位或售票授權。'] },
      { heading: '帳號使用', body: ['請使用獨立的展示密碼，不要使用你在其他服務的密碼。禁止以自動化程式大量搶票或干擾排隊。'] },
    ],
  },
  {
    slug: 'contact', eyebrow: 'CONTACT', title: '客服中心', lead: '找不到答案嗎？', sections: [
      { heading: '先看看常見問題', body: ['大多數購票、付款與入場問題都可以在常見問題找到答案。'] },
      { heading: '聯絡客服', body: ['展示版沒有真人客服；下方的客服管道為模擬版本。'] },
    ],
  },
]

export const findInfoPage = (slug: unknown) => infoPages.find(p => p.slug === slug)
