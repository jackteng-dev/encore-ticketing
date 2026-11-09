<script setup lang="ts">
import { ref,watch } from 'vue'
import type { CatalogEvent } from '../api'
const props=defineProps<{event?:CatalogEvent;disabled:boolean}>()
const emit=defineEmits<{save:[body:Record<string,unknown>]}>()
const slug=ref(''),title=ref(''),venue=ref(''),description=ref('')
watch(()=>props.event,e=>{slug.value=e?.slug||'';title.value=e?.title||'';venue.value=e?.venue||'';description.value=e?.description||''},{immediate:true})
</script>
<template><form @submit.prevent="emit('save',{slug,title,venue,description,expectedVersion:event?.version||0})"><fieldset :disabled="disabled"><div class="form-grid"><label>活動名稱<input v-model="title" required maxlength="120"></label><label>活動代碼<input v-model="slug" required pattern="[a-z0-9]+(-[a-z0-9]+)*" maxlength="80" placeholder="例如 summer-live"></label><label class="full">場地<input v-model="venue" required maxlength="160"></label><label class="full">活動介紹<textarea v-model="description" maxlength="3000" rows="4"></textarea></label></div><button class="primary">{{event?'儲存活動':'建立草稿'}}</button></fieldset></form></template>
