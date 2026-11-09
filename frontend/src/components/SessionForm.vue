<script setup lang="ts">
import { ref,watch } from 'vue'
import type { Session } from '../api'
const props=defineProps<{session?:Session;disabled:boolean}>(),emit=defineEmits<{save:[body:Record<string,unknown>]}>()
const name=ref(''),startsAt=ref(''),salesStart=ref(''),salesEnd=ref('')
function local(s:string){const d=new Date(s);return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16)}
watch(()=>props.session,s=>{name.value=s?.name||'';startsAt.value=s?local(s.startsAt):'';salesStart.value=s?local(s.salesStart):'';salesEnd.value=s?local(s.salesEnd):''},{immediate:true})
function submit(){emit('save',{name:name.value,startsAt:new Date(startsAt.value).toISOString(),salesStart:new Date(salesStart.value).toISOString(),salesEnd:new Date(salesEnd.value).toISOString(),expectedVersion:props.session?.version||0})}
</script>
<template><form @submit.prevent="submit"><fieldset :disabled="disabled"><div class="form-grid"><label class="full">場次名稱<input v-model="name" required maxlength="120"></label><label>活動開始<input v-model="startsAt" type="datetime-local" required></label><label>售票開始<input v-model="salesStart" type="datetime-local" required></label><label>售票結束<input v-model="salesEnd" type="datetime-local" required></label></div><p class="muted">輸入使用瀏覽器本地時區；公開頁面統一顯示台北時間。</p><button class="primary">{{session?'儲存場次':'新增場次'}}</button></fieldset></form></template>
