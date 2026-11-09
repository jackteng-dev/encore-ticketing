<script setup lang="ts">
import { ref,computed,onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api,write,status,date,money,ApiError,type Staff,type CatalogEvent,type Detail,type Page } from '../api'
import { pending,busy,commandMessage,command,retry } from '../commands'
import EventForm from '../components/EventForm.vue'
import SessionForm from '../components/SessionForm.vue'
import TicketForm from '../components/TicketForm.vue'
const router=useRouter(),staff=ref<Staff|null>(null),list=ref<Page<CatalogEvent>|null>(null),detail=ref<Detail|null>(null),error=ref(''),page=ref(1),newEvent=ref(false)
const editable=computed(()=>!!staff.value?.canWrite&&!pending.value&&!busy.value)
const draft=computed(()=>detail.value?.event.status==='DRAFT')
const audit=ref<Page<{requestId:string;action:string;resourceId:string;createdAt:string}>|null>(null)
async function load(clearError=true){if(clearError)error.value='';try{list.value=await api(`/admin/events?page=${page.value}&size=12`);audit.value=await api('/admin/audit')}catch(e){error.value=(e as Error).message;if(e instanceof ApiError&&e.status===401)await router.push('/staff/login')}}
async function select(id:string){error.value='';newEvent.value=false;try{detail.value=await api(`/admin/events/${id}`)}catch(e){error.value=(e as Error).message}}
async function perform(path:string,method:string,body:Record<string,unknown>){if(!staff.value)return;error.value='';try{const result=await command(staff.value,path,method,body,detail.value?.event.id);if(path==='/admin/events'){newEvent.value=false;await select(result.id)}else if(detail.value)await select(detail.value.event.id);await load(false)}catch(e){error.value=(e as Error).message}}
async function retryOriginal(){if(!staff.value)return;const original=pending.value;error.value='';try{const result=await retry(staff.value);await select(original?.eventId||result.id);await load(false)}catch(e){error.value=(e as Error).message}}
async function transition(action:string){if(!detail.value||!window.confirm(action==='publish'?'發布後活動、場次與票種條件將鎖定，確定發布？':'封存後活動將從公開頁面移除，確定封存？'))return;await perform(`/admin/events/${detail.value.event.id}/${action}`,'POST',{expectedVersion:detail.value.event.version})}
async function logout(){try{await write('/auth/logout','POST',{});await router.push('/staff/login')}catch(e){error.value=(e as Error).message}}
onMounted(async()=>{try{staff.value=await api('/auth/me');await load()}catch{await router.push('/staff/login')}})
</script>
<template><section v-if="staff" class="content admin-page"><div class="section-heading"><div><div class="eyebrow">STAFF WORKSPACE</div><h1>活動管理</h1><p>{{staff.username}} · {{staff.canWrite?'管理員':'唯讀員工'}}</p></div><button @click="logout">登出</button></div>
 <RouterLink to="/staff/admissions" class="back-link">票券核對與驗票 ↗</RouterLink>
 <RouterLink to="/staff/monitoring" class="back-link">執行監控 ↗</RouterLink>
 <RouterLink to="/staff/stock" class="back-link">庫存維護與核對 ↗</RouterLink>
 <div v-if="pending" class="notice warning" role="status"><strong>有一筆操作尚未確認。</strong><p>原員工：{{pending.owner}}。其他寫入已暫停，重新整理仍會保留原操作。</p><RouterLink v-if="pending.path.startsWith('/admin/stock/')" to="/staff/stock">核對原維護操作 ↗</RouterLink><RouterLink v-else-if="pending.path.startsWith('/admin/admissions/')" to="/staff/admissions">核對原票券操作 ↗</RouterLink><button v-else :disabled="busy||staff.username!==pending.owner" @click="retryOriginal">重試原操作</button></div>
 <p v-if="commandMessage&&!pending" class="notice" role="status">{{commandMessage}}</p><div v-if="error" class="notice error" role="alert">{{error}} <button @click="load()">重讀清單</button></div>
 <div class="admin-grid"><aside class="panel event-sidebar"><div class="section-heading"><h2>活動清單</h2><button v-if="staff.canWrite" :disabled="!editable" @click="detail=null;newEvent=true">＋ 新增</button></div><button v-for="e in list?.items" :key="e.id" class="event-select" :class="{selected:detail?.event.id===e.id}" @click="select(e.id)"><strong>{{e.title}}</strong><span>{{status(e.status)}} · v{{e.version}}</span></button><p v-if="!list?.items.length" class="muted">尚無活動，先建立一份草稿。</p><div class="pagination"><button :disabled="page===1" @click="page--;load()">上一頁</button><span>{{page}}</span><button :disabled="!list||page*12>=list.total" @click="page++;load()">下一頁</button></div></aside>
 <div><section v-if="newEvent" class="panel"><h2>建立活動草稿</h2><EventForm :disabled="!editable" @save="perform('/admin/events','POST',$event)" /></section>
 <template v-else-if="detail"><section class="panel"><div class="section-heading"><h2>{{detail.event.title}}</h2><span class="badge">{{status(detail.event.status)}} · v{{detail.event.version}}</span></div><EventForm :event="detail.event" :disabled="!editable||!draft" @save="perform(`/admin/events/${detail!.event.id}`,'PUT',$event)"/><div v-if="staff.canWrite" class="actions"><button v-if="draft" :disabled="!editable" @click="transition('publish')">發布活動 ↗</button><button v-if="detail.event.status!=='ARCHIVED'" :disabled="!editable" @click="transition('archive')">封存活動</button></div><p class="muted">發布後鎖定條件；封存保留資料與稽核，不提供刪除。</p></section>
 <section v-for="s in detail.sessions" :key="s.id" class="panel"><h2>{{s.name}}</h2><SessionForm :session="s" :disabled="!editable||!draft" @save="perform(`/admin/sessions/${s.id}`,'PUT',$event)"/><h3>票種</h3><details v-for="t in s.tickets" :key="t.id" class="ticket-editor"><summary>{{t.name}} · {{money(t.priceTwd)}} · {{t.capacity}} 張 · v{{t.version}}</summary><TicketForm :ticket="t" :disabled="!editable||!draft" @save="perform(`/admin/tickets/${t.id}`,'PUT',$event)"/></details><details v-if="staff.canWrite&&draft" class="ticket-editor"><summary>＋ 新增票種</summary><TicketForm :disabled="!editable" @save="perform(`/admin/sessions/${s.id}/tickets`,'POST',$event)"/></details></section>
 <section v-if="staff.canWrite&&draft" class="panel"><h2>新增場次</h2><SessionForm :disabled="!editable" @save="perform(`/admin/events/${detail!.event.id}/sessions`,'POST',$event)"/></section></template>
 <section v-else class="panel empty"><span class="empty-icon">◎</span><h2>從一場期待開始</h2><p>選擇活動，或建立新的草稿。</p></section>
 </div></div><section class="panel audit"><h2>最近的操作紀錄</h2><p class="muted">僅顯示最近20筆；歷史持久保存，沒有刪改入口。</p><div v-for="a in audit?.items" :key="a.requestId" class="audit-row"><strong>{{a.action}}</strong><span>{{date(a.createdAt)}}</span><code>{{a.resourceId}}</code></div></section></section></template>
