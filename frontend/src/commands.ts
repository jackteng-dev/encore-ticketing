import { ref } from 'vue'
import { ApiError, write, type Staff } from './api'
export interface Pending { owner: string; path: string; method: string; body: Record<string,unknown>; eventId?: string; admissionTicketId?:string; admissionWindowRequired?:boolean }
const key='encore-catalog-pending-v1'
function restore(): Pending|null {try {const p=JSON.parse(sessionStorage.getItem(key)||'null');return p&&typeof p.owner==='string'&&/^\/admin\//.test(p.path)&&['POST','PUT'].includes(p.method)&&typeof p.body?.requestId==='string'?p:null} catch{return null}}
export const pending=ref<Pending|null>(restore())
export const busy=ref(false)
export const commandMessage=ref('')
export function save(p: Pending|null) {pending.value=p;if(p)sessionStorage.setItem(key,JSON.stringify(p));else sessionStorage.removeItem(key)}
async function send(staff: Staff) {
 const p=pending.value;if(!p||p.owner!==staff.username)throw new Error('請由原員工登入核對尚未確認的操作')
 if(p.path.startsWith('/admin/stock/'))throw new Error('請到庫存維護頁核對原操作')
 if(p.path.startsWith('/admin/admissions/'))throw new Error('請到票券核對頁核對原操作')
 busy.value=true;commandMessage.value=''
 try {
  const result=await write<{id:string;version:number}>(p.path,p.method,p.body)
  if(typeof result?.id!=='string'||typeof result.version!=='number')throw new ApiError(0,'回覆不完整，請核對原操作')
  save(null);commandMessage.value='操作已確認。';return result
 }catch(e){
  if(e instanceof ApiError&&e.status>=400&&e.status<500&&![408,429].includes(e.status))save(null)
  commandMessage.value=pending.value?'結果尚未確認。請重試原操作；不要另建相同資料。':e instanceof Error?e.message:'操作失敗'
  throw e
 }finally{busy.value=false}
}
export async function command(staff: Staff,path:string,method:string,body:Record<string,unknown>,eventId?:string) {
 if(pending.value||busy.value)throw new Error('請先核對原操作')
 save({owner:staff.username,path,method,body:{...body,requestId:crypto.randomUUID()},eventId})
 return send(staff)
}
export const retry=send
