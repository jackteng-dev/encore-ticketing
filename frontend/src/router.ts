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
 { path: '/:pathMatch(.*)*', component: Events },
] })
