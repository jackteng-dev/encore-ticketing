<script setup lang="ts">
import { computed,onMounted,onUnmounted,ref,watch } from 'vue'
import { api,ApiError,customerWrite,type Customer } from '../api'
interface State {ticketId:string;state:string;requestId:string|null;position:number;expiresAt:number|null;serverNow:number}
const props=defineProps<{customer:Customer;ticketId:string}>(),emit=defineEmits<{eligible:[boolean]}>()
const key='encore-queue-pending:'+props.customer.id+':'+props.ticketId,path='/customer/waiting-room/'+props.ticketId,uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
function restore(){try{const id=sessionStorage.getItem(key);return id&&uuid.test(id)?id:null}catch{return null}}
const pending=ref<string|null>(restore()),state=ref<State|null>(null),busy=ref(false),error=ref(''),clock=ref(Date.now()),offset=ref(0),retryAt=ref(0)
const remaining=computed(()=>Math.max(0,Math.ceil(((state.value?.expiresAt||0)-clock.value-offset.value)/1000)))
const eligible=computed(()=>state.value?.state==='ADMITTED'&&remaining.value>0&&!pending.value&&!error.value)
watch(eligible,v=>emit('eligible',v),{immediate:true})
function save(id:string|null){if(id)sessionStorage.setItem(key,id);else sessionStorage.removeItem(key);pending.value=id}
function accept(s:State){if(s.ticketId!==props.ticketId||!['NOT_JOINED','WAITING','ADMITTED','EXPIRED'].includes(s.state)||!Number.isFinite(s.serverNow)||(['WAITING','ADMITTED','EXPIRED'].includes(s.state)&&(!s.requestId||!uuid.test(s.requestId)||!Number.isFinite(s.expiresAt)))||!Number.isInteger(s.position)||s.position<0)throw new ApiError(0,'等候室回覆不完整，請核對原排隊');state.value=s;offset.value=s.serverNow-Date.now()}
function failed(e:unknown){error.value=(e as Error).message;retryAt.value=Date.now()+Math.max(e instanceof ApiError?e.retryAfter:0,3)*1000}
let disposed=false,timer:ReturnType<typeof setInterval>,poll:ReturnType<typeof setInterval>
async function read(){if(busy.value||disposed||Date.now()<retryAt.value)return;busy.value=true;try{const s=await api<State>(path);if(!disposed){accept(s);error.value=''}}catch(e){if(!disposed)failed(e)}finally{busy.value=false}}
async function join(){if(busy.value||Date.now()<retryAt.value)return;busy.value=true;error.value='';try{if(!pending.value)save(crypto.randomUUID());const requestId=pending.value!;const s=await customerWrite<State>(path+'/join',{requestId});if(disposed)return;accept(s);if(s.requestId!==requestId)throw new ApiError(0,'排隊操作尚未核對，請重試原操作');save(null)}catch(e){if(!disposed){if(e instanceof ApiError&&e.status>=400&&e.status<500&&![401,403,408,429].includes(e.status))save(null);failed(e)}}finally{busy.value=false}}
onMounted(()=>{void read();timer=setInterval(()=>clock.value=Date.now(),250);poll=setInterval(()=>{if(state.value?.state==='WAITING'||state.value?.state==='ADMITTED')void read()},3000)})
onUnmounted(()=>{disposed=true;clearInterval(timer);clearInterval(poll);emit('eligible',false)})
</script>
<template><div class="waiting-room notice" aria-label="購票等候室"><div class="eyebrow">WAITING ROOM</div><h3>購票等候室</h3><p>按有效排隊順序入場；資格不代表已保留票額。請留在此頁，等待期間會更新排隊狀態。</p><p v-if="error" role="alert">{{error}}</p><p v-if="pending">排隊結果尚未確認，請核對原操作；不要另開新的排隊。</p><p v-if="state?.state==='WAITING'" role="status">目前排第 {{state.position}} 位，等待入場。</p><p v-else-if="state?.state==='ADMITTED'&&remaining>0" role="status">已取得入場資格 · 剩餘 {{remaining}} 秒（不會自動延長）</p><p v-else-if="state?.state==='EXPIRED'||state?.state==='ADMITTED'" role="status">入場或等待資格已到期。重新排隊會回到隊尾。</p><p v-else-if="state?.state==='NOT_JOINED'" role="status">尚未加入等候室。資格遺失時也需重新加入，原訂單不受影響。</p><button v-if="pending" :disabled="busy||clock<retryAt" @click="join">核對原排隊操作</button><button v-else-if="state?.state==='NOT_JOINED'||state?.state==='EXPIRED'" :disabled="busy||clock<retryAt" @click="join">{{state.state==='EXPIRED'?'重新加入隊尾':'加入等候室'}}</button><button :disabled="busy||clock<retryAt" @click="read">更新排隊狀態</button><p v-if="clock<retryAt" class="muted">請等 {{Math.ceil((retryAt-clock)/1000)}} 秒後再核對。</p></div></template>
