<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { findInfoPage, infoPages } from '../info'
import { showMock } from '../mockNotice'
const route = useRoute()
const page = computed(() => findInfoPage(route.params.slug))
</script>
<template>
  <section class="content info-page">
    <nav class="info-nav" aria-label="服務說明">
      <RouterLink v-for="p in infoPages" :key="p.slug" :to="'/help/' + p.slug">{{ p.title }}</RouterLink>
    </nav>
    <article v-if="page" class="info-article">
      <div class="eyebrow">{{ page.eyebrow }}</div>
      <h1>{{ page.title }}</h1>
      <p class="muted">{{ page.lead }}</p>
      <section v-for="s in page.sections" :key="s.heading" class="info-card">
        <h2>{{ s.heading }}</h2>
        <p v-for="b in s.body" :key="b">{{ b }}</p>
      </section>
      <div v-if="page.slug === 'contact'" class="contact-grid">
        <button type="button" @click="showMock('線上文字客服')">💬 線上文字客服</button>
        <button type="button" @click="showMock('LINE 官方帳號客服')">LINE 官方帳號</button>
        <button type="button" @click="showMock('Email 客服信箱')">✉ 客服信箱</button>
        <button type="button" @click="showMock('客服專線')">☎ 客服專線</button>
      </div>
    </article>
    <div v-else class="empty"><h1>找不到這個頁面。</h1><RouterLink to="/help/faq">看看常見問題 →</RouterLink></div>
  </section>
</template>
