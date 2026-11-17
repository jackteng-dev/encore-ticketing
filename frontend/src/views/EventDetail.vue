<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { api,date,money,status,type Detail,type Session } from '../api'
import { posterArt } from '../poster'
import { setTitle } from '../title'
const route=useRoute(),data=ref<Detail|null>(null),error=ref('')
async function load(){error.value='';try{data.value=await api<Detail>(`/public/events/${route.params.id}`);setTitle(data.value.event.title)}catch(e){error.value=(e as Error).message}}
onMounted(load)
const art=computed(()=>data.value?posterArt(data.value.event.id,data.value.event.title):null)
const facts=computed(()=>{
 const d=data.value;if(!d)return null
 const tickets=d.sessions.flatMap(s=>s.tickets),prices=tickets.map(t=>t.priceTwd),limits=tickets.map(t=>t.maxPerCustomer)
 const starts=d.sessions.map(s=>s.startsAt).sort()
 return {
  when:starts.length?(starts.length>1?date(starts[0])+' 起':date(starts[0])):'場次公布中',
  price:prices.length?(Math.min(...prices)===Math.max(...prices)?money(prices[0]):money(Math.min(...prices))+' – '+money(Math.max(...prices))):'票價公布中',
  limit:limits.length?'每人每票種 '+Math.min(...limits)+' 張起':'—'
 }
})
const dayLabel=(s:Session)=>new Intl.DateTimeFormat('zh-TW',{timeZone:'Asia/Taipei',month:'2-digit',day:'2-digit'}).format(new Date(s.startsAt)).replace('/','.')
const badgeTone=(s:Session)=>s.salesStatus==='SELLING'?'hot':s.salesStatus==='UPCOMING'?'soon':'out'
</script>
<template>
 <p v-if="error" class="content notice error" role="alert">{{error}} <button @click="load">重新讀取</button></p>
 <template v-if="data&&art&&facts">
  <section class="event-hero">
   <div class="event-hero-inner">
    <RouterLink to="/" class="back-link">← 所有活動</RouterLink>
    <div class="poster" :class="art.tone" aria-hidden="true"><span class="poster-mark">{{art.mark}}</span><span class="poster-tag">ENCORE / LIVE</span><span class="poster-word">{{art.word}}</span></div>
    <div class="event-hero-copy">
     <div class="eyebrow">ENCORE / LIVE EXPERIENCE</div>
     <h1>{{data.event.title}}</h1>
     <dl class="fact-grid">
      <div><dt>DATE</dt><dd>{{facts.when}}</dd></div>
      <div><dt>VENUE</dt><dd>{{data.event.venue}}</dd></div>
      <div><dt>PRICE</dt><dd>{{facts.price}}</dd></div>
      <div><dt>LIMIT</dt><dd>{{facts.limit}}</dd></div>
     </dl>
    </div>
   </div>
  </section>

  <div class="event-body">
   <div class="event-main">
    <ol class="steps" aria-label="購票步驟">
     <li class="on"><b>01</b>選擇場次與區域</li>
     <li :class="{on:data.waitingRoomEnabled}"><b>02</b>{{data.waitingRoomEnabled?'等候室':'選擇張數'}}</li>
     <li><b>03</b>保票與模擬付款</li>
     <li><b>04</b>領取 QR 票</li>
    </ol>
    <h2 style="margin:0">場次與票種</h2>
    <p v-if="!data.sessions.length" class="empty">場次公布中，請稍後再回來看看。</p>
    <article v-for="s in data.sessions" :key="s.id" class="session-block">
     <div class="session-block-head">
      <span class="day" aria-hidden="true">{{dayLabel(s)}}</span>
      <div><h3>{{s.name}}</h3><p class="meta" style="margin:0">{{date(s.startsAt)}} · 台北時間 · 售票 {{date(s.salesStart)}} — {{date(s.salesEnd)}}</p></div>
      <span class="badge" :class="badgeTone(s)">{{status(s.salesStatus)}}</span>
     </div>
     <div class="table-scroll">
      <table class="ticket-table">
       <thead><tr><th scope="col">區域／票種</th><th scope="col">票價</th><th scope="col">剩餘</th><th scope="col">購買</th></tr></thead>
       <tbody>
        <tr v-for="t in s.tickets" :key="t.id" :class="{'sold-out':t.available===0}">
         <td class="name"><span class="swatch" aria-hidden="true"></span>{{t.name}}<small>每人限購 {{t.maxPerCustomer}} 張</small></td>
         <td class="price">{{money(t.priceTwd)}}</td>
         <td class="left" :class="{low:t.available>0&&t.available<20}">{{t.available===0?'已售完':`剩餘 ${t.available.toLocaleString()} / ${t.capacity.toLocaleString()} 張`}}</td>
         <td><RouterLink v-if="data.orderingEnabled&&s.salesStatus==='SELLING'&&t.available>0" class="button-link primary" :to="'/events/'+data.event.id+'/reserve/'+t.id">選擇張數 →</RouterLink><button v-else disabled>{{!data.orderingEnabled?'購票尚未開放':s.salesStatus==='SELLING'?'票額已保留完':'此場次尚未開放保票'}}</button></td>
        </tr>
       </tbody>
      </table>
     </div>
    </article>
    <div v-if="data.event.description" class="info-card"><div class="eyebrow">ABOUT</div><p class="description">{{data.event.description}}</p></div>
   </div>

   <aside class="event-aside">
    <div class="summary-card">
     <span class="label">購票說明</span>
     <p class="notice" style="margin:0">{{data.orderingEnabled?'可建立展示保票訂單，付款僅限免費模擬，沒有真收款；電子票券需模擬付款確認後另行領取。':'本階段提供活動與場次資訊，購票尚未開放。'}}查看活動不會保留票額。</p>
     <p v-if="data.waitingRoomEnabled" class="muted" style="margin:0">開賣時啟用等候室：選擇票種後先加入等候室，取得入場資格才能保票。</p>
    </div>
    <div class="info-card">
     <div class="eyebrow">NOTICE</div>
     <ol>
      <li>保票後在期限內完成模擬付款，逾時自動釋出。</li>
      <li>付款確認後於「我的票券」領取 QR 電子票。</li>
      <li>入場時由現場員工掃描驗票，一張票只能使用一次。</li>
     </ol>
    </div>
   </aside>
  </div>
 </template>
</template>
