<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, type Page, type Performer, type PublicEvent, type Venue } from '../api'
import EventCard from '../components/EventCard.vue'
import { posterArt } from '../poster'
import { setTitle } from '../title'
const route = useRoute()
const kind = computed(() => (route.path.startsWith('/venues/') ? 'venue' : 'performer'))
const slug = computed(() => String(route.params.slug))
const item = ref<Venue | Performer | null>(null), events = ref<Page<PublicEvent> | null>(null), error = ref(''), page = ref(1)
const venue = computed(() => (kind.value === 'venue' ? (item.value as Venue | null) : null))
const art = computed(() => (item.value ? posterArt(item.value.id, item.value.name) : null))
async function loadEvents() {
  events.value = await api<Page<PublicEvent>>(`/public/events?${kind.value}=${encodeURIComponent(slug.value)}&sort=date&page=${page.value}&size=12`)
}
async function load() {
  error.value = ''
  try {
    item.value = await api(`/public/${kind.value === 'venue' ? 'venues' : 'performers'}/${encodeURIComponent(slug.value)}`)
    setTitle(item.value!.name); await loadEvents()
  } catch (e) { error.value = (e as Error).message }
}
async function move(n: number) { page.value += n; try { await loadEvents() } catch (e) { error.value = (e as Error).message } }
onMounted(load)
</script>
<template>
  <p v-if="error" class="content notice error" role="alert">{{ error }} <RouterLink :to="kind === 'venue' ? '/venues' : '/performers'">回到列表</RouterLink></p>
  <template v-if="item && art">
    <section class="event-hero">
      <div class="event-hero-inner">
        <RouterLink :to="kind === 'venue' ? '/venues' : '/performers'" class="back-link">← {{ kind === 'venue' ? '所有場館' : '所有演出者' }}</RouterLink>
        <div class="poster" :class="art.tone" aria-hidden="true">
          <img v-if="item.imageUrl" :src="item.imageUrl" alt="" class="poster-img" loading="lazy">
          <template v-else><span class="poster-mark">{{ art.mark }}</span><span class="poster-tag">{{ kind === 'venue' ? 'VENUE' : 'ARTIST' }}</span><span class="poster-word">{{ art.word }}</span></template>
        </div>
        <div class="event-hero-copy">
          <div class="eyebrow">{{ kind === 'venue' ? 'VENUE' : 'ARTIST' }}</div>
          <h1>{{ item.name }}</h1>
          <dl v-if="venue" class="fact-grid">
            <div v-if="venue.city"><dt>CITY</dt><dd>{{ venue.city }}</dd></div>
            <div v-if="venue.address"><dt>ADDRESS</dt><dd>{{ venue.address }}</dd></div>
          </dl>
          <p v-if="item.description" class="description">{{ item.description }}</p>
        </div>
      </div>
    </section>
    <section class="content">
      <div class="block-heading"><h2>UPCOMING</h2><span class="sub">{{ kind === 'venue' ? '在這裡的演出' : '相關演出' }}</span><span class="spacer"></span><span class="muted">{{ events?.total ?? '—' }} 場活動</span></div>
      <div v-if="events && !events.items.length" class="empty"><h3>目前沒有已發布的活動</h3><p>新的場次公布後會出現在這裡。</p></div>
      <div v-else-if="events" class="event-grid"><EventCard v-for="e in events.items" :key="e.id" :event="e" /></div>
      <div v-if="events && events.total > events.size" class="pagination"><button :disabled="page === 1" @click="move(-1)">上一頁</button><span>{{ page }}</span><button :disabled="page * events.size >= events.total" @click="move(1)">下一頁</button></div>
    </section>
  </template>
</template>
