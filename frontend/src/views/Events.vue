<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { api, date, money, type Category, type Page, type PublicEvent } from '../api'
import { posterArt } from '../poster'
import EventCard from '../components/EventCard.vue'
const data=ref<Page<PublicEvent>|null>(null),error=ref(''),page=ref(1),loading=ref(false)
const cards=computed(()=>(data.value?.items||[]).map(e=>({...e,art:posterArt(e.id,e.title)})))
const lead=computed(()=>page.value===1?cards.value[0]:undefined)
const ticker=computed(()=>cards.value.slice(0,8).map(e=>e.title))
const featuredFacts=computed(()=>{
 const e=lead.value;if(!e)return null
 return {from:e.minPriceTwd,when:e.firstStartsAt?date(e.firstStartsAt):null,sessions:e.sessionCount,selling:e.selling}
})
async function load(){loading.value=true;error.value='';try{data.value=await api<Page<PublicEvent>>(`/public/events?page=${page.value}&size=12`)}catch(e){error.value=(e as Error).message}finally{loading.value=false}}
const categories=ref<Category[]>([])
onMounted(()=>{void load();api<Category[]>('/public/categories').then(c=>categories.value=c,()=>categories.value=[])})
async function move(n:number){page.value+=n;await load()}
</script>
<template>
 <section v-if="lead" class="stage-hero" aria-label="焦點活動">
  <div class="stage-hero-inner">
   <RouterLink :to="`/events/${lead.id}`" class="poster" :class="lead.art.tone">
    <img v-if="lead.imageUrl" :src="lead.imageUrl" alt="" class="poster-img hero-img">
    <template v-else><span class="poster-mark" aria-hidden="true">{{lead.art.mark}}</span>
    <span class="poster-word">{{lead.art.word}}</span></template>
    <h1>{{lead.title}}</h1>
    <span class="hero-meta">{{lead.venue}}</span>
   </RouterLink>
   <div class="hero-side">
    <div class="hero-card">
     <div class="eyebrow">{{featuredFacts?.selling?'熱賣中':'焦點活動'}}</div>
     <template v-if="featuredFacts">
      <div v-if="featuredFacts.from!==null"><span class="muted">票價</span><div class="price">{{money(featuredFacts.from)}} 起</div></div>
      <div v-if="featuredFacts.when" class="muted">{{featuredFacts.when}} 起 · 共 {{featuredFacts.sessions}} 場</div>
     </template>
     <p class="muted">開賣時啟用等候室，依序取得購票資格。</p>
     <div class="button-row"><RouterLink :to="`/events/${lead.id}`" class="button-link primary">立即購票</RouterLink></div>
    </div>
    <div v-if="cards.length>1" class="hero-list">
     <RouterLink v-for="e in cards.slice(1,4)" :key="e.id" :to="`/events/${e.id}`">
      <span class="poster thumb" :class="e.art.tone" aria-hidden="true"><img v-if="e.imageUrl" :src="e.imageUrl" alt="" class="poster-img" loading="lazy"><template v-else>{{e.art.mark}}</template></span>
      <span><strong>{{e.title}}</strong><span>{{e.venue}}</span></span>
     </RouterLink>
    </div>
   </div>
  </div>
 </section>
 <div v-if="ticker.length" class="ticker" aria-hidden="true"><div class="ticker-track"><span v-for="(t,i) in [...ticker,...ticker]" :key="i">{{t}}</span></div></div>

 <section class="content">
  <div class="block-heading"><h2>NOW ON SALE</h2><span class="sub">即將相遇</span><span class="spacer"></span><span class="muted">{{ data?.total ?? '—' }} 場活動</span></div>
  <nav v-if="categories.length" class="category-nav" aria-label="活動分類"><RouterLink v-for="c in categories" :key="c.id" :to="`/search?category=${c.slug}`" class="category-tile"><strong>{{c.name}}</strong><span>{{c.eventCount}} 場</span></RouterLink></nav>
  <nav class="filter-chips" aria-label="快速探索"><RouterLink to="/search?onSale=true" class="chip">售票中</RouterLink><RouterLink to="/search?sort=date" class="chip">依演出日期</RouterLink><RouterLink to="/search?sort=price" class="chip">票價低到高</RouterLink><RouterLink to="/performers" class="chip">演出者</RouterLink><RouterLink to="/venues" class="chip">場館</RouterLink><RouterLink to="/search" class="chip">進階搜尋 →</RouterLink></nav>
  <p v-if="loading" role="status">正在載入活動…</p><div v-else-if="error" class="notice error" role="alert">{{error}} <button @click="load">重新讀取</button></div>
  <div v-else-if="!data?.items.length" class="empty"><span class="empty-icon" aria-hidden="true">ENCORE</span><h3>精彩，正在準備中</h3><p>目前沒有已發布的活動。發布後會出現在這裡。</p></div>
  <div v-else class="event-grid"><EventCard v-for="e in cards" :key="e.id" :event="e" /></div>
  <div v-if="data&&data.total>12" class="pagination"><button :disabled="page===1||loading" @click="move(-1)">上一頁</button><span>{{page}}</span><button :disabled="page*12>=data.total||loading" @click="move(1)">下一頁</button></div>
 </section>

 <section class="content" style="padding-top:0">
  <div class="fair-queue">
   <span class="display" aria-hidden="true">FAIR<br>QUEUE</span>
   <div>
    <h2>搶票不靠手速，靠排隊</h2>
    <p>開賣瞬間所有人進入同一個等候室，依序取得購票資格；保留的票在期限內完成模擬付款即可領取 QR 電子票，逾時自動釋出。</p>
    <ol class="fair-steps"><li><b>01</b>進入等候室</li><li><b>02</b>選擇票種張數</li><li><b>03</b>保留期內付款</li><li><b>04</b>領取 QR 票</li></ol>
   </div>
  </div>
 </section>
</template>
