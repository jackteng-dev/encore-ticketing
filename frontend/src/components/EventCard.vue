<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { money, type PublicEvent } from '../api'
import { posterArt } from '../poster'
const props = defineProps<{ event: PublicEvent }>()
const art = computed(() => posterArt(props.event.id, props.event.title))
const when = computed(() => {
  const e = props.event
  if (!e.firstStartsAt) return '場次公布中'
  const f = (s: string) => new Intl.DateTimeFormat('zh-TW', { timeZone: 'Asia/Taipei', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(s))
  const first = f(e.firstStartsAt), last = e.lastStartsAt ? f(e.lastStartsAt) : first
  return first === last ? first : `${first} – ${last.slice(5)}`
})
</script>
<template>
  <RouterLink :to="`/events/${event.id}`" class="event-card">
    <div class="poster" :class="art.tone">
      <img v-if="event.imageUrl" :src="event.imageUrl" alt="" class="poster-img" loading="lazy">
      <template v-else>
        <span class="poster-mark" aria-hidden="true">{{ art.mark }}</span>
        <span class="poster-tag">{{ event.categoryName || 'ENCORE / LIVE' }}</span>
        <span class="poster-word" aria-hidden="true">{{ art.word }}</span>
      </template>
      <span v-if="event.selling" class="badge hot card-badge">售票中</span>
    </div>
    <span class="card-date">{{ when }}<span v-if="event.categoryName" class="card-category">{{ event.categoryName }}</span></span>
    <h3>{{ event.title }}</h3>
    <span class="venue">{{ event.venue }}</span>
    <span class="card-foot">
      <span class="card-price">{{ event.minPriceTwd === null ? '票價公布中' : money(event.minPriceTwd) + (event.maxPriceTwd !== event.minPriceTwd ? ' 起' : '') }}</span>
      <span class="more">查看場次 →</span>
    </span>
  </RouterLink>
</template>
