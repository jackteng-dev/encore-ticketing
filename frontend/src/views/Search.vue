<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, type Page, type PublicEvent } from '../api'
import EventCard from '../components/EventCard.vue'
import { setTitle } from '../title'

const route = useRoute(), router = useRouter()
const str = (v: unknown) => (typeof v === 'string' ? v : '')
const datePattern = /^\d{4}-\d{2}-\d{2}$/
const sorts = { newest: '最新上架', date: '演出日期', price: '票價低到高' } as const
type Sort = keyof typeof sorts

// 狀態一律來自網址查詢參數，重新整理與分享連結都能還原同一個搜尋。
const q = ref(str(route.query.q).slice(0, 60))
const from = ref(datePattern.test(str(route.query.from)) ? str(route.query.from) : '')
const to = ref(datePattern.test(str(route.query.to)) ? str(route.query.to) : '')
const sort = ref<Sort>(str(route.query.sort) in sorts ? (str(route.query.sort) as Sort) : 'newest')
const onSale = ref(route.query.onSale === 'true')
const page = ref(Math.max(1, Number.parseInt(str(route.query.page)) || 1))
const data = ref<Page<PublicEvent> | null>(null), error = ref(''), loading = ref(false)

const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Taipei' }).format(new Date())
const addDays = (d: string, n: number) => { const x = new Date(d + 'T00:00:00Z'); x.setUTCDate(x.getUTCDate() + n); return x.toISOString().slice(0, 10) }
const quick = [
  { label: '全部日期', from: () => '', to: () => '' },
  // 結束日含當天整日（後端查詢至 to 隔日 00:00），今天算第 1 天。
  { label: '7 天內', from: today, to: () => addDays(today(), 6) },
  { label: '30 天內', from: today, to: () => addDays(today(), 29) },
]
const invalidRange = computed(() => !!from.value && !!to.value && to.value < from.value)

function query(p = 1) {
  const out: Record<string, string> = {}
  if (q.value.trim()) out.q = q.value.trim()
  if (from.value) out.from = from.value
  if (to.value) out.to = to.value
  if (sort.value !== 'newest') out.sort = sort.value
  if (onSale.value) out.onSale = 'true'
  if (p > 1) out.page = String(p)
  return out
}
async function load() {
  if (invalidRange.value) { error.value = '結束日期不可早於開始日期'; return }
  loading.value = true; error.value = ''
  try {
    const params = new URLSearchParams({ ...query(page.value), page: String(page.value), size: '12' })
    data.value = await api<Page<PublicEvent>>('/public/events?' + params.toString())
  } catch (e) { error.value = (e as Error).message } finally { loading.value = false }
}
function apply(p = 1) { void router.push({ path: '/search', query: query(p) }) }
function pick(i: number) { from.value = quick[i].from(); to.value = quick[i].to(); apply() }
const activeQuick = computed(() => quick.findIndex(x => x.from() === from.value && x.to() === to.value))
const heading = computed(() => q.value.trim() ? `「${q.value.trim()}」的搜尋結果` : '探索全部活動')

onMounted(() => { setTitle(q.value.trim() ? `搜尋：${q.value.trim()}` : '探索活動'); void load() })
</script>
<template>
  <section class="content search-page">
    <div class="eyebrow">EXPLORE</div>
    <h1>{{ heading }}</h1>
    <form class="search-filters" role="search" @submit.prevent="apply()">
      <label class="grow">關鍵字<input v-model="q" type="search" maxlength="60" placeholder="活動名稱、場館"></label>
      <label>開始日期<input v-model="from" type="date"></label>
      <label>結束日期<input v-model="to" type="date" :min="from || undefined"></label>
      <label>排序<select v-model="sort" @change="apply()"><option v-for="(label, key) in sorts" :key="key" :value="key">{{ label }}</option></select></label>
      <button class="primary" :disabled="invalidRange">搜尋</button>
    </form>
    <div class="filter-chips" role="group" aria-label="快速篩選">
      <button v-for="(x, i) in quick" :key="x.label" type="button" class="chip" :class="{ on: activeQuick === i }" :aria-pressed="activeQuick === i" @click="pick(i)">{{ x.label }}</button>
      <label class="chip check"><input v-model="onSale" type="checkbox" @change="apply()">只看售票中</label>
    </div>
    <p v-if="invalidRange" class="notice error" role="alert">結束日期不可早於開始日期</p>
    <p v-else-if="loading" role="status">正在搜尋…</p>
    <div v-else-if="error" class="notice error" role="alert">{{ error }} <button @click="load">重新讀取</button></div>
    <template v-else-if="data">
      <p class="muted result-count" role="status">共 {{ data.total }} 場活動</p>
      <div v-if="!data.items.length" class="empty"><h2>找不到符合的活動</h2><p>試試其他關鍵字，或放寬日期範圍。</p></div>
      <div v-else class="event-grid"><EventCard v-for="e in data.items" :key="e.id" :event="e" /></div>
      <div v-if="data.total > data.size" class="pagination">
        <button :disabled="page === 1" @click="apply(page - 1)">上一頁</button>
        <span>第 {{ page }} / {{ Math.ceil(data.total / data.size) }} 頁</span>
        <button :disabled="page * data.size >= data.total" @click="apply(page + 1)">下一頁</button>
      </div>
    </template>
  </section>
</template>
