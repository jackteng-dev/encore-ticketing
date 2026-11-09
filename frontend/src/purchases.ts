import { ref } from 'vue'
import { ApiError,customerWrite,type Customer,type Purchase } from './api'
import { commandPending,commandBusy,commandMessage,saveCommand,uuidPattern,purchasePath } from './customerCommands'
const receipts=ref<{owner:string;items:Map<string,Purchase>}|null>(null)
export const acknowledgedPurchase=ref<{owner:string;receipt:Purchase}|null>(null)
export function validPurchase(p:Purchase):boolean {
 if(!p||!uuidPattern.test(p.id)||!uuidPattern.test(p.requestId)||!uuidPattern.test(p.ticketId)||!Number.isInteger(p.quantity)||p.quantity<1||p.quantity>10||!Number.isSafeInteger(p.expectedTicketVersion)||p.expectedTicketVersion<1||!Number.isInteger(p.expectedTotalTwd)||p.expectedTotalTwd<1||p.expectedTotalTwd>10000000)return false
 const created=Date.parse(p.createdAt),deadline=Date.parse(p.deadlineAt),closed=p.closedAt===null?NaN:Date.parse(p.closedAt)
 if(!Number.isFinite(created)||!Number.isFinite(deadline)||deadline<=created||deadline-created>120000)return false
 if(p.status==='PROCESSING')return p.version===1&&p.orderId===null&&p.reason===null&&p.closedAt===null
 if(p.version!==2||!Number.isFinite(closed)||closed<created)return false
 if(p.status==='RESERVED')return !!p.orderId&&uuidPattern.test(p.orderId)&&p.reason===null&&closed<deadline
 return ['REJECTED','CANCELLED','EXPIRED'].includes(p.status)&&p.orderId===null&&typeof p.reason==='string'&&p.reason.length>0&&(p.status!=='EXPIRED'||closed>=deadline)
}
export function rememberPurchase(owner:string,value:Purchase):Purchase {
 if(!validPurchase(value))throw new ApiError(0,'購票回覆不完整，請核對原操作')
 if(receipts.value?.owner!==owner)receipts.value={owner,items:new Map()}
 const items=receipts.value!.items,old=items.get(value.id)
 if(old){
  if(value.version<old.version)return old
  if(value.requestId!==old.requestId||value.ticketId!==old.ticketId||value.quantity!==old.quantity||value.expectedTotalTwd!==old.expectedTotalTwd||value.expectedTicketVersion!==old.expectedTicketVersion||value.createdAt!==old.createdAt||value.deadlineAt!==old.deadlineAt||value.version===old.version&&JSON.stringify(value)!==JSON.stringify(old))throw new ApiError(0,'購票回覆與原收據不一致，請重新核對')
 }
 if(items.size>=100&&!items.has(value.id))items.delete(items.keys().next().value!)
 items.set(value.id,value);return value
}
export function knownPurchases(owner:string):Purchase[]{return receipts.value?.owner===owner?[...receipts.value.items.values()]:[]}
export async function retryPurchase(customer:Customer):Promise<Purchase>{
 const p=commandPending.value
 if(!p||p.owner!==customer.id||!purchasePath.test(p.path))throw new Error('請由原顧客核對原購票操作')
 if(commandBusy.value)throw new Error('操作處理中')
 commandBusy.value=true;commandMessage.value=''
 try{
  const value=await customerWrite<Purchase>(p.path,p.body,p.owner)
  if(!validPurchase(value)||(p.path==='/customer/purchases'?(value.requestId!==p.body.requestId||value.ticketId!==p.body.ticketId||value.quantity!==p.body.quantity||value.expectedTicketVersion!==p.body.expectedTicketVersion||value.expectedTotalTwd!==p.body.expectedTotalTwd):value.id!==p.path.split('/')[3]))throw new ApiError(0,'購票回覆不完整，請重試原操作')
  const receipt=rememberPurchase(p.owner,value);acknowledgedPurchase.value={owner:p.owner,receipt};saveCommand(null);commandMessage.value='購票操作已確認。';return receipt
 }catch(e){
  if(e instanceof ApiError&&e.status>=400&&e.status<500&&![401,403,408,429].includes(e.status))saveCommand(null)
  commandMessage.value=commandPending.value?'購票結果尚未確認。請由原顧客重試原操作，勿新增購票、取消或付款。':(e as Error).message;throw e
 }finally{commandBusy.value=false}
}
export async function purchaseCommand(customer:Customer,path:string,body:Record<string,unknown>){
 if(commandPending.value||commandBusy.value)throw new Error('請先核對原購票操作')
 if(!purchasePath.test(path))throw new Error('購票路徑不正確')
 saveCommand({owner:customer.id,path,body:{...body,requestId:crypto.randomUUID()}});return retryPurchase(customer)
}
export function purchaseStatus(p:Purchase){return {PROCESSING:'處理中・尚未保票',RESERVED:'已建立保票訂單',REJECTED:'未能保票',CANCELLED:'購票請求已取消',EXPIRED:'購票請求已到期'}[p.status]}
export function purchaseReason(p:Purchase){return ({UNAUTHENTICATED:'受理時的登入已失效，未完成的請求不會建立保票。',SOLD_OUT:'票額已不足，這筆請求不會再保票。',PURCHASE_LIMIT:'已達此票種限購數量。',ORDER_LIMIT:'未付款訂單已達上限。',SALES_CLOSED:'售票時段已結束或活動已封存。',QUOTE_CHANGED:'票價或版本已改變。',REQUEST_EXPIRED:'已超過原受理期限，這筆請求不會復活。',CUSTOMER_CANCELLED:'你已取消這筆請求，沒有保留票額。'}[p.reason||'']||'請核對本人訂單與購票紀錄。')}
