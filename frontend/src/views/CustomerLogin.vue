<script setup lang="ts">
import { ref } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { customerWrite } from '../api'
import { showMock } from '../mockNotice'
const socials=[{id:'google',name:'Google',mark:'G'},{id:'line',name:'LINE',mark:'L'},{id:'facebook',name:'Facebook',mark:'f'},{id:'apple',name:'Apple',mark:''}]
const router=useRouter(),route=useRoute(),register=ref(false),handle=ref(''),displayName=ref(''),password=ref(''),busy=ref(false),error=ref('')
async function submit(){
 busy.value=true;error.value=''
 const registration=register.value
 try {
  await customerWrite('/customer/auth/'+(registration?'register':'login'),{handle:handle.value,password:password.value,...(registration?{displayName:displayName.value}:{})})
  password.value='';const target=route.query.returnTo;await router.push(typeof target==='string'&&(target==='/account/orders'||target==='/account/purchases'||target==='/account/favorites'||target==='/account/profile'||target==='/account/notifications'||/^\/events\/[0-9a-f-]{36}$/.test(target)||/^\/events\/[0-9a-f-]{36}\/reserve\/[0-9a-f-]{36}$/.test(target))?target:'/account')
 } catch(e){
  password.value='';error.value=(e as Error).message
  if(registration)error.value+='。若剛才回覆中斷，請用相同帳號登入確認；不要重複註冊。'
 } finally {busy.value=false}
}
function changeMode(){register.value=!register.value;password.value='';error.value=''}
</script>
<template><section class="login-page"><div class="eyebrow">YOUR ENCORE ACCOUNT</div><h1>{{register?'為下一次相聚準備。':'歡迎回到現場。'}}</h1><p class="muted">顧客帳號與員工帳號分開。可管理展示保票訂單；付款僅限開啟後的免費模擬，不會真收款。</p><form class="panel" @submit.prevent="submit"><fieldset :disabled="busy"><label>顧客帳號<input v-model="handle" autocomplete="username" required minlength="3" maxlength="40" pattern="[a-z0-9][a-z0-9_\-]{2,39}" aria-describedby="handle-help"></label><p id="handle-help" class="muted">3–40 個小寫英文字母、數字、底線或連字號。</p><label v-if="register">顯示名稱<input v-model="displayName" autocomplete="nickname" required maxlength="80"></label><label>密碼<input v-model="password" type="password" :autocomplete="register?'new-password':'current-password'" required minlength="12" maxlength="72" aria-describedby="password-help"></label><p id="password-help" class="muted">12–72 字元，UTF-8 最多 72 bytes。展示請使用獨立密碼。</p><p v-if="error" class="notice error" role="alert">{{error}}</p><button class="primary" :disabled="busy">{{busy?'處理中…':register?'建立顧客帳號 →':'登入會員 →'}}</button></fieldset></form><div class="social-login" aria-label="其他登入方式"><p class="social-divider"><span>或使用以下帳號{{register?'註冊':'登入'}}</span></p><div class="social-buttons"><button v-for="x in socials" :key="x.id" type="button" class="social-button" :class="'brand-'+x.id" :disabled="busy" @click="showMock(x.name+' 帳號登入')"><span class="social-mark" aria-hidden="true">{{x.mark}}</span>{{x.name}}</button></div><p class="muted social-note">社群登入為模擬版本，展示請使用上方的 Encore 帳號。</p></div><button :disabled="busy" @click="changeMode">{{register?'已有帳號，改用登入':'還沒有帳號？建立帳號'}}</button></section></template>
