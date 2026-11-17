<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { money, type SeatMap, type Ticket } from '../api'
const props = defineProps<{ map: SeatMap; tickets: Ticket[]; reserveBase?: string; canReserve?: boolean; preview?: boolean }>()
const byId = computed(() => new Map(props.tickets.map(t => [t.id, t])))
const zones = computed(() => props.map.zones.map(z => {
  const t = byId.value.get(z.ticketTypeId)
  const available = t?.available ?? 0, capacity = t?.capacity ?? 0
  const state = !t || available === 0 ? 'out' : available < 20 || available / Math.max(capacity, 1) < 0.1 ? 'low' : 'open'
  return { ...z, t, available, state, style: { left: z.x + '%', top: z.y + '%', width: z.w + '%', height: z.h + '%' } }
}))
const link = (id: string) => (props.reserveBase ? `${props.reserveBase}/${id}` : '')
</script>
<template>
  <div class="seat-map" :class="{ preview }">
    <div class="seat-stage">{{ map.stageLabel }}</div>
    <div class="seat-canvas" role="group" :aria-label="map.stageLabel + ' 區域座位圖'">
      <template v-for="z in zones" :key="z.ticketTypeId">
        <RouterLink v-if="!preview && canReserve && z.state !== 'out' && reserveBase" :to="link(z.ticketTypeId)" class="seat-zone" :class="z.state" :style="z.style"
          :aria-label="`${z.label}，${z.t?.name}，${z.t ? money(z.t.priceTwd) : ''}，剩餘 ${z.available} 張，選擇張數`">
          <strong>{{ z.label }}</strong><span>{{ z.t ? money(z.t.priceTwd) : '' }}</span><small>{{ z.state === 'low' ? '剩餘 ' + z.available : '可購買' }}</small>
        </RouterLink>
        <div v-else class="seat-zone" :class="z.state" :style="z.style" :aria-label="`${z.label}，${z.state === 'out' ? '已售完' : '剩餘 ' + z.available + ' 張'}`">
          <strong>{{ z.label }}</strong><span>{{ z.t ? money(z.t.priceTwd) : '票種已移除' }}</span><small>{{ z.state === 'out' ? '已售完' : '剩餘 ' + z.available }}</small>
        </div>
      </template>
    </div>
    <div v-if="!preview" class="seat-legend" aria-hidden="true"><span><i class="open"></i>可購買</span><span><i class="low"></i>即將售完</span><span><i class="out"></i>已售完</span><span class="muted">點選區域選擇張數；本展示依區域入場、不劃位</span></div>
  </div>
</template>
