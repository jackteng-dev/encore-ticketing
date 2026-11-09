import { ref } from 'vue'
import { ApiError,customerWrite,type Customer,type Order } from './api'
import { commandPending as reservationPending,commandBusy as reservationBusy,commandMessage as reservationMessage,saveCommand as save,uuidPattern,orderPath as pathPattern } from './customerCommands'
export { reservationPending,reservationBusy,reservationMessage }
export const confirmedOrder=ref<Order|null>(null),confirmedOwner=ref<string|null>(null)
export async function retryReservation(customer:Customer):Promise<Order>{
 const p=reservationPending.value
 if(!p||p.owner!==customer.id||!pathPattern.test(p.path))throw new Error('請由原顧客登入核對尚未確認的保票操作')
 if(reservationBusy.value)throw new Error('操作處理中')
 reservationBusy.value=true;reservationMessage.value=''
 try{
  const result=await customerWrite<Order>(p.path,p.body,p.owner)
  if(!uuidPattern.test(result?.id)||!Number.isInteger(result.version)||result.version<1||!['PENDING_PAYMENT','CANCELLED','EXPIRED','PAID'].includes(result.status)||
   (p.path==='/customer/orders'?(result.requestId!==p.body.requestId||result.ticketId!==p.body.ticketId||result.quantity!==p.body.quantity||result.totalTwd!==p.body.expectedTotalTwd):result.id!==p.path.split('/')[3]))throw new ApiError(0,'回覆不完整，請重試原操作')
  if(p.path.endsWith('/payments')&&(!result.payment||!uuidPattern.test(result.payment.id)||result.payment.requestId!==p.body.requestId||result.payment.amountTwd!==p.body.expectedTotalTwd))throw new ApiError(0,'付款回覆不完整，請重試原操作')
  if(p.path.endsWith('/reconcile')&&result.payment?.id!==p.path.split('/')[5])throw new ApiError(0,'核對回覆不完整，請重試原操作')
  if(result.payment&&(!uuidPattern.test(result.payment.id)||!['PENDING','SUCCEEDED','DECLINED','REVIEW'].includes(result.payment.status)||result.payment.amountTwd!==result.totalTwd))throw new ApiError(0,'付款狀態不完整，請重試原操作')
  confirmedOwner.value=p.owner;confirmedOrder.value=result;save(null);reservationMessage.value='操作已確認。';return result
 }catch(e){
  if(e instanceof ApiError&&e.status>=400&&e.status<500&&![401,403,408,429].includes(e.status))save(null)
  reservationMessage.value=reservationPending.value?'結果尚未確認。請由原顧客重試原操作，勿新增保票或付款。':(e as Error).message
  throw e
 }finally{reservationBusy.value=false}
}
export async function reserveCommand(customer:Customer,path:string,body:Record<string,unknown>){
 if(reservationPending.value||reservationBusy.value)throw new Error('請先核對原保票操作')
 if(!pathPattern.test(path))throw new Error('操作路徑不正確')
 save({owner:customer.id,path,body:{...body,requestId:crypto.randomUUID()}});return retryReservation(customer)
}
