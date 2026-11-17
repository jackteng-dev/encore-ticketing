<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { allPages, api, type Category, type EventProfile, type Performer, type Venue } from '../api'
const props = defineProps<{ eventId: string; disabled: boolean }>()
const emit = defineEmits<{ save: [body: Record<string, unknown>] }>()
const profile = ref<EventProfile | null>(null), categories = ref<Category[]>([]), venues = ref<Venue[]>([]), performers = ref<Performer[]>([]), error = ref('')
const categoryId = ref(''), venueId = ref(''), chosen = ref<string[]>([]), imageUrl = ref('')
async function load() {
  error.value = ''
  try {
    const [p, c, v, f] = await Promise.all([
      api<EventProfile>(`/admin/events/${props.eventId}/profile`), api<Category[]>('/admin/categories'),
      allPages<Venue>('/admin/venues'), allPages<Performer>('/admin/performers'),
    ])
    profile.value = p; categories.value = c; venues.value = v; performers.value = f
    categoryId.value = p.category?.id || ''; venueId.value = p.venue?.id || ''
    chosen.value = p.performers.map(x => x.id); imageUrl.value = p.imageUrl || ''
  } catch (e) { error.value = (e as Error).message }
}
function toggle(id: string) { chosen.value = chosen.value.includes(id) ? chosen.value.filter(x => x !== id) : [...chosen.value, id] }
const imageInvalid = computed(() => !!imageUrl.value.trim() && !/^(https:\/\/|\/)[^\s"<>]+$/.test(imageUrl.value.trim()))
function submit() {
  if (!profile.value || imageInvalid.value) return
  emit('save', { expectedVersion: profile.value.version, categoryId: categoryId.value || null, venueId: venueId.value || null, performerIds: chosen.value, imageUrl: imageUrl.value.trim() || null })
}
onMounted(load)
watch(() => props.eventId, load)
defineExpose({ load })
</script>
<template>
  <form class="profile-form" @submit.prevent="submit">
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <fieldset :disabled="disabled || !profile">
      <div class="form-grid">
        <label>活動分類<select v-model="categoryId"><option value="">未分類</option><option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label>場館頁面<select v-model="venueId"><option value="">不連結場館</option><option v-for="v in venues" :key="v.id" :value="v.id">{{ v.name }}{{ v.city ? '（' + v.city + '）' : '' }}</option></select></label>
        <label class="full">主視覺圖片網址<input v-model="imageUrl" maxlength="500" placeholder="留空即可，之後再補圖片（https:// 或 / 開頭）" aria-describedby="image-help"></label>
      </div>
      <p id="image-help" class="muted">{{ imageInvalid ? '圖片網址需以 https:// 或 / 開頭，且不可含空白或引號。' : '未設定圖片時，前台使用文字海報。' }}</p>
      <div class="performer-picks" role="group" aria-label="演出者">
        <span class="label">演出者（依點選順序排列）</span>
        <p v-if="!performers.length" class="muted">尚未建立演出者。<RouterLink to="/staff/directory">到場館與演出者管理新增 ↗</RouterLink></p>
        <button v-for="p in performers" :key="p.id" type="button" class="chip" :class="{ on: chosen.includes(p.id) }" :aria-pressed="chosen.includes(p.id)" @click="toggle(p.id)">
          <b v-if="chosen.includes(p.id)">{{ chosen.indexOf(p.id) + 1 }}</b>{{ p.name }}
        </button>
      </div>
      <button class="primary" :disabled="imageInvalid">儲存活動檔案</button>
      <span class="muted"> · 版本 v{{ profile?.version ?? 0 }}，發布後仍可調整，不影響票額與售票條件。</span>
    </fieldset>
  </form>
</template>
