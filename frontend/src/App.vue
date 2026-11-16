<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>
<template>
 <header class="site-header">
  <div class="site-header-inner">
   <RouterLink to="/" class="brand">ENCORE</RouterLink>
   <nav class="site-nav" aria-label="主要導覽">
    <RouterLink to="/">探索活動</RouterLink>
    <span class="spacer" aria-hidden="true"></span>
    <RouterLink to="/account/orders">我的票券</RouterLink>
    <RouterLink to="/staff">員工後台 ↗</RouterLink>
    <RouterLink to="/account" class="nav-cta">會員</RouterLink>
   </nav>
  </div>
 </header>
 <main><RouterView :key="$route.fullPath" /></main>
 <footer class="site-footer">
  <div class="site-footer-inner">
   <span class="brand">ENCORE</span>
   <span>排隊、保票、模擬付款與 QR 驗票的售票展示</span>
   <span>展示作品：付款為模擬，不實際收款</span>
  </div>
 </footer>
</template>
