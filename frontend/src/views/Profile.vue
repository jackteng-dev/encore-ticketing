<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api, ApiError, customerWrite, date, type Customer } from '../api'
import { currentCustomer } from '../engagement'
import { showMock } from '../mockNotice'

interface Profile { realName: string | null; email: string | null; emailVerifiedAt: string | null; phone: string | null; phoneVerifiedAt: string | null; version: number }
interface Sent { channel: string; maskedTarget: string; expiresAt: string; resendAfterSeconds: number; demoCode: string }
type Channel = 'EMAIL' | 'PHONE'

const router = useRouter(), customer = ref<Customer | null>(null), profile = ref<Profile | null>(null), error = ref(''), notice = ref(''), busy = ref(false)
const form = ref({ realName: '', email: '', phone: '' })
const sent = ref<Record<Channel, Sent | null>>({ EMAIL: null, PHONE: null }), codes = ref<Record<Channel, string>>({ EMAIL: '', PHONE: '' })
const clock = ref(Date.now()), timer = setInterval(() => (clock.value = Date.now()), 1000)
onUnmounted(() => clearInterval(timer))
const dirty = computed(() => !!base.value && (form.value.realName.trim() !== (base.value.realName || '') || form.value.email.trim().toLowerCase() !== (base.value.email || '') || form.value.phone.trim() !== (base.value.phone || '')))
const phoneOk = computed(() => !form.value.phone.trim() || /^09\d{8}$/.test(form.value.phone.trim()))
const resendIn = ref<Record<Channel, number>>({ EMAIL: 0, PHONE: 0 })
const headers = () => ({ headers: { 'X-Expected-Customer-ID': customer.value!.id } })

