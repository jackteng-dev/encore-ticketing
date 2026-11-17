<script setup lang="ts">
import { ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { refreshUnread, unread } from './engagement'
import MockNotice from './components/MockNotice.vue'
import { showMock } from './mockNotice'
const router=useRouter(),route=useRoute(),keyword=ref('')
// 換頁時更新未讀數（未登入時 summary 回 401，鈴鐺會隱藏）。
watch(()=>route.path,p=>{if(!p.startsWith('/staff'))void refreshUnread()},{immediate:true})
function search(){const q=keyword.value.trim().slice(0,60);keyword.value='';void router.push({path:'/search',query:q?{q}:{}})}
</script>
<template>
 <header class="site-header">
  <div class="site-header-inner">
   <RouterLink to="/" class="brand">ENCORE</RouterLink>
   <nav class="site-nav" aria-label="主要導覽">
    <RouterLink to="/">探索活動</RouterLink>
    <RouterLink to="/help/guide" class="nav-extra">購票須知</RouterLink>
    <span class="spacer" aria-hidden="true"></span>
    <form class="header-search" role="search" @submit.prevent="search"><input v-model="keyword" type="search" maxlength="60" placeholder="搜尋活動、場館" aria-label="搜尋活動"><button aria-label="送出搜尋">⌕</button></form>
    <RouterLink to="/account/orders">我的票券</RouterLink>
    <RouterLink v-if="unread!==null" to="/account/notifications" class="nav-bell" :aria-label="unread?`通知中心，${unread} 則未讀`:'通知中心'">🔔<span v-if="unread" class="bell-count" aria-hidden="true">{{unread>99?'99+':unread}}</span></RouterLink>
    <RouterLink to="/staff">員工後台 ↗</RouterLink>
    <RouterLink to="/account" class="nav-cta">會員</RouterLink>
   </nav>
  </div>
 </header>
 <main><RouterView :key="$route.fullPath" /></main>
 <footer class="site-footer">
  <div class="site-footer-inner">
   <div class="footer-brand">
    <span class="brand">ENCORE</span>
    <p>排隊、保票、模擬付款與 QR 驗票的售票展示。</p>
    <p class="footer-demo">展示作品：付款為模擬，不實際收款</p>
   </div>
   <nav class="footer-col" aria-label="購票服務">
    <h2>購票服務</h2>
    <RouterLink to="/help/faq">常見問題</RouterLink>
    <RouterLink to="/help/guide">購票須知</RouterLink>
    <RouterLink to="/help/refund">退換票規定</RouterLink>
    <RouterLink to="/help/contact">客服中心</RouterLink>
   </nav>
   <nav class="footer-col" aria-label="關於 Encore">
    <h2>關於 Encore</h2>
    <RouterLink to="/performers">演出者</RouterLink>
    <RouterLink to="/venues">場館</RouterLink>
    <RouterLink to="/help/terms">服務條款</RouterLink>
    <RouterLink to="/help/privacy">隱私權政策</RouterLink>
    <button type="button" class="footer-link" @click="showMock('主辦單位合作申請')">主辦單位合作</button>
   </nav>
   <div class="footer-col">
    <h2>下載 App</h2>
    <div class="store-badges">
     <button type="button" class="store-badge" @click="showMock('App Store 下載')"><small>Download on the</small>App Store</button>
     <button type="button" class="store-badge" @click="showMock('Google Play 下載')"><small>GET IT ON</small>Google Play</button>
    </div>
    <h2>追蹤我們</h2>
    <div class="social-row">
     <button type="button" class="social-dot" aria-label="Facebook" @click="showMock('Facebook 粉絲專頁')">f</button>
     <button type="button" class="social-dot" aria-label="Instagram" @click="showMock('Instagram')">IG</button>
     <button type="button" class="social-dot" aria-label="LINE" @click="showMock('LINE 官方帳號')">L</button>
     <button type="button" class="social-dot" aria-label="YouTube" @click="showMock('YouTube 頻道')">▶</button>
    </div>
   </div>
  </div>
  <div class="footer-bottom">© 2026 Encore 展示作品 · 非真實售票服務</div>
 </footer>
 <MockNotice />
</template>
