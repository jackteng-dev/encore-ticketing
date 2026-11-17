<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api, ApiError, date, money, status, type Page } from '../api'
import { downloadCsv } from '../csv'

interface TicketReport { ticketId: string; name: string; priceTwd: number; capacity: number; held: number; sold: number; available: number; paidOrders: number; paidQuantity: number; paidRevenueTwd: number; pendingOrders: number; cancelledOrders: number; expiredOrders: number; issuedTickets: number; checkedIn: number }
interface SessionReport { sessionId: string; name: string; startsAt: string; salesStart: string; salesEnd: string; tickets: TicketReport[] }
interface DailySales { date: string; paidOrders: number; paidQuantity: number; revenueTwd: number }
interface Totals { capacity: number; held: number; sold: number; available: number; paidOrders: number; revenueTwd: number; issuedTickets: number; checkedIn: number }
interface EventReport { eventId: string; title: string; status: string; generatedAt: string; totals: Totals; sessions: SessionReport[]; daily: DailySales[] }
interface EventSummary { eventId: string; title: string; status: string; capacity: number; sold: number; held: number; revenueTwd: number }

const router = useRouter(), list = ref<Page<EventSummary> | null>(null), report = ref<EventReport | null>(null), error = ref(''), page = ref(1), loading = ref(false)
let seq = 0
const pct = (a: number, b: number) => (b ? Math.round((a / b) * 1000) / 10 : 0)
const maxDaily = computed(() => Math.max(1, ...(report.value?.daily.map(d => d.revenueTwd) || [0])))

let listSeq = 0
const listLoading = ref(false)
// 只採用最新一次列表請求，避免分頁回應亂序覆蓋。
async function loadList() {
  const mine = ++listSeq; error.value = ''; listLoading.value = true
  try { const r = await api<Page<EventSummary>>(`/admin/reports/events?page=${page.value}&size=20`); if (mine === listSeq) list.value = r }
  catch (e) { if (mine !== listSeq) return; error.value = (e as Error).message; if (e instanceof ApiError && e.status === 401) await router.push('/staff/login') }
  finally { if (mine === listSeq) listLoading.value = false }
}
async function open(id: string) {
  const mine = ++seq; loading.value = true; error.value = ''
  try { const r = await api<EventReport>(`/admin/reports/events/${id}`); if (mine === seq) report.value = r }
  catch (e) { if (mine === seq) error.value = (e as Error).message } finally { if (mine === seq) loading.value = false }
}
async function move(n: number) { page.value += n; await loadList() }

