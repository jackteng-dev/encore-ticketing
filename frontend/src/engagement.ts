import { ref } from 'vue'
import { api, ApiError, customerWrite, type Customer } from './api'

export interface EventFlags { eventId: string; favorite: boolean; reminder: boolean; reminderAvailable: boolean }
export interface FavoriteItem { eventId: string; title: string; venue: string; firstStartsAt: string | null; minPriceTwd: number | null; selling: boolean; savedAt: string }
export interface SiteNotification { id: string; kind: string; title: string; body: string; link: string; createdAt: string; readAt: string | null }

/** 頁首鈴鐺的未讀數；null 代表未登入或尚未讀取。 */
export const unread = ref<number | null>(null)

export async function refreshUnread() {
  try { unread.value = (await api<{ unread: number }>('/customer/notifications/summary')).unread }
  catch (e) { if (e instanceof ApiError && e.status === 401) unread.value = null }
}

/** 已登入時回傳會員，未登入回傳 null（不視為錯誤）。 */
export async function currentCustomer(): Promise<Customer | null> {
  try { return await api<Customer>('/customer/auth/me') } catch (e) { if (e instanceof ApiError && e.status === 401) return null; throw e }
}

/** 設定式寫入：on 為目標狀態，重送相同請求結果不變。 */
export function setFlag(customer: Customer, eventId: string, kind: 'favorite' | 'reminder', on: boolean) {
  return customerWrite<EventFlags>(`/customer/events/${eventId}/${kind}`, { on }, customer.id)
}

/** 通知連結只允許站內相對路徑。 */
export const safeLink = (link: string) => (/^\/[^/]/.test(link) ? link : '/')
