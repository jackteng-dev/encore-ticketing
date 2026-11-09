<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { api, type CatalogEvent, type Page } from '../api'
const data=ref<Page<CatalogEvent>|null>(null),error=ref(''),page=ref(1),loading=ref(false)
async function load(){loading.value=true;error.value='';try{data.value=await api(`/public/events?page=${page.value}&size=12`)}catch(e){error.value=(e as Error).message}finally{loading.value=false}}
onMounted(load)
async function move(n:number){page.value+=n;await load()}
</script>
<template>
 <section class="hero"><div class="eyebrow">A LITTLE ANTICIPATION. A LOT OF LIFE.</div><h1>下一個現場，<br>有你的位置。</h1><p>音樂、舞台，以及那些值得一起記住的時刻。<br>從這裡，找到你的下一場期待。</p><span class="hero-mark" aria-hidden="true">e.</span><div class="hero-foot"><span>EXPLORE / DISCOVER / EXPERIENCE</span><span>↓ 往下探索</span></div></section>
 <section class="content"><div class="section-heading"><div><div class="eyebrow">WHAT'S NEXT</div><h2>即將相遇</h2></div><span class="muted">{{ data?.total ?? '—' }} 場活動</span></div>
  <p v-if="loading" role="status">正在載入活動…</p><div v-else-if="error" class="notice error" role="alert">{{error}} <button @click="load">重新讀取</button></div>
  <div v-else-if="!data?.items.length" class="empty"><span class="empty-icon">◎</span><h3>精彩，正在準備中</h3><p>目前沒有已發布的活動。發布後會出現在這裡。</p></div>
  <div v-else class="event-grid"><RouterLink v-for="(e,i) in data.items" :key="e.id" :to="`/events/${e.id}`" class="event-card"><div class="poster" :class="`tone-${i%3}`"><span>ENCORE / LIVE</span><strong>{{e.title.slice(0,2)}}</strong><small>LET'S BE THERE.</small></div><div class="card-copy"><span class="eyebrow">{{e.venue}}</span><h3>{{e.title}}</h3><p>{{e.description.slice(0,90)}}</p><span class="card-link">查看場次 <span>↗</span></span></div></RouterLink></div>
  <div v-if="data&&data.total>12" class="pagination"><button :disabled="page===1||loading" @click="move(-1)">上一頁</button><span>{{page}}</span><button :disabled="page*12>=data.total||loading" @click="move(1)">下一頁</button></div>
 </section>
</template>
