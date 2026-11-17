<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { closeMock, mockFeature } from '../mockNotice'
const dialog = ref<HTMLDialogElement | null>(null)
watch(mockFeature, async v => {
  await nextTick()
  if (!dialog.value) return
  if (v && !dialog.value.open) dialog.value.showModal()
  else if (!v && dialog.value.open) dialog.value.close()
})
</script>
<template>
  <dialog ref="dialog" class="mock-dialog" aria-labelledby="mock-title" @close="closeMock" @click.self="closeMock">
    <div class="mock-dialog-body">
      <div class="eyebrow">DEMO ONLY</div>
      <h2 id="mock-title">此為模擬版本</h2>
      <p><strong>{{ mockFeature }}</strong> 尚未串接正式服務。這是展示作品，點擊不會連到外部服務、不會送出或收取任何資料。</p>
      <p class="muted">正式上線時才會接上真實服務；目前可使用本站的展示帳號與免費模擬付款體驗完整流程。</p>
      <button class="primary" autofocus @click="closeMock">我知道了</button>
    </div>
  </dialog>
</template>
