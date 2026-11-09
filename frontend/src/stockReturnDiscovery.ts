import {api,write,ApiError,type Staff,type Page} from './api'
import {pending,busy,save,commandMessage} from './commands'
import {uuidPattern} from './customerCommands'
export interface DiscoveryPolicy {requestId:string;ticketId:string;startRevision:number;maxSources:number;state:'ACTIVE'|'EXHAUSTED'|'STOPPED';version:number;discovered:number;enabled:boolean}
export interface DiscoverySource {orderId:string;releaseRevision:number;disposition:'ENROLLED'|'EXISTING_RETURN';returnRequestId:string|null;createdAt:string}
interface EnrollmentProof {requestId:string;ticketId:string;maxSources:number;state:'UNKNOWN'|'APPLIED'|'SUPERSEDED';policy:DiscoveryPolicy|null}
interface StopProof {requestId:string;policyId:string;expectedVersion:number;state:'UNKNOWN'|'APPLIED'|'SUPERSEDED';policy:DiscoveryPolicy}
const root='/admin/stock/return-discovery',enrollPath=/^\/admin\/stock\/return-discovery\/tickets\/([0-9a-f-]{36})\/policies$/,stopPath=/^\/admin\/stock\/return-discovery\/policies\/([0-9a-f-]{36})\/stop$/
const positive=(n:unknown)=>Number.isSafeInteger(n)&&Number(n)>0
function invalid():never {throw new ApiError(0,'自動返還證據不完整，請保留原操作核對')}
export function discoveryPending(){return !!pending.value&&(enrollPath.test(pending.value.path)||stopPath.test(pending.value.path))}
export function validatePolicy(p:DiscoveryPolicy,ticket?:string,id?:string,max?:number){
 if(!p||!uuidPattern.test(p.requestId)||!uuidPattern.test(p.ticketId)||ticket&&p.ticketId!==ticket||id&&p.requestId!==id||max!==undefined&&p.maxSources!==max||!Number.isSafeInteger(p.startRevision)||p.startRevision<0||!positive(p.maxSources)||p.maxSources>100||!Number.isSafeInteger(p.discovered)||p.discovered<0||p.discovered>p.maxSources||typeof p.enabled!=='boolean'||!positive(p.version)||!['ACTIVE','EXHAUSTED','STOPPED'].includes(p.state))invalid()
 if(p.state==='ACTIVE'&&(p.version!==1||p.discovered>=p.maxSources)||p.state==='EXHAUSTED'&&(p.version!==2||p.discovered!==p.maxSources)||p.state==='STOPPED'&&![2,3].includes(p.version))invalid()
 return p
}
export async function readTicketPolicy(ticket:string){try{return validatePolicy(await api<DiscoveryPolicy>(`${root}/tickets/${ticket}/policy`),ticket)}catch(e){if(e instanceof ApiError&&e.status===404)return null;throw e}}
export async function readDiscoverySources(p:DiscoveryPolicy,page=1){
 const result=await api<Page<DiscoverySource>>(`${root}/policies/${p.requestId}/sources?page=${page}&size=12`)
 if(!result||result.page!==page||result.size!==12||!Number.isSafeInteger(result.total)||result.total<0||result.total>p.maxSources||!Array.isArray(result.items)||result.items.length>12||result.items.length>result.total)invalid()
 const ids=new Set<string>(),revisions=new Set<number>()
 for(const s of result.items){if(!s||!uuidPattern.test(s.orderId)||!positive(s.releaseRevision)||s.releaseRevision<=p.startRevision||!Number.isFinite(Date.parse(s.createdAt))||!['ENROLLED','EXISTING_RETURN'].includes(s.disposition)||(s.disposition==='ENROLLED'?typeof s.returnRequestId!=='string'||!uuidPattern.test(s.returnRequestId):s.returnRequestId!==null)||ids.has(s.orderId)||revisions.has(s.releaseRevision))invalid();ids.add(s.orderId);revisions.add(s.releaseRevision)}
 return result
}
export async function retryDiscovery(staff:Staff,first=false){
 const p=pending.value,match=p&&(enrollPath.exec(p.path)||stopPath.exec(p.path)),enrolling=p&&enrollPath.test(p.path)
 if(!p||!match||!uuidPattern.test(match[1]!)||p.method!=='POST'||p.owner!==staff.username||!uuidPattern.test(String(p.body.requestId))||busy.value||enrolling&&(!positive(p.body.maxSources)||Number(p.body.maxSources)>100)||!enrolling&&!positive(p.body.expectedVersion))throw new Error('請由原員工核對自動返還原操作')
 busy.value=true
 try{
  if(enrolling){
   if(!first){const proof=await api<EnrollmentProof>(`${root}/tickets/${match[1]}/policy-requests/${p.body.requestId}?maxSources=${p.body.maxSources}`)
    if(!proof||proof.requestId!==p.body.requestId||proof.ticketId!==match[1]||proof.maxSources!==p.body.maxSources||!['UNKNOWN','APPLIED','SUPERSEDED'].includes(proof.state))invalid()
    if(proof.state==='APPLIED'){const policy=validatePolicy(proof.policy!,match[1],String(p.body.requestId),Number(p.body.maxSources));save(null);commandMessage.value='原授權已確認；來源與工作進度需另外核對。';return policy}
    if(proof.policy!==null)invalid()
    if(proof.state==='SUPERSEDED'){save(null);commandMessage.value='原授權內容已不能套用；沒有接管其他授權。請重讀本人授權。';return null}
   }
   if(!staff.canWrite)throw new Error('唯讀員工只能核對；未知原授權仍保留，請由原管理員續做')
   const policy=validatePolicy(await write<DiscoveryPolicy>(p.path,'POST',p.body,p.owner),match[1],String(p.body.requestId),Number(p.body.maxSources));save(null);commandMessage.value='有限授權已保存；登記不代表已完成返還。';return policy
  }
  const proofPath=`${root}/policies/${match[1]}/stop-requests/${p.body.requestId}?expectedVersion=${p.body.expectedVersion}`
  const verify=(r:StopProof)=>{if(!r||r.requestId!==p.body.requestId||r.policyId!==match[1]||r.expectedVersion!==p.body.expectedVersion||!['UNKNOWN','APPLIED','SUPERSEDED'].includes(r.state))invalid();validatePolicy(r.policy,undefined,match[1]);if(r.state==='APPLIED'&&(r.policy.state!=='STOPPED'||r.policy.version!==Number(p.body.expectedVersion)+1)||r.state==='UNKNOWN'&&(r.policy.state==='STOPPED'||r.policy.version>Number(p.body.expectedVersion))||r.state==='SUPERSEDED'&&r.policy.state!=='STOPPED'&&r.policy.version<=Number(p.body.expectedVersion))invalid();return r}
  let proof=verify(await api<StopProof>(proofPath))
  if(proof.state==='UNKNOWN'){if(!staff.canWrite)throw new Error('唯讀員工只能核對；未知停止命令仍保留，請由原管理員續做');validatePolicy(await write<DiscoveryPolicy>(p.path,'POST',p.body,p.owner),undefined,match[1]);proof=verify(await api<StopProof>(proofPath));if(proof.state==='UNKNOWN')invalid()}
  save(null);commandMessage.value=proof.state==='APPLIED'?'原停止命令已確認。已完成返還不撤回，已送出的命令仍需核對。':'原停止內容已不能套用，沒有當成本命令成功；請重讀後明確決定下一步。';return proof.policy
 }catch(e){commandMessage.value='自動返還結果尚未確認，原編號與內容保留；其他寫入暫停。';throw e}finally{busy.value=false}
}
export async function enrollDiscovery(staff:Staff,ticket:string,maxSources:number){if(!staff.canWrite||pending.value||busy.value||!uuidPattern.test(ticket)||!positive(maxSources)||maxSources>100)throw new Error('請先核對原操作及來源數量');save({owner:staff.username,path:`${root}/tickets/${ticket}/policies`,method:'POST',body:{requestId:crypto.randomUUID(),maxSources}});return retryDiscovery(staff,true)}
export async function stopDiscovery(staff:Staff,policy:DiscoveryPolicy){validatePolicy(policy);if(!staff.canWrite||pending.value||busy.value||policy.state==='STOPPED')throw new Error('請先核對原操作');save({owner:staff.username,path:`${root}/policies/${policy.requestId}/stop`,method:'POST',body:{requestId:crypto.randomUUID(),expectedVersion:policy.version}});return retryDiscovery(staff,true)}
