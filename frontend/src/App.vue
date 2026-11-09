<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>
<template>
 <header class="site-header"><RouterLink to="/" class="brand">encore<span>LIVE / TOGETHER</span></RouterLink><nav aria-label="主要導覽"><RouterLink to="/">探索活動</RouterLink><RouterLink to="/account">會員</RouterLink><RouterLink to="/staff">員工後台 ↗</RouterLink></nav></header>
 <main><RouterView :key="$route.fullPath" /></main>
 <footer><span>ENCORE — MAKE ROOM FOR THE MOMENT.</span><span>活動與保票展示 · 模擬付款・不收款</span></footer>
</template>
