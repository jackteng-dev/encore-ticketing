<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { allPages, api, ApiError, type Performer, type Staff, type Venue } from '../api'
import { busy, command, commandMessage, pending, retry } from '../commands'

type Kind = 'venues' | 'performers'
const router = useRouter(), staff = ref<Staff | null>(null), error = ref('')
const venues = ref<Venue[]>([]), performers = ref<Performer[]>([])
const kind = ref<Kind>('venues'), editing = ref<Venue | Performer | null>(null)
const form = ref({ slug: '', name: '', city: '', address: '', description: '', imageUrl: '' })
const editable = computed(() => !!staff.value?.canWrite && !pending.value && !busy.value)
const items = computed(() => (kind.value === 'venues' ? venues.value : performers.value) as (Venue | Performer)[])

async function load() {
  try {
    const [v, p] = await Promise.all([allPages<Venue>('/admin/venues'), allPages<Performer>('/admin/performers')])
    venues.value = v; performers.value = p
  } catch (e) { error.value = (e as Error).message; if (e instanceof ApiError && e.status === 401) await router.push('/staff/login') }
}
function reset(item: Venue | Performer | null = null) {
  editing.value = item
  const v = item as Partial<Venue> | null
  form.value = { slug: item?.slug || '', name: item?.name || '', city: v?.city || '', address: v?.address || '', description: item?.description || '', imageUrl: item?.imageUrl || '' }
}
function switchKind(k: Kind) { kind.value = k; reset() }
async function save() {
  if (!staff.value) return
  error.value = ''
  const f = form.value, base = { expectedVersion: editing.value?.version ?? 0, slug: f.slug.trim(), name: f.name, description: f.description, imageUrl: f.imageUrl.trim() || null }
  const body = kind.value === 'venues' ? { ...base, city: f.city, address: f.address } : base
  const path = `/admin/${kind.value}` + (editing.value ? `/${editing.value.id}` : '')
  try { await command(staff.value, path, editing.value ? 'PUT' : 'POST', body); reset(); await load() } catch (e) {
    error.value = (e as Error).message
    // 版本衝突：先重新讀取，再以最新資料重填表單，避免沿用舊版本一直失敗。
    if (e instanceof ApiError && e.status === 409 && editing.value) {
      const id = editing.value.id; await load()
      const fresh = items.value.find(x => x.id === id) || null
      reset(fresh); error.value += '。已重新讀取最新資料，請確認後再儲存。'
    }
  }
}
async function retryOriginal() { if (!staff.value) return; try { await retry(staff.value); reset(); await load() } catch (e) { error.value = (e as Error).message } }
onMounted(async () => { try { staff.value = await api<Staff>('/auth/me'); await load() } catch { await router.push('/staff/login') } })
</script>
<template>
  <section v-if="staff" class="content admin-page">
    <RouterLink to="/staff" class="back-link">← 活動管理</RouterLink>
    <div class="eyebrow">STAFF DIRECTORY</div>
    <h1>場館與演出者</h1>
    <p class="muted">建立後可在活動管理的「活動檔案」連結到活動。圖片網址可先留空。</p>
    <div v-if="pending" class="notice warning" role="status"><strong>有一筆操作尚未確認。</strong><button :disabled="busy || staff.username !== pending.owner" @click="retryOriginal">重試原操作</button></div>
    <p v-if="commandMessage && !pending" class="notice" role="status">{{ commandMessage }}</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <div class="filter-chips" role="tablist">
      <button type="button" role="tab" class="chip" :class="{ on: kind === 'venues' }" :aria-selected="kind === 'venues'" @click="switchKind('venues')">場館 {{ venues.length }}</button>
      <button type="button" role="tab" class="chip" :class="{ on: kind === 'performers' }" :aria-selected="kind === 'performers'" @click="switchKind('performers')">演出者 {{ performers.length }}</button>
    </div>
    <div class="admin-grid">
      <aside class="panel event-sidebar">
        <div class="section-heading"><h2>{{ kind === 'venues' ? '場館' : '演出者' }}</h2><button v-if="staff.canWrite" :disabled="!editable" @click="reset()">＋ 新增</button></div>
        <button v-for="x in items" :key="x.id" class="event-select" :class="{ selected: editing?.id === x.id }" @click="reset(x)"><strong>{{ x.name }}</strong><span>{{ x.slug }} · v{{ x.version }}</span></button>
        <p v-if="!items.length" class="muted">尚無資料。</p>
      </aside>
      <form class="panel" @submit.prevent="save">
        <h2>{{ editing ? '編輯' : '新增' }}{{ kind === 'venues' ? '場館' : '演出者' }}</h2>
        <fieldset :disabled="!editable">
          <div class="form-grid">
            <label>名稱<input v-model="form.name" required :maxlength="kind === 'venues' ? 160 : 120"></label>
            <label>網址代碼<input v-model="form.slug" required maxlength="80" pattern="[a-z0-9]+(-[a-z0-9]+)*" placeholder="例如 taipei-arena"></label>
            <template v-if="kind === 'venues'">
              <label>城市<input v-model="form.city" maxlength="40"></label>
              <label>地址<input v-model="form.address" maxlength="200"></label>
            </template>
            <label class="full">介紹<textarea v-model="form.description" maxlength="3000" rows="4"></textarea></label>
            <label class="full">圖片網址（選填）<input v-model="form.imageUrl" maxlength="500" pattern="(https://|/)[^\s&quot;<>]+" placeholder="留空即可"></label>
          </div>
          <button class="primary">{{ editing ? '儲存變更' : '建立' }}</button>
          <RouterLink v-if="editing" :to="`/${kind}/${editing.slug}`" class="back-link"> 查看前台頁面 ↗</RouterLink>
        </fieldset>
      </form>
    </div>
  </section>
</template>
