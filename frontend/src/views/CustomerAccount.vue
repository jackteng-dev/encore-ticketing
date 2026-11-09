<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { RouterLink,useRouter } from 'vue-router'
import { api,customerWrite,ApiError,type Customer } from '../api'
const router=useRouter(),customer=ref<Customer|null>(null),error=ref(''),busy=ref(false)
async function load(){busy.value=true;error.value='';try{customer.value=await api<Customer>('/customer/auth/me')}catch(e){customer.value=null;if(e instanceof ApiError&&e.status===401)await router.replace('/account/login');else error.value=(e as Error).message}finally{busy.value=false}}
async function logout(){busy.value=true;error.value='';try{await customerWrite('/customer/auth/logout',{});customer.value=null;await router.replace('/account/login')}catch(e){error.value=(e as Error).message+'。登出結果不明時請重試登出。'}finally{busy.value=false}}
onMounted(load)
</script>
<template><section class="content detail-page"><div class="eyebrow">MEMBER SPACE</div><h1>你的會員空間。</h1><p v-if="error" class="notice error" role="alert">{{error}}</p><p v-if="busy" role="status">處理中…</p><article v-if="customer" class="panel"><h2>{{customer.displayName}}</h2><p class="muted">顧客帳號 · {{customer.handle}}</p><p>查看本人購票紀錄與保票訂單。付款僅限免費模擬，不會真收款；已確認付款的訂單可領取展示電子票券。</p><p class="muted">登入有效期最多 30 分鐘；到期請重新登入。登出會員不影響員工後台。</p><div class="actions"><RouterLink to="/account/purchases" class="button-link">我的購票紀錄 ↗</RouterLink><RouterLink to="/account/orders" class="button-link primary">我的保票訂單 ↗</RouterLink><RouterLink to="/" class="back-link">探索活動 ↗</RouterLink><button :disabled="busy" @click="logout">登出會員</button></div></article><button v-if="error&&!customer" :disabled="busy" @click="load">重新載入</button></section></template>
