<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type SeatMap, type Session } from '../api'
import SeatMapView from './SeatMapView.vue'
const props = defineProps<{ session: Session; disabled: boolean }>()
const emit = defineEmits<{ save: [body: Record<string, unknown>] }>()
interface Row { ticketTypeId: string; name: string; include: boolean; label: string; x: number; y: number; w: number; h: number }
const map = ref<SeatMap | null>(null), stage = ref('舞台'), rows = ref<Row[]>([]), error = ref('')
async function load() {
  error.value = ''
  try {
    const m = await api<SeatMap>(`/admin/sessions/${props.session.id}/seat-map`); map.value = m; stage.value = m.stageLabel
    rows.value = props.session.tickets.map(t => {
      const z = m.zones.find(x => x.ticketTypeId === t.id)
      return { ticketTypeId: t.id, name: t.name, include: !!z, label: z?.label ?? t.name.slice(0, 40), x: z?.x ?? 0, y: z?.y ?? 0, w: z?.w ?? 20, h: z?.h ?? 20 }
    })
  } catch (e) { error.value = (e as Error).message }
}
/** 依票價由高到低、由舞台往後排成多列，作為起始版面。 */
function autoLayout() {
  const chosen = rows.value.filter(r => r.include).sort((a, b) => price(b) - price(a))
  // 票價越高越靠近舞台；3 區以內每排一區，之後每排 2–3 區。
  const perRow = chosen.length <= 3 ? 1 : chosen.length <= 6 ? 2 : 3, lines = Math.ceil(chosen.length / perRow) || 1, h = Math.min(30, Math.floor(90 / lines))
  chosen.forEach((r, i) => { const row = Math.floor(i / perRow), col = i % perRow, inRow = Math.min(perRow, chosen.length - row * perRow), w = Math.floor(96 / inRow); r.x = 2 + col * w; r.y = 5 + row * h; r.w = w - 2; r.h = h - 3 })
}
const price = (r: Row) => props.session.tickets.find(t => t.id === r.ticketTypeId)?.priceTwd ?? 0
const zones = computed(() => rows.value.filter(r => r.include).map(r => ({ ticketTypeId: r.ticketTypeId, label: r.label.trim(), x: r.x, y: r.y, w: r.w, h: r.h })))
const problem = computed(() => {
  const z = zones.value
  for (const a of z) {
    if (!a.label) return '每個區塊都需要名稱'
    if (![a.x, a.y, a.w, a.h].every(Number.isInteger) || a.x < 0 || a.y < 0 || a.w < 1 || a.h < 1 || a.x + a.w > 100 || a.y + a.h > 100) return `「${a.label}」超出 0–100 的範圍`
  }
  for (let i = 0; i < z.length; i++) for (let j = i + 1; j < z.length; j++) {
    const a = z[i], b = z[j]
    if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) return `「${a.label}」與「${b.label}」重疊`
  }
  return ''
})
const preview = computed<SeatMap>(() => ({ sessionId: props.session.id, stageLabel: stage.value || '舞台', zones: zones.value, version: map.value?.version ?? 0 }))
function submit() { if (!map.value || problem.value) return; emit('save', { expectedVersion: map.value.version, stageLabel: stage.value.trim(), zones: zones.value }) }
onMounted(load)
</script>
<template>
  <form class="seat-editor" @submit.prevent="submit">
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <fieldset :disabled="disabled || !map">
      <label>舞台名稱<input v-model="stage" required maxlength="40"></label>
      <div class="table-scroll">
        <table class="seat-editor-table">
          <thead><tr><th scope="col">顯示</th><th scope="col">票種</th><th scope="col">區塊名稱</th><th scope="col">X</th><th scope="col">Y</th><th scope="col">寬</th><th scope="col">高</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.ticketTypeId">
              <td><input v-model="r.include" type="checkbox" :aria-label="'在座位圖顯示 ' + r.name"></td>
              <td>{{ r.name }}</td>
              <td><input v-model="r.label" maxlength="40" :disabled="!r.include" :aria-label="r.name + ' 區塊名稱'"></td>
              <td v-for="k in (['x', 'y', 'w', 'h'] as const)" :key="k"><input v-model.number="r[k]" type="number" :min="k === 'w' || k === 'h' ? 1 : 0" max="100" step="1" :disabled="!r.include" :aria-label="r.name + ' ' + k"></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="muted">座標為 0–100 的百分比（左上為 0,0），區塊不可重疊。座位圖只決定版面，票額仍以票種為準。</p>
      <p v-if="problem" class="notice warning" role="status">{{ problem }}</p>
      <SeatMapView v-if="zones.length" :map="preview" :tickets="session.tickets" preview />
      <div class="button-row">
        <button type="button" @click="autoLayout">自動排列</button>
        <button class="primary" :disabled="!!problem">儲存座位圖</button>
        <span class="muted">版本 v{{ map?.version ?? 0 }}</span>
      </div>
    </fieldset>
  </form>
</template>
