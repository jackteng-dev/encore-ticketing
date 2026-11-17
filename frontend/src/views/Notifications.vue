<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api, ApiError, customerWrite, date, type Customer, type Page } from '../api'
import { currentCustomer, safeLink, unread, type SiteNotification } from '../engagement'
import { showMock } from '../mockNotice'
const router = useRouter(), customer = ref<Customer | null>(null), data = ref<Page<SiteNotification> | null>(null), error = ref(''), page = ref(1), busy = ref(false)
const channels = ['Email 通知', '簡訊通知', 'LINE 推播', 'App 推播']
let seq = 0
const loading = ref(false)
// 只採用最新一次讀取的結果，避免分頁回應亂序覆蓋目前頁面。
async function load() {
  const mine = ++seq
  error.value = ''; loading.value = true
  try {
    customer.value = await currentCustomer()
    if (!customer.value) { await router.replace({ path: '/account/login', query: { returnTo: '/account/notifications' } }); return }
    const result = await api<Page<SiteNotification>>(`/customer/notifications?page=${page.value}&size=20`, { headers: { 'X-Expected-Customer-ID': customer.value.id } })
    if (mine === seq) data.value = result
  } catch (e) { if (mine !== seq) return; error.value = (e as Error).message; if (e instanceof ApiError && e.status === 401) await router.replace({ path: '/account/login', query: { returnTo: '/account/notifications' } }) } finally { if (mine === seq) loading.value = false }
}
async function markRead(ids: string[], all = false) {
  if (!customer.value || busy.value) return
  busy.value = true; error.value = ''
  try { unread.value = (await customerWrite<{ unread: number }>('/customer/notifications/read', { ids, all }, customer.value.id)).unread; await load() } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function open(n: SiteNotification) { if (!n.readAt) await markRead([n.id]); await router.push(safeLink(n.link)) }
async function move(k: number) { page.value += k; await load() }
onMounted(load)
</script>
<template>
  <section class="content">
    <RouterLink to="/account" class="back-link">← 會員空間</RouterLink>
    <div class="eyebrow">NOTIFICATIONS</div>
    <div class="section-heading"><h1>通知中心</h1><button :disabled="busy || !unread" @click="markRead([], true)">全部標為已讀</button></div>
    <p v-if="error" class="notice error" role="alert">{{ error }} <button @click="load">重新讀取</button></p>
    <div v-if="data && !data.items.length" class="empty"><h2>目前沒有通知</h2><p>在活動頁設定「開賣提醒」，開賣時會通知你。</p></div>
    <ul v-else-if="data" class="notice-list">
      <li v-for="n in data.items" :key="n.id" :class="{ unread: !n.readAt }">
        <button type="button" class="notice-item" :disabled="busy" @click="open(n)">
          <span class="dot" aria-hidden="true"></span>
          <span><strong>{{ n.title }}</strong><span class="muted">{{ n.body }}</span><small class="muted">{{ date(n.createdAt) }}{{ n.readAt ? '' : ' · 未讀' }}</small></span>
        </button>
      </li>
    </ul>
    <div v-if="data && data.total > data.size" class="pagination"><button :disabled="page === 1 || loading" @click="move(-1)">上一頁</button><span>{{ page }}</span><button :disabled="page * data.size >= data.total || loading" @click="move(1)">下一頁</button></div>
    <section class="info-card channel-card">
      <div class="eyebrow">CHANNELS</div>
      <h2>通知管道</h2>
      <p class="muted">站內通知已啟用。其他管道為模擬版本，正式上線時才會串接。</p>
      <div class="filter-chips"><span class="chip on">站內通知（已啟用）</span><button v-for="c in channels" :key="c" type="button" class="chip" @click="showMock(c)">{{ c }}</button></div>
    </section>
  </section>
</template>