// base：表單編輯起點的版本與值；儲存時以 base.version 做版本檢查，避免覆蓋其他分頁的變更。
const base = ref<Profile | null>(null)
function fill(p: Profile) { profile.value = p; base.value = p; form.value = { realName: p.realName || '', email: p.email || '', phone: p.phone || '' } }
const sameValues = (a: Profile, b: Profile) => a.realName === b.realName && a.email === b.email && a.phone === b.phone
async function load() {
  error.value = ''
  try {
    customer.value = await currentCustomer()
    if (!customer.value) { await router.replace({ path: '/account/login', query: { returnTo: '/account/profile' } }); return }
    fill(await api<Profile>('/customer/profile', headers()))
  } catch (e) { error.value = (e as Error).message; if (e instanceof ApiError && e.status === 401) await router.replace({ path: '/account/login', query: { returnTo: '/account/profile' } }) }
}
async function save() {
  if (!customer.value || !base.value || busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const before = base.value
    fill(await customerWrite<Profile>('/customer/profile', { expectedVersion: before.version, realName: form.value.realName.trim() || null, email: form.value.email.trim() || null, phone: form.value.phone.trim() || null }, customer.value.id))
    // 只有聯絡方式變更的管道需要重新寄送；未變更者保留尚在有效期的驗證碼
    if (profile.value!.email !== before.email) sent.value.EMAIL = null
    if (profile.value!.phone !== before.phone) sent.value.PHONE = null
    notice.value = '已儲存。變更過的 Email／手機需要重新驗證。'
  } catch (e) {
    const message = (e as Error).message
    // 版本衝突：重新讀取最新資料後再顯示訊息（load 會清除錯誤）
    if (e instanceof ApiError && e.status === 409) { await load(); error.value = message + '。已載入最新資料，請確認後再儲存。' } else error.value = message
  } finally { busy.value = false }
}
async function send(c: Channel) {
  if (!customer.value || busy.value) return
  busy.value = true; error.value = ''
  try {
    const s = await customerWrite<Sent>('/customer/profile/verifications', { channel: c }, customer.value.id)
    sent.value[c] = s; codes.value[c] = ''; resendIn.value[c] = Date.now() + s.resendAfterSeconds * 1000
  } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function confirm(c: Channel) {
  if (!customer.value || busy.value) return
  busy.value = true; error.value = ''
  try {
    const p = await customerWrite<Profile>('/customer/profile/verifications/confirm', { channel: c, code: codes.value[c] }, customer.value.id)
    // 沒有未儲存編輯時直接採用；有編輯時只更新顯示，且只有伺服器資料仍等於編輯起點時才前進版本（否則儲存時會偵測到衝突）
    if (!dirty.value) fill(p); else { profile.value = p; if (base.value && sameValues(base.value, p)) base.value = p }
    sent.value[c] = null; notice.value = c === 'EMAIL' ? 'Email 驗證完成。' : '手機驗證完成。' }
  catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
const resendLeft = (c: Channel) => Math.max(0, Math.ceil((resendIn.value[c] - clock.value) / 1000))
onMounted(load)
</script>
<template>
  <section class="content profile-page">
    <RouterLink to="/account" class="back-link">← 會員空間</RouterLink>
    <div class="eyebrow">MY PROFILE</div>
    <h1>會員資料與驗證</h1>
    <p class="muted">實名與已驗證的聯絡方式可協助防止黃牛與找回帳號。本站不蒐集身分證字號。</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <form v-if="profile" class="panel" @submit.prevent="save">
      <h2>基本資料</h2>
      <fieldset :disabled="busy">
        <div class="form-grid">
          <label class="full">真實姓名<input v-model="form.realName" maxlength="80" autocomplete="name" placeholder="與證件相同"></label>
          <label>Email<input v-model="form.email" type="email" maxlength="254" autocomplete="email"></label>
          <label>手機號碼<input v-model="form.phone" inputmode="numeric" maxlength="10" pattern="09[0-9]{8}" autocomplete="tel" placeholder="09xxxxxxxx" :aria-invalid="!phoneOk"></label>
        </div>
        <button class="primary" :disabled="!dirty || !phoneOk">儲存資料</button>
      </fieldset>
    </form>
    <section v-for="c in (['EMAIL', 'PHONE'] as const)" v-show="profile" :key="c" class="panel verify-card">
      <div class="section-heading">
        <h2>{{ c === 'EMAIL' ? 'Email 驗證' : '手機驗證' }}</h2>
        <span v-if="c === 'EMAIL' ? profile?.emailVerifiedAt : profile?.phoneVerifiedAt" class="badge hot">已驗證</span>
        <span v-else-if="c === 'EMAIL' ? profile?.email : profile?.phone" class="badge few">未驗證</span>
      </div>
      <p v-if="!(c === 'EMAIL' ? profile?.email : profile?.phone)" class="muted">請先在上方填寫並儲存{{ c === 'EMAIL' ? ' Email' : '手機號碼' }}。</p>
      <p v-else-if="c === 'EMAIL' ? profile?.emailVerifiedAt : profile?.phoneVerifiedAt" class="muted">{{ c === 'EMAIL' ? profile?.email : profile?.phone }} · 驗證於 {{ date((c === 'EMAIL' ? profile?.emailVerifiedAt : profile?.phoneVerifiedAt)!) }}</p>
      <template v-else>
        <p class="muted">{{ c === 'EMAIL' ? profile?.email : profile?.phone }}{{ dirty ? '（請先儲存變更）' : '' }}</p>
        <button type="button" :disabled="busy || dirty || resendLeft(c) > 0" @click="send(c)">{{ sent[c] ? (resendLeft(c) > 0 ? `重新寄送（${resendLeft(c)} 秒）` : '重新寄送驗證碼') : '寄送驗證碼' }}</button>
        <div v-if="sent[c]" class="otp-box">
          <p class="notice warning" role="status"><strong>此為模擬版本：</strong>尚未串接{{ c === 'EMAIL' ? ' Email' : '簡訊' }}服務，驗證碼直接顯示於此。寄往 {{ sent[c]!.maskedTarget }}，驗證碼 <code class="otp-code">{{ sent[c]!.demoCode }}</code>，{{ date(sent[c]!.expiresAt) }} 前有效。</p>
          <form class="otp-form" @submit.prevent="confirm(c)">
            <label>輸入 6 位數驗證碼<input v-model="codes[c]" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" required></label>
            <button class="primary" :disabled="busy || !/^\d{6}$/.test(codes[c])">確認驗證</button>
          </form>
        </div>
      </template>
    </section>
    <section v-if="profile" class="info-card">
      <div class="eyebrow">MORE</div>
      <h2>其他驗證與綁定</h2>
      <p class="muted">以下為模擬版本，正式上線時才會串接。</p>
      <div class="filter-chips">
        <button type="button" class="chip" @click="showMock('身分證實名認證')">身分證實名認證</button>
        <button type="button" class="chip" @click="showMock('綁定 LINE 帳號')">綁定 LINE</button>
        <button type="button" class="chip" @click="showMock('綁定 Google 帳號')">綁定 Google</button>
        <button type="button" class="chip" @click="showMock('兩步驟驗證')">兩步驟驗證</button>
      </div>
    </section>
  </section>
</template>
