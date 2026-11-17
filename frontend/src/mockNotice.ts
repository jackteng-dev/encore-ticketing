import { ref } from 'vue'

/** 尚未串接的正式服務（金流、社群登入、簡訊、App 等）。點擊時只顯示模擬說明，不送出任何資料。 */
export const mockFeature = ref<string | null>(null)

export function showMock(feature: string) {
  mockFeature.value = feature
}

export function closeMock() {
  mockFeature.value = null
}
