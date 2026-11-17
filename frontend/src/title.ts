const siteName='Encore'
/** 設定分頁標題；未提供時使用網站預設標題。 */
export function setTitle(title?:string){document.title=title?`${title}｜${siteName}`:`${siteName}｜每一次現場，都值得期待`}
