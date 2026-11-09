import { ref } from 'vue'
export interface Pending { owner:string; path:string; body:Record<string,unknown> }
export const uuidPattern=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const uuid=uuidPattern.source.slice(1,-1)
export const orderPath=new RegExp('^/customer/orders(?:/'+uuid+'/(?:cancel|payments(?:/'+uuid+'/reconcile)?))?$')
export const purchasePath=new RegExp('^/customer/purchases(?:/'+uuid+'/cancel)?$')
export const attemptPath='/customer/purchase-attempts'
export const ticketPath=new RegExp('^/customer/orders/'+uuid+'/tickets$')
// Preserve existing unknown order/payment commands across this frontend upgrade.
const key='encore-reservation-pending-v1'
function restore():Pending|null {try{const p=JSON.parse(sessionStorage.getItem(key)||'null');return p&&uuidPattern.test(p.owner)&&(orderPath.test(p.path)||purchasePath.test(p.path)||ticketPath.test(p.path)||p.path===attemptPath)&&uuidPattern.test(p.body?.requestId)?p:null}catch{return null}}
export const commandPending=ref<Pending|null>(restore()),commandBusy=ref(false),commandMessage=ref('')
export function saveCommand(p:Pending|null){if(p)sessionStorage.setItem(key,JSON.stringify(p));else sessionStorage.removeItem(key);commandPending.value=p}
