<script setup lang="ts">
import { computed,onMounted,onUnmounted,ref } from 'vue'
import { RouterLink,useRouter } from 'vue-router'
import { api,ApiError,date,money,type Customer,type Purchase,type Page } from '../api'
import { commandPending,commandBusy,uuidPattern } from '../customerCommands'
import { acknowledgedPurchase,knownPurchases,rememberPurchase,purchaseCommand,purchaseStatus,purchaseReason } from '../purchases'
import ReservationPending from '../components/ReservationPending.vue'
const router=useRouter(),customer=ref<Customer|null>(null),data=ref<Page<Purchase>|null>(null),page=ref(1),error=ref(''),loading=ref(false),clock=ref(Date.now()),retryAt=ref(0)
let disposed=false,generation=0
const purchases=computed(()=>{
 if(!customer.value)return []
 const cached=new Map(knownPurchases(customer.value.id).map(p=>[p.id,p])),items=(data.value?.items||[]).map(p=>cached.get(p.id)||p)
 const last=acknowledgedPurchase.value
 if(page.value===1&&last?.owner===customer.value.id&&!items.some(p=>p.id===last.receipt.id))items.unshift(cached.get(last.receipt.id)||last.receipt)
 return items
})
async function load(silent=false){
 if(disposed||loading.value||commandBusy.value||Date.now()<retryAt.value)return
 const stamp=++generation;loading.value=true;if(!silent)error.value=''
 try{
  const me=await api<Customer>('/customer/auth/me');if(disposed||stamp!==generation)return
  if(customer.value?.id!==me.id){data.value=null;customer.value=me}
  const result=await api<Page<Purchase>>('/customer/purchases?page='+page.value,{headers:{'X-Expected-Customer-ID':me.id}})
  if(disposed||stamp!==generation)return
  if(!result||!Array.isArray(result.items)||result.page!==page.value||!Number.isInteger(result.total)||result.total<0||!Number.isInteger(result.size)||result.size<1||result.size>20||result.items.length>result.size)throw new ApiError(0,'購票紀錄回覆不完整，請重新讀取')
  data.value={...result,items:result.items.map(p=>rememberPurchase(me.id,p))};error.value='';retryAt.value=0
 }catch(e){
  if(disposed||stamp!==generation)return
  error.value=(e as Error).message;retryAt.value=Date.now()+Math.max(e instanceof ApiError?e.retryAfter:0,3)*1000
  if(e instanceof ApiError&&e.status===401){customer.value=null;data.value=null;await router.replace({path:'/account/login',query:{returnTo:'/account/purchases'}})}
 }finally{if(!disposed&&stamp===generation)loading.value=false}
}
async function cancel(p:Purchase){if(!customer.value||commandPending.value||commandBusy.value)return;error.value='';++generation;loading.value=false;try{await purchaseCommand(customer.value,'/customer/purchases/'+p.id+'/cancel',{expectedVersion:p.version});if(!disposed)await load()}catch(e){if(disposed)return;error.value=(e as Error).message;if(!commandPending.value){retryAt.value=0;await load()}}}
async function move(step:number){page.value+=step;data.value=null;await load()}
const blocked=computed(()=>loading.value||commandBusy.value||!!commandPending.value||!!error.value)
let timer:ReturnType<typeof setInterval>,poll:ReturnType<typeof setInterval>
onMounted(()=>{void load();timer=setInterval(()=>clock.value=Date.now(),500);poll=setInterval(()=>{if(purchases.value.some(p=>p.status==='PROCESSING'))void load(true)},3000)})
onUnmounted(()=>{disposed=true;++generation;clearInterval(timer);clearInterval(poll)})
</script>
<template><section class="content detail-page"><RouterLink to="/account" class="back-link">← 會員空間</RouterLink><div class="event-title"><div class="eyebrow">MY PURCHASE REQUESTS</div><h1>你的購票紀錄。</h1><p class="muted">送出請求後先核對結果。處理中尚未保票；只有建立訂單後才有付款期限。沒有真收款；電子票券需模擬付款確認後另行領取。</p></div><ReservationPending :customer="customer" @confirmed="load()"/><p v-if="error" class="notice error" role="alert">{{error}}</p><div class="actions"><button :disabled="loading||commandBusy||clock<retryAt" @click="load()">重新讀取購票紀錄</button><RouterLink to="/account/orders" class="back-link">查看本人保票訂單 ↗</RouterLink></div><p v-if="loading" role="status">讀取中…</p><p v-if="clock<retryAt" class="muted">請稍候再核對，原購票操作會保留。</p><div v-if="!purchases.length&&!loading&&!error" class="empty"><h2>還沒有購票請求。</h2><RouterLink to="/">探索活動 ↗</RouterLink></div><article v-for="p in purchases" :key="p.id" class="panel purchase-card" :data-purchase-id="p.id"><div class="section-heading"><h2>{{purchaseStatus(p)}}</h2><span class="badge">{{p.quantity}} 張 · {{money(p.expectedTotalTwd)}}</span></div><p class="muted order-id">購票請求 {{p.id}}</p><p class="muted order-id">票種 {{p.ticketId}}</p><template v-if="p.status==='PROCESSING'"><p role="status">正在處理，尚未保留票額，付款倒數尚未開始。</p><p>受理截止 {{date(p.deadlineAt)}}</p><p v-if="clock>=Date.parse(p.deadlineAt)" class="notice warning">已到原受理截止，等待伺服器核對結果。請勿另送同一筆購票。</p><button :disabled="blocked" @click="cancel(p)">取消購票請求</button></template><template v-else-if="p.status==='RESERVED'"><p>已建立本人保票訂單。訂單可能已付款、取消或到期，請核對最新狀態。</p><RouterLink v-if="p.orderId&&uuidPattern.test(p.orderId)" class="button-link primary" :to="'/account/orders?orderId='+p.orderId+'#order-'+p.orderId">查看本人訂單與付款 ↗</RouterLink></template><p v-else class="notice">{{purchaseReason(p)}}</p></article><div v-if="data" class="pagination"><button :disabled="page===1||loading||commandBusy||clock<retryAt" @click="move(-1)">上一頁</button><span>第 {{page}} 頁 · {{data.total}} 筆</span><button :disabled="page*data.size>=data.total||loading||commandBusy||clock<retryAt" @click="move(1)">下一頁</button></div></section></template>
