<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, type Page, type Performer, type Venue } from '../api'
import { posterArt } from '../poster'
import { setTitle } from '../title'
const route = useRoute()
const isVenue = computed(() => route.path.startsWith('/venues'))
const data = ref<Page<Venue | Performer> | null>(null), error = ref(''), page = ref(1)
async function load() {
  error.value = ''
  try { data.value = await api(`/public/${isVenue.value ? 'venues' : 'performers'}?page=${page.value}&size=24`) } catch (e) { error.value = (e as Error).message }
}
async function move(n: number) { page.value += n; await load() }
onMounted(() => { setTitle(isVenue.value ? '場館' : '演出者'); void load() })
</script>
<template>
  <section class="content">
    <div class="eyebrow">{{ isVenue ? 'VENUES' : 'ARTISTS' }}</div>
    <h1>{{ isVenue ? '場館' : '演出者' }}</h1>
    <p v-if="error" class="notice error" role="alert">{{ error }} <button @click="load">重新讀取</button></p>
    <div v-else-if="data && !data.items.length" class="empty"><h2>目前還沒有{{ isVenue ? '場館' : '演出者' }}資料</h2></div>
    <div v-else-if="data" class="directory-grid">
      <RouterLink v-for="x in data.items" :key="x.id" :to="`/${isVenue ? 'venues' : 'performers'}/${x.slug}`" class="directory-card">
        <span class="poster thumb" :class="posterArt(x.id, x.name).tone" aria-hidden="true">
          <img v-if="x.imageUrl" :src="x.imageUrl" alt="" class="poster-img" loading="lazy"><template v-else>{{ posterArt(x.id, x.name).mark }}</template>
        </span>
        <span><strong>{{ x.name }}</strong><span v-if="isVenue && (x as Venue).city" class="muted">{{ (x as Venue).city }}</span></span>
      </RouterLink>
    </div>
    <div v-if="data && data.total > data.size" class="pagination"><button :disabled="page === 1" @click="move(-1)">上一頁</button><span>{{ page }}</span><button :disabled="page * data.size >= data.total" @click="move(1)">下一頁</button></div>
  </section>
</template>
