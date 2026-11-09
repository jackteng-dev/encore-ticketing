export interface CatalogEvent { id: string; slug: string; title: string; description: string; venue: string; status: string; version: number }
export interface Ticket { id: string; name: string; priceTwd: number; capacity: number; maxPerCustomer: number; version: number; available: number }
export interface Payment { id:string; requestId:string; status:string; reason:string|null; amountTwd:number; createdAt:string; processedAt:string|null }
export interface Order { id:string; requestId:string; ticketId:string; eventTitle:string; sessionName:string; startsAt:string; ticketName:string; priceTwd:number; quantity:number; totalTwd:number; status:string; version:number; createdAt:string; expiresAt:string; closedAt:string|null; payment:Payment|null; mockPaymentsEnabled:boolean }
export interface Session { id: string; eventId: string; name: string; startsAt: string; salesStart: string; salesEnd: string; version: number; salesStatus: string; tickets: Ticket[] }
export interface Detail { event: CatalogEvent; sessions: Session[]; orderingEnabled: boolean; waitingRoomEnabled:boolean; purchaseRequestsEnabled:boolean; preDebitEnabled:boolean }
export interface StockAttempt { requestId:string;ticketId:string;quantity:number;expectedTicketVersion:number;expectedTotalTwd:number;state:'PREPARED'|'HELD'|'ABORTING'|'ABORTED'|'REVIEW'|Purchase['status'];deadlineAt:string;canRetrySameRequest:boolean;purchase:Purchase|null }
export interface Purchase { id:string; requestId:string; ticketId:string; quantity:number; expectedTicketVersion:number; expectedTotalTwd:number; status:'PROCESSING'|'RESERVED'|'REJECTED'|'CANCELLED'|'EXPIRED'; version:number; reason:string|null; orderId:string|null; createdAt:string; deadlineAt:string; closedAt:string|null }
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface Staff { username: string; canWrite: boolean }
export interface Customer { id: string; handle: string; displayName: string }
export class ApiError extends Error { constructor(public status: number, message: string,public retryAfter=0) { super(message) } }
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
 try {
  const response = await fetch('/api'+path, { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(10000), ...init })
  if (!response.ok) {
   const error = await response.json().catch(() => null)
   throw new ApiError(response.status, error?.message || `請求未完成 (${response.status})`,Number(response.headers.get('Retry-After'))||0)
  }
  return await response.json() as T
 } catch (error) { if (error instanceof ApiError) throw error; throw new ApiError(0, '連線中斷或回覆不完整，請核對原操作') }
}
export async function write<T>(path: string, method: string, body: unknown,expectedStaff?:string): Promise<T> {
 const csrf = await api<{headerName:string;token:string}>('/auth/csrf')
 return api(path,{method,headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token,...(expectedStaff?{'X-Expected-Staff-Username':expectedStaff}:{})},body:JSON.stringify(body)})
}
export async function customerWrite<T>(path: string,body: unknown,expectedOwner?:string): Promise<T> {
 const csrf=await api<{headerName:string;token:string}>('/customer/auth/csrf')
 return api(path,{method:'POST',headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token,...(expectedOwner?{'X-Expected-Customer-ID':expectedOwner}:{})},body:JSON.stringify(body)})
}
export const date = (s: string) => new Intl.DateTimeFormat('zh-TW',{timeZone:'Asia/Taipei',dateStyle:'medium',timeStyle:'short'}).format(new Date(s))
export const money = (n: number) => 'NT$ '+n.toLocaleString('zh-TW')
export const status = (s: string) => ({ DRAFT:'草稿', PUBLISHED:'已發布', ARCHIVED:'已封存', UPCOMING:'尚未開賣', SELLING:'售票時段內', CLOSED:'售票已結束',PENDING_PAYMENT:'保票中・未付款',CANCELLED:'已取消',EXPIRED:'已到期',PAID:'模擬已付款',PENDING:'待核對',SUCCEEDED:'模擬成功',DECLINED:'模擬拒絕',REVIEW:'待人工核對' }[s] || s)
