import {api,write,ApiError,type Staff} from './api'
import {pending,busy,save,commandMessage} from './commands'
import {uuidPattern} from './customerCommands'

export interface WindowStatus {sessionId:string;version:number;requestId:string|null;opensAt:string|null;closesAt:string|null;state:'UNCONFIGURED'|'NOT_OPEN'|'OPEN'|'CLOSED';observedAt:string}
export interface WindowPolicy {sessionId:string;version:number;requestId:string;opensAt:string;closesAt:string;createdAt:string}
export interface WindowProof {requestId:string;sessionId:string;policyVersion:number;opensAt:string;closesAt:string;checkedAt:string}
export interface WindowRecovery {requestId:string;sessionId:string;expectedVersion:number;opensAt:string;closesAt:string;bodyFingerprint:string;state:'UNKNOWN'|'APPLIED'|'FENCED';policy:WindowPolicy|null;recordedAt:string|null}
export const windowPath=/^\/admin\/admissions\/windows\/([0-9a-f-]{36})$/i
function validDate(s:unknown):s is string {return typeof s==='string'&&Number.isFinite(Date.parse(s))}
function invalid():never {throw new ApiError(0,'入場時間窗證據不完整，請保留原操作並重新核對')}
export function validateWindowStatus(s:WindowStatus,id?:string) {
 if(!s||!uuidPattern.test(s.sessionId)||id&&s.sessionId!==id||!Number.isSafeInteger(s.version)||s.version<0||!validDate(s.observedAt))invalid()
 if(s.version===0){if(s.state!=='UNCONFIGURED'||s.requestId!==null||s.opensAt!==null||s.closesAt!==null)invalid();return s}
 if(!s.requestId||!uuidPattern.test(s.requestId)||!validDate(s.opensAt)||!validDate(s.closesAt)||Date.parse(s.opensAt)>=Date.parse(s.closesAt))invalid()
 const now=Date.parse(s.observedAt),expected=now<Date.parse(s.opensAt)?'NOT_OPEN':now>=Date.parse(s.closesAt)?'CLOSED':'OPEN'
 if(s.state!==expected)invalid();return s
}
export const readWindow=async(id:string)=>validateWindowStatus(await api<WindowStatus>('/admin/admissions/windows/'+id),id)
export function validateWindowProof(p:WindowProof|null,request:string,session:string,changedAt:string|null,required:boolean,checkIn:boolean) {
 if(p===null){if(required)invalid();return}
 if(!checkIn||!p||p.requestId!==request||p.sessionId!==session||!Number.isSafeInteger(p.policyVersion)||p.policyVersion<1||!validDate(p.opensAt)||!validDate(p.closesAt)||!validDate(p.checkedAt)||!changedAt||p.checkedAt!==changedAt||Date.parse(p.opensAt)>Date.parse(p.checkedAt)||Date.parse(p.checkedAt)>=Date.parse(p.closesAt))invalid()
}
async function verify(r:WindowRecovery,sid:string,body:Record<string,unknown>) {
 const canonical={requestId:body.requestId,expectedVersion:body.expectedVersion,opensAt:body.opensAt,closesAt:body.closesAt}
 const bytes=new TextEncoder().encode('ADMISSION_WINDOW:'+sid+':'+JSON.stringify(canonical)),hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))).map(b=>b.toString(16).padStart(2,'0')).join('')
 if(!r||r.requestId!==body.requestId||r.sessionId!==sid||r.expectedVersion!==body.expectedVersion||r.opensAt!==body.opensAt||r.closesAt!==body.closesAt||r.bodyFingerprint!==hash||!['UNKNOWN','APPLIED','FENCED'].includes(r.state))invalid()
 if(r.state==='UNKNOWN'){if(r.policy!==null||r.recordedAt!==null)invalid();return r}
 if(!validDate(r.recordedAt))invalid()
 if(r.state==='FENCED'){if(r.policy!==null)invalid();return r}
 const p=r.policy
 if(!p||p.sessionId!==sid||p.requestId!==body.requestId||p.version!==Number(body.expectedVersion)+1||!validDate(p.opensAt)||!validDate(p.closesAt)||!validDate(p.createdAt)||Date.parse(p.opensAt)!==Date.parse(r.opensAt)||Date.parse(p.closesAt)!==Date.parse(r.closesAt)||Date.parse(p.opensAt)>=Date.parse(p.closesAt)||p.createdAt!==r.recordedAt)invalid()
 return r
}
async function send(staff:Staff,mode:'apply'|'receipt'|'stop') {
 const p=pending.value,match=p&&windowPath.exec(p.path)
 if(!p||!match||!uuidPattern.test(match[1]!)||p.method!=='POST'||p.owner!==staff.username||!staff.canWrite||busy.value)throw new Error('請由原管理員核對時間窗操作')
 busy.value=true
 try {
  const r=await verify(await write<WindowRecovery>(p.path+(mode==='apply'?'':mode==='stop'?'/stop':'/receipt'),'POST',p.body,p.owner),match[1]!,p.body)
  if(mode==='apply'&&r.state!=='APPLIED')invalid()
  if(r.state==='UNKNOWN')commandMessage.value='尚未找到原時間窗結果；原操作仍保留。可重試原操作或明確永久停止。'
  else {save(null);commandMessage.value=r.state==='APPLIED'?'原時間窗設定已確認。':'原時間窗請求已永久停止，原請求不會再套用；目前場次設定不變。'}
  return r
 }catch(error){commandMessage.value='原時間窗結果尚未確認，請保留原操作；其他寫入暫停。';throw error}
 finally {busy.value=false}
}
export const retryWindow=(staff:Staff)=>send(staff,'apply')
export const recoverWindow=(staff:Staff,stop=false)=>send(staff,stop?'stop':'receipt')
export async function configureWindow(staff:Staff,sid:string,version:number,opensAt:string,closesAt:string) {
 if(pending.value||busy.value||!staff.canWrite||!uuidPattern.test(sid)||!Number.isSafeInteger(version)||version<0||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(opensAt)||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(closesAt)||!validDate(opensAt)||!validDate(closesAt)||Date.parse(opensAt)>=Date.parse(closesAt))throw new Error('請確認場次與開始、結束時間，並先核對原操作')
 save({owner:staff.username,path:'/admin/admissions/windows/'+sid,method:'POST',body:{requestId:crypto.randomUUID(),expectedVersion:version,opensAt,closesAt}})
 return retryWindow(staff)
}