function exportTickets() {
  const r = report.value; if (!r) return
  const rows: (string | number)[][] = [['活動', '場次', '開演時間', '票種', '票價', '票額', '已售', '保留中', '剩餘', '已付款訂單', '已付款張數', '營收(TWD)', '待付款訂單', '已取消', '已逾期', '已出票', '已入場']]
  for (const s of r.sessions) for (const t of s.tickets) rows.push([r.title, s.name, date(s.startsAt), t.name, t.priceTwd, t.capacity, t.sold, t.held, t.available, t.paidOrders, t.paidQuantity, t.paidRevenueTwd, t.pendingOrders, t.cancelledOrders, t.expiredOrders, t.issuedTickets, t.checkedIn])
  downloadCsv(`encore-report-${r.eventId.slice(0, 8)}-tickets.csv`, rows)
}
function exportDaily() {
  const r = report.value; if (!r) return
  downloadCsv(`encore-report-${r.eventId.slice(0, 8)}-daily.csv`, [['日期(台北)', '已付款訂單', '已付款張數', '營收(TWD)'], ...r.daily.map(d => [d.date, d.paidOrders, d.paidQuantity, d.revenueTwd])])
}
onMounted(async () => { try { await api('/auth/me'); await loadList() } catch { await router.push('/staff/login') } })
</script>
<template>
  <section class="content admin-page">
    <RouterLink to="/staff" class="back-link">← 活動管理</RouterLink>
    <div class="eyebrow">SALES REPORTS</div>
    <h1>銷售報表</h1>
    <p class="muted">票額以資料庫票種為準；營收只計已付款訂單（本展示為模擬付款，非真實收入）。匯出只含彙總數字，不含會員個資。</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <div class="admin-grid">
      <aside class="panel event-sidebar">
        <div class="section-heading"><h2>活動</h2></div>
        <button v-for="e in list?.items" :key="e.eventId" class="event-select" :class="{ selected: report?.eventId === e.eventId }" @click="open(e.eventId)">
          <strong>{{ e.title }}</strong><span>{{ status(e.status) }} · 售出 {{ e.sold }}/{{ e.capacity }} · {{ money(e.revenueTwd) }}</span>
        </button>
        <div v-if="list && list.total > list.size" class="pagination"><button :disabled="page === 1 || listLoading" @click="move(-1)">上一頁</button><span>{{ page }}</span><button :disabled="page * list.size >= list.total || listLoading" @click="move(1)">下一頁</button></div>
      </aside>
      <div>
        <p v-if="loading" role="status">讀取報表中…</p>
        <div v-else-if="!report" class="empty"><h2>選擇左側活動查看報表</h2></div>
        <template v-else>
          <section class="panel">
            <div class="section-heading"><div><h2>{{ report.title }}</h2><p class="muted">{{ status(report.status) }} · 產生於 {{ date(report.generatedAt) }}</p></div>
              <div class="button-row"><button @click="exportTickets">匯出票種 CSV</button><button :disabled="!report.daily.length" @click="exportDaily">匯出每日 CSV</button></div></div>
            <div class="kpi-grid">
              <div class="kpi"><span>營收（已付款）</span><strong>{{ money(report.totals.revenueTwd) }}</strong></div>
              <div class="kpi"><span>售出率</span><strong>{{ pct(report.totals.sold, report.totals.capacity) }}%</strong><small>{{ report.totals.sold }} / {{ report.totals.capacity }} 張</small></div>
              <div class="kpi"><span>保留中</span><strong>{{ report.totals.held }}</strong><small>剩餘 {{ report.totals.available }} 張</small></div>
              <div class="kpi"><span>已付款訂單</span><strong>{{ report.totals.paidOrders }}</strong></div>
              <div class="kpi"><span>入場率</span><strong>{{ pct(report.totals.checkedIn, report.totals.issuedTickets) }}%</strong><small>{{ report.totals.checkedIn }} / {{ report.totals.issuedTickets }} 張票</small></div>
            </div>
          </section>
          <section v-for="s in report.sessions" :key="s.sessionId" class="panel">
            <h2>{{ s.name }}</h2><p class="muted">{{ date(s.startsAt) }} · 售票 {{ date(s.salesStart) }} — {{ date(s.salesEnd) }}</p>
            <div class="table-scroll">
              <table class="report-table">
                <thead><tr><th scope="col">票種</th><th scope="col">票價</th><th scope="col">售出率</th><th scope="col">已售</th><th scope="col">保留</th><th scope="col">剩餘</th><th scope="col">營收</th><th scope="col">待付款／取消／逾期</th><th scope="col">入場</th></tr></thead>
                <tbody>
                  <tr v-for="t in s.tickets" :key="t.ticketId">
                    <th scope="row">{{ t.name }}</th><td>{{ money(t.priceTwd) }}</td>
                    <td><span class="bar" role="img" :aria-label="`售出率 ${pct(t.sold, t.capacity)}%`"><i :style="{ width: pct(t.sold, t.capacity) + '%' }"></i></span>{{ pct(t.sold, t.capacity) }}%</td>
                    <td>{{ t.sold }}</td><td>{{ t.held }}</td><td>{{ t.available }}</td><td>{{ money(t.paidRevenueTwd) }}</td>
                    <td>{{ t.pendingOrders }} / {{ t.cancelledOrders }} / {{ t.expiredOrders }}</td><td>{{ t.checkedIn }} / {{ t.issuedTickets }}</td>
                  </tr>
                  <tr v-if="!s.tickets.length"><td colspan="9" class="muted">此場次尚無票種。</td></tr>
                </tbody>
              </table>
            </div>
          </section>
          <section class="panel">
            <h2>每日營收</h2>
            <p v-if="!report.daily.length" class="muted">尚無已付款訂單。</p>
            <ol v-else class="daily-chart">
              <li v-for="d in report.daily" :key="d.date"><span class="d">{{ d.date }}</span><span class="bar"><i :style="{ width: (d.revenueTwd / maxDaily) * 100 + '%' }"></i></span><span class="v">{{ money(d.revenueTwd) }} · {{ d.paidQuantity }} 張</span></li>
            </ol>
          </section>
        </template>
      </div>
    </div>
  </section>
</template>
