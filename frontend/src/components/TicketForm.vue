<script setup lang="ts">
import { ref,watch } from 'vue'
import type { Ticket } from '../api'
const props=defineProps<{ticket?:Ticket;disabled:boolean}>(),emit=defineEmits<{save:[body:Record<string,unknown>]}>()
const name=ref(''),priceTwd=ref(1200),capacity=ref(100),maxPerCustomer=ref(2)
watch(()=>props.ticket,t=>{name.value=t?.name||'';priceTwd.value=t?.priceTwd||1200;capacity.value=t?.capacity||100;maxPerCustomer.value=t?.maxPerCustomer||2},{immediate:true})
</script>
<template><form @submit.prevent="emit('save',{name,priceTwd,capacity,maxPerCustomer,expectedVersion:ticket?.version||0})"><fieldset :disabled="disabled"><div class="form-grid"><label>票種名稱<input v-model="name" required maxlength="100"></label><label>票價（TWD）<input v-model.number="priceTwd" type="number" min="1" max="1000000" required></label><label>總票額<input v-model.number="capacity" type="number" min="1" max="100000" required></label><label>每人限購<input v-model.number="maxPerCustomer" type="number" min="1" :max="Math.min(10,capacity)" required></label></div><button class="primary">{{ticket?'儲存票種':'新增票種'}}</button></fieldset></form></template>
