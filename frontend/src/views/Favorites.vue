<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api, ApiError, date, money, type Customer, type Page } from '../api'
import { currentCustomer, setFlag, type FavoriteItem } from '../engagement'
import { posterArt } from '../poster'
const router = useRouter(), customer = ref<Customer | null>(null), data = ref<Page<FavoriteItem> | null>(null), error = ref(''), page = ref(1), busy = ref(false)
let seq = 0
const loading = ref(false)
// 只採用最新一次讀取的結果，避免分頁回應亂序覆蓋目前頁面。
async function load() {
  const mine = ++seq
  error.value = ''; loading.value = true
  try {
    customer.value = await currentCustomer()
    if (!customer.value) { await router.replace({ path: '/account/login', query: { returnTo: '/account/favorites' } }); return }
    const result = await api<Page<FavoriteItem>>(`/customer/favorites?page=${page.value}&size=12`, { headers: { 'X-Expected-Customer-ID': customer.value.id } })
    if (mine === seq) data.value = result
  } catch (e) { if (mine !== seq) return; error.value = (e as Error).message; if (e instanceof ApiError && e.status === 401) await router.replace({ path: '/account/login', query: { returnTo: '/account/favorites' } }) } finally { if (mine === seq) loading.value = false }
}
async function remove(id: string) {
  if (!customer.value || busy.value) return
  busy.value = true; error.value = ''
  try { await setFlag(customer.value, id, 'favorite', false); if (data.value && data.value.items.length === 1 && page.value > 1) page.value--; await load() } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function move(n: number) { page.value += n; await load() }
onMounted(load)
</script>
<template>
  <section class="content">
    <RouterLink to="/account" class="back-link">← 會員空間</RouterLink>
    <div class="eyebrow">MY FAVORITES</div>
    <h1>我的收藏</h1>
    <p v-if="error" class="notice error" role="alert">{{ error }} <button @click="load">重新讀取</button></p>
    <div v-if="data && !data.items.length" class="empty"><h2>還沒有收藏的活動</h2><p>在活動頁按下「♡ 收藏」，就能在這裡快速找到。</p><RouterLink to="/search" class="button-link primary">探索活動 →</RouterLink></div>
    <ul v-else-if="data" class="fav-list">
      <li v-for="f in data.items" :key="f.eventId" class="fav-row">
        <RouterLink :to="`/events/${f.eventId}`" class="poster thumb" :class="posterArt(f.eventId, f.title).tone" aria-hidden="true" tabindex="-1">{{ posterArt(f.eventId, f.title).mark }}</RouterLink>
        <div class="fav-main">
          <RouterLink :to="`/events/${f.eventId}`"><strong>{{ f.title }}</strong></RouterLink>
          <span class="muted">{{ f.venue }} · {{ f.firstStartsAt ? date(f.firstStartsAt) : '場次公布中' }}</span>
          <span><span v-if="f.selling" class="badge hot">售票中</span> {{ f.minPriceTwd === null ? '' : money(f.minPriceTwd) + ' 起' }}</span>
        </div>
        <button type="button" :disabled="busy" :aria-label="'取消收藏 ' + f.title" @click="remove(f.eventId)">取消收藏</button>
      </li>
    </ul>
    <div v-if="data && data.total > data.size" class="pagination"><button :disabled="page === 1 || loading" @click="move(-1)">上一頁</button><span>{{ page }}</span><button :disabled="page * data.size >= data.total || loading" @click="move(1)">下一頁</button></div>
  </section>
</template>
