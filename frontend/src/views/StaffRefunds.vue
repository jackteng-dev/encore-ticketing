<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api, ApiError, date, money, write, type Page, type Staff } from '../api'
interface Refund { id: string; orderId: string; eventTitle: string; sessionName: string; startsAt: string; ticketName: string; quantity: number; amountTwd: number; reason: string; status: string; reviewNote: string | null; reviewedAt: string | null; mockRefundId: string | null; version: number; createdAt: string; activeTickets: number; checkedInTickets: number; withdrawnAt: string | null }
const tabs = [['PENDING', '待審核'], ['APPROVED', '已核准'], ['REJECTED', '已駁回'], ['WITHDRAWN', '已撤回'], ['ALL', '全部']] as const
const router = useRouter(), staff = ref<Staff | null>(null), tab = ref<string>('PENDING'), data = ref<Page<Refund> | null>(null), error = ref(''), notes = ref<Record<string, string>>({}), busy = ref(false), page = ref(1)
let seq = 0
async function load() {
  const mine = ++seq; error.value = ''
  try {
    const r = await api<Page<Refund>>(`/admin/refunds?status=${tab.value}&page=${page.value}&size=20`); if (mine !== seq) return
    // 處理完最後一筆使目前頁變空時，退回上一頁
    if (!r.items.length && page.value > 1) { page.value = Math.max(1, Math.ceil(r.total / r.size)); return load() }
    data.value = r
  }
  catch (e) { if (mine !== seq) return; error.value = (e as Error).message; if (e instanceof ApiError && e.status === 401) await router.push('/staff/login') }
}
function pick(t: string) { tab.value = t; page.value = 1; void load() }
// 依 expectedVersion 審核；回覆不明時重新讀取後再決定，不盲目重送。
async function review(r: Refund, approve: boolean) {
  if (!staff.value || busy.value) return
  if (approve && !window.confirm(`核准退票 ${money(r.amountTwd)}？本展示為模擬退款，票券需另行撤銷。`)) return
  busy.value = true; error.value = ''; let failure = ''
  try { await write(`/admin/refunds/${r.id}/review`, 'POST', { expectedVersion: r.version, approve, note: notes.value[r.id]?.trim() || null }, staff.value.username) }
  catch (e) { failure = (e as Error).message + '（已重新讀取最新狀態，請確認後再操作）' } finally { busy.value = false; await load(); if (failure) error.value = failure }
}
interface RefundTicket { ticketId: string; sessionId: string; unit: number; state: string }
const tickets = ref<Record<string, RefundTicket[]>>({})
async function showTickets(r: Refund) { try { tickets.value[r.id] = await api<RefundTicket[]>(`/admin/refunds/${r.id}/tickets`) } catch (e) { error.value = (e as Error).message } }
const stateLabel = (s: string) => (s === 'ACTIVE' ? '尚未使用' : s === 'CHECKED_IN' ? '已入場' : '已撤銷')
async function move(n: number) { page.value += n; await load() }
onMounted(async () => { try { staff.value = await api<Staff>('/auth/me'); await load() } catch { await router.push('/staff/login') } })
</script>
<template>
  <section v-if="staff" class="content admin-page">
    <RouterLink to="/staff" class="back-link">← 活動管理</RouterLink>
    <div class="eyebrow">REFUNDS</div>
    <h1>退票審核</h1>
    <p class="muted">核准只記錄模擬退款，不改訂單與票額。核准後請到<RouterLink to="/staff/admissions">票券核對與驗票</RouterLink>撤銷未使用票券；票額不自動回補開賣。</p>
    <div class="filter-chips" role="tablist"><button v-for="[k, l] in tabs" :key="k" type="button" role="tab" class="chip" :class="{ on: tab === k }" :aria-selected="tab === k" @click="pick(k)">{{ l }}</button></div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <div v-if="data && !data.items.length" class="empty"><h2>沒有符合的申請</h2></div>
    <article v-for="r in data?.items" :key="r.id" class="panel refund-card">
      <div class="section-heading"><div><h2>{{ r.eventTitle }}</h2><p class="muted">{{ r.sessionName }} · {{ date(r.startsAt) }} · {{ r.ticketName }} × {{ r.quantity }}</p></div><strong>{{ money(r.amountTwd) }}</strong></div>
      <p>原因：{{ r.reason }}</p>
      <p class="muted">申請於 {{ date(r.createdAt) }} · 訂單 {{ r.orderId }} · 未使用票券 {{ r.activeTickets }} · 已入場 {{ r.checkedInTickets }}</p>
      <template v-if="r.status === 'PENDING'">
        <label>審核備註（駁回必填）<input v-model="notes[r.id]" maxlength="500"></label>
        <div class="button-row"><button class="primary" :disabled="busy || !staff.canWrite || r.checkedInTickets > 0" @click="review(r, true)">核准（模擬退款）</button><button :disabled="busy || !staff.canWrite || !notes[r.id]?.trim()" @click="review(r, false)">駁回</button></div>
        <p v-if="r.checkedInTickets > 0" class="notice warning">已有票券入場，不應核准退票。</p>
      </template>
      <div v-else-if="r.status === 'APPROVED'" class="refund-tickets">
        <p class="muted">已核准 · 模擬退款 {{ r.mockRefundId }}{{ r.reviewedAt ? ' · ' + date(r.reviewedAt) : '' }}{{ r.reviewNote ? ' · ' + r.reviewNote : '' }}</p>
        <button v-if="!tickets[r.id]" type="button" @click="showTickets(r)">查看此訂單票券</button>
        <ul v-else><li v-for="t in tickets[r.id]" :key="t.ticketId">第 {{ t.unit }} 張 · {{ stateLabel(t.state) }} <RouterLink v-if="t.state === 'ACTIVE'" :to="`/staff/admissions?ticketId=${t.ticketId}`">到驗票頁撤銷 ↗</RouterLink></li><li v-if="!tickets[r.id].length" class="muted">此訂單尚未領取票券，無需撤銷。</li></ul>
      </div>
      <p v-else class="muted">{{ r.status === 'REJECTED' ? '已駁回' : '會員已撤回' }}{{ r.reviewedAt ? ' · ' + date(r.reviewedAt) : '' }}{{ r.withdrawnAt ? ' · ' + date(r.withdrawnAt) : '' }}{{ r.reviewNote ? ' · ' + r.reviewNote : '' }}</p>
    </article>
    <div v-if="data && (data.total > data.size || page > 1)" class="pagination"><button :disabled="page === 1" @click="move(-1)">上一頁</button><span>{{ page }}</span><button :disabled="page * data.size >= data.total" @click="move(1)">下一頁</button></div>
  </section>
</template>
