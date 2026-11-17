<script setup lang="ts">
import { computed, ref } from 'vue'
import { customerWrite, date, money, type Customer, type Order, type Refund } from '../api'
const props = defineProps<{ order: Order; refund: Refund | null; customer: Customer }>()
const emit = defineEmits<{ changed: [] }>()
const open = ref(false), reason = ref(''), busy = ref(false), error = ref('')
// 未知回覆時保留原 requestId 與內容重送，不另建新申請。
const key = computed(() => 'encore-refund-pending:' + props.customer.id + ':' + props.order.id)
function pending(): { requestId: string; reason: string } | null { try { const v = JSON.parse(sessionStorage.getItem(key.value) || 'null'); return v && typeof v.requestId === 'string' ? v : null } catch { return null } }
const unsure = ref(pending())
const labels: Record<string, string> = { PENDING: '退票審核中', APPROVED: '已核准退票（模擬退款）', REJECTED: '退票申請未通過', WITHDRAWN: '已撤回退票申請' }
const refundable = computed(() => props.order.status === 'PAID' && Date.parse(props.order.startsAt) - Date.now() > 24 * 3600 * 1000)
async function submit() {
  if (busy.value) return
  const p = unsure.value || { requestId: crypto.randomUUID(), reason: reason.value.trim() }
  if (!p.reason) { error.value = '請填寫退票原因'; return }
  sessionStorage.setItem(key.value, JSON.stringify(p)); unsure.value = p
  busy.value = true; error.value = ''
  try { await customerWrite(`/customer/orders/${props.order.id}/refund`, p, props.customer.id); sessionStorage.removeItem(key.value); unsure.value = null; open.value = false; reason.value = ''; emit('changed') }
  catch (e) { error.value = (e as Error).message + '。若不確定是否已送出，請按「重試原申請」，不會重複建立。' }
  finally { busy.value = false }
}
async function withdraw() {
  if (!props.refund || busy.value) return
  busy.value = true; error.value = ''
  try { await customerWrite(`/customer/refunds/${props.refund.id}/withdraw`, { expectedVersion: props.refund.version }, props.customer.id); emit('changed') }
  catch (e) { error.value = (e as Error).message; emit('changed') } finally { busy.value = false }
}
</script>
<template>
  <div class="refund-panel">
    <template v-if="refund && refund.status !== 'WITHDRAWN' && refund.status !== 'REJECTED'">
      <p><span class="badge" :class="refund.status === 'APPROVED' ? 'hot' : 'few'">{{ labels[refund.status] }}</span> {{ money(refund.amountTwd) }} · 申請於 {{ date(refund.createdAt) }}</p>
      <p v-if="refund.status === 'APPROVED'" class="muted">模擬退款編號 {{ refund.mockRefundId }}。本展示不實際退款；票券將由主辦方撤銷。{{ refund.reviewNote ? '備註：' + refund.reviewNote : '' }}</p>
      <button v-if="refund.status === 'PENDING'" type="button" :disabled="busy" @click="withdraw">撤回退票申請</button>
    </template>
    <template v-else>
      <p v-if="refund" class="muted">{{ labels[refund.status] }}{{ refund.reviewNote ? '：' + refund.reviewNote : '' }}</p>
      <div v-if="unsure" class="notice warning" role="status">退票申請結果尚未確認。請以原申請重試核對（不會重複建立）；確認後若不需要可再撤回。<button type="button" :disabled="busy" @click="submit">重試原申請</button></div>
      <template v-else-if="refundable">
        <button v-if="!open" type="button" @click="open = true">申請退票</button>
        <form v-else class="refund-form" @submit.prevent="submit">
          <label>退票原因<textarea v-model="reason" required maxlength="500" rows="3" placeholder="例如：行程衝突"></textarea></label>
          <p class="muted">開演前 24 小時內不受理；已入場的票券無法退票。核准後為模擬退款，不會實際退款。</p>
          <div class="button-row"><button class="primary" :disabled="busy || !reason.trim()">送出退票申請</button><button type="button" :disabled="busy" @click="open = false">取消</button></div>
        </form>
      </template>
      <p v-else-if="order.status === 'PAID'" class="muted">已超過退票期限（開演前 24 小時）。</p>
    </template>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  </div>
</template>
