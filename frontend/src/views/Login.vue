<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
const username=ref(''),password=ref(''),error=ref(''),busy=ref(false),router=useRouter()
async function login(){busy.value=true;error.value='';try{const csrf=await api<{headerName:string;token:string}>('/auth/csrf');await api('/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',[csrf.headerName]:csrf.token},body:new URLSearchParams({username:username.value,password:password.value}).toString()});password.value='';await router.push('/staff')}catch(e){password.value='';error.value=(e as Error).message}finally{busy.value=false}}
</script>
<template><section class="login-page"><div class="eyebrow">STAFF WORKSPACE</div><h1>管理每一次期待。</h1><p class="muted">使用你的員工帳號登入活動後台。</p><form class="panel" @submit.prevent="login"><label>員工帳號<input v-model="username" autocomplete="username" required maxlength="80"></label><label>密碼<input v-model="password" type="password" autocomplete="current-password" required maxlength="72"></label><p v-if="error" class="notice error" role="alert">{{error}}</p><button class="primary" :disabled="busy">{{busy?'登入中…':'登入後台 →'}}</button></form></section></template>
