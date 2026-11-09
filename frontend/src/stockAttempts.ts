import { ref } from 'vue'
import { api,ApiError,customerWrite,type Customer,type StockAttempt } from './api'
import { commandPending,commandBusy,commandMessage,saveCommand,attemptPath,uuidPattern,type Pending } from './customerCommands'
import { validPurchase,rememberPurchase,acknowledgedPurchase } from './purchases'
export const lastAttempt=ref<{owner:string;progress:StockAttempt}|null>(null)
function validate(value:StockAttempt,p:Pending){
 if(!value||value.purchase===undefined||!uuidPattern.test(value.requestId)||value.requestId!==p.body.requestId||value.ticketId!==p.body.ticketId||value.quantity!==p.body.quantity||value.expectedTicketVersion!==p.body.expectedTicketVersion||value.expectedTotalTwd!==p.body.expectedTotalTwd||!Number.isFinite(Date.parse(value.deadlineAt))||typeof value.canRetrySameRequest!=='boolean')throw new ApiError(0,'預扣進度回覆不完整，請核對原操作')
 if(value.purchase){const q=value.purchase;if(!validPurchase(q)||q.requestId!==value.requestId||q.ticketId!==value.ticketId||q.quantity!==value.quantity||q.expectedTicketVersion!==value.expectedTicketVersion||q.expectedTotalTwd!==value.expectedTotalTwd||q.status!==value.state||q.deadlineAt!==value.deadlineAt||value.canRetrySameRequest)throw new ApiError(0,'預扣進度與正式收據不一致，請核對原操作')}
 else if(!['PREPARED','HELD','ABORTING','ABORTED','REVIEW'].includes(value.state)||value.canRetrySameRequest&&!['PREPARED','HELD'].includes(value.state))throw new ApiError(0,'預扣進度尚未確認，請核對原操作')
 return value
}
function remember(value:StockAttempt,p:Pending){
 const old=lastAttempt.value?.owner===p.owner&&lastAttempt.value.progress.requestId===p.body.requestId?lastAttempt.value.progress:null
 // A slower earlier read cannot overwrite a committed purchase or abandonment.
 if(old&&(old.purchase&&!value.purchase||old.state==='ABORTED'&&value.state!=='ABORTED'))return old
 if(old&&old.deadlineAt!==value.deadlineAt)throw new ApiError(0,'原購票期限不一致，請核對原操作')
 if(value.purchase){const receipt=rememberPurchase(p.owner,value.purchase);value={...value,state:receipt.status,purchase:receipt};acknowledgedPurchase.value={owner:p.owner,receipt}}
 lastAttempt.value={owner:p.owner,progress:value};return value
}
export function attemptMessage(p:StockAttempt){return p.purchase?'已確認正式購票結果，請查看本人購票紀錄。':p.state==='ABORTED'?'原預扣已核對結束，沒有建立保票訂單。':p.state==='REVIEW'?'原預扣待人工核對，請保留原操作。':'原購票尚未正式受理，尚未保票，也沒有付款倒數。請持續核對原操作。'}
export async function retryAttempt(customer:Customer,first=false):Promise<StockAttempt>{
 const p=commandPending.value
 if(!p||p.owner!==customer.id||p.path!==attemptPath)throw new Error('請由原顧客核對原預扣購票操作')
 if(commandBusy.value)throw new Error('操作處理中')
 commandBusy.value=true;commandMessage.value=''
 try{
  let value:StockAttempt|undefined
  if(!first){try{value=validate(await api<StockAttempt>(attemptPath+'/'+p.body.requestId,{headers:{'X-Expected-Customer-ID':p.owner}}),p)}catch(e){if(!(e instanceof ApiError&&e.status===404))throw e}}
  // Preserve verified PG progress even if the following resume POST fails.
  if(value)value=remember(value,p)
  if(!value||value.canRetrySameRequest)value=validate(await customerWrite<StockAttempt>(p.path,p.body,p.owner),p)
  value=remember(value,p)
  if(value.purchase||value.state==='ABORTED')saveCommand(null)
  commandMessage.value=attemptMessage(value);return value
 }catch(e){commandMessage.value='原預扣購票結果尚未確認，請保留原編號與內容核對。';throw e}
 finally{commandBusy.value=false}
}
export async function attemptCommand(customer:Customer,body:Record<string,unknown>){
 if(commandPending.value||commandBusy.value)throw new Error('請先核對原購票操作')
 saveCommand({owner:customer.id,path:attemptPath,body:{...body,requestId:crypto.randomUUID()}})
 return retryAttempt(customer,true)
}
