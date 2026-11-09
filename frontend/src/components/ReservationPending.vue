<script setup lang="ts">
import { computed,ref,watch } from 'vue'
import type { Customer } from '../api'
import { reservationPending,reservationBusy,reservationMessage,retryReservation } from '../reservations'
import { retryPurchase } from '../purchases'
import { retryAttempt,lastAttempt,attemptMessage } from '../stockAttempts'
import { attemptPath } from '../customerCommands'
import { retryIssue,recoverIssue } from '../tickets'
import { ticketPath } from '../customerCommands'
const ticketPending=computed(()=>ticketPath.test(reservationPending.value?.path||''))
const attemptPending=computed(()=>reservationPending.value?.path===attemptPath)
const paymentPending=computed(()=>reservationPending.value?.path.includes('/payments'))
const purchasePending=computed(()=>reservationPending.value?.path.startsWith('/customer/purchases'))
const props=defineProps<{customer:Customer|null}>(),emit=defineEmits<{confirmed:[kind:'purchase'|'order']}>()
const canStop=ref(false),confirmStop=ref(false)
watch(()=>reservationPending.value,()=>{canStop.value=false;confirmStop.value=false})
async function lookup(stop=false){if(!props.customer)return;try{const r=await recoverIssue(props.customer,stop);canStop.value=r.state==='UNKNOWN';confirmStop.value=false;if(r.state!=='UNKNOWN')emit('confirmed','order')}catch{/* Preserve the original request on every failure. */}}
async function retry(){if(!props.customer)return;try{if(ticketPending.value){await retryIssue(props.customer);emit('confirmed','order');return}if(attemptPending.value){const result=await retryAttempt(props.customer);if(result.purchase)emit('confirmed','purchase');return}const kind=purchasePending.value?'purchase':'order';if(kind==='purchase')await retryPurchase(props.customer);else await retryReservation(props.customer);emit('confirmed',kind)}catch{/* shared message preserves unknown outcome */}}
</script>
<template><div v-if="reservationPending" class="notice warning" role="status"><p>有一筆{{(purchasePending||attemptPending)?'購票請求':ticketPending?'出票':paymentPending?'付款':'保票'}}操作尚未確認，新增購票、保票、取消與付款暫停。</p><p v-if="customer?.id!==reservationPending.owner">請由原顧客登入核對；目前帳號不能接管。</p><button :disabled="reservationBusy||customer?.id!==reservationPending.owner" @click="ticketPending?lookup():retry()">{{(purchasePending||attemptPending)?'核對原購票操作':ticketPending?'核對原出票操作':paymentPending?'核對尚未確認的付款操作':'重試原保票操作'}}</button><template v-if="ticketPending&&customer?.id===reservationPending.owner&&canStop"><button :disabled="reservationBusy" @click="retry">重試原出票操作</button><button :disabled="reservationBusy" @click="confirmStop=true">停止原出票請求</button><div v-if="confirmStop"><p>停止後，原請求不能再出票；若已成功，會改為顯示原票券。不取消訂單、不退款。</p><button :disabled="reservationBusy" @click="lookup(true)">確認永久停止原出票請求</button><button :disabled="reservationBusy" @click="confirmStop=false">返回核對</button></div></template></div><p v-if="reservationMessage" class="notice" role="status">{{reservationMessage}}</p><p v-if="attemptPending&&customer?.id===lastAttempt?.owner&&lastAttempt?.progress.requestId===reservationPending?.body.requestId" class="notice" role="status">{{attemptMessage(lastAttempt!.progress)}}</p></template>
