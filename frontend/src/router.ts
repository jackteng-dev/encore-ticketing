import { createRouter, createWebHistory } from 'vue-router'
import Events from './views/Events.vue'
import EventDetail from './views/EventDetail.vue'
import Admin from './views/Admin.vue'
import Login from './views/Login.vue'
import CustomerLogin from './views/CustomerLogin.vue'
import CustomerAccount from './views/CustomerAccount.vue'
import Reserve from './views/Reserve.vue'
import Orders from './views/Orders.vue'
import Purchases from './views/Purchases.vue'
import StockMaintenance from './views/StockMaintenance.vue'
import CustomerTickets from './views/CustomerTickets.vue'
import Admissions from './views/Admissions.vue'
import Monitoring from './views/Monitoring.vue'
import InfoPage from './views/InfoPage.vue'
import Search from './views/Search.vue'
import StaffDirectory from './views/StaffDirectory.vue'
import DirectoryPage from './views/DirectoryPage.vue'
import DirectoryList from './views/DirectoryList.vue'
import { findInfoPage } from './info'
import { setTitle } from './title'
export const router = createRouter({ history: createWebHistory(), routes: [
 { path: '/', component: Events }, { path: '/events/:id', component: EventDetail },
 { path: '/staff/login', component: Login }, { path: '/staff', component: Admin },
 { path: '/staff/stock', component: StockMaintenance },
 { path: '/staff/monitoring', component: Monitoring },
 { path: '/staff/admissions', component: Admissions },
 { path: '/account/orders/:orderId/tickets', component: CustomerTickets },
 { path: '/account/login', component: CustomerLogin }, { path: '/account', component: CustomerAccount },
 { path: '/account/orders', component: Orders }, { path: '/events/:eventId/reserve/:ticketId', component: Reserve },
 { path: '/account/purchases', component: Purchases },
 { path: '/help/:slug', component: InfoPage },
 { path: '/search', component: Search },
 { path: '/staff/directory', component: StaffDirectory },
 { path: '/venues', component: DirectoryList }, { path: '/performers', component: DirectoryList },
 { path: '/venues/:slug', component: DirectoryPage }, { path: '/performers/:slug', component: DirectoryPage },
 { path: '/:pathMatch(.*)*', component: Events },
] })
const titles:[RegExp,string][]=[[/^\/account\/login/,'會員登入'],[/^\/account\/orders\/.+\/tickets/,'電子票券'],[/^\/account\/orders/,'我的保票訂單'],[/^\/account\/purchases/,'購票紀錄'],[/^\/account/,'會員空間'],[/^\/staff/,'員工後台'],[/\/reserve\//,'選擇張數']]
router.afterEach(to=>{
 const info=to.path.startsWith('/help/')?findInfoPage(to.params.slug)?.title:undefined
 setTitle(info||titles.find(([r])=>r.test(to.path))?.[1])
})
