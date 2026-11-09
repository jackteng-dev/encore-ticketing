<script setup lang="ts">
import {computed} from 'vue'
import qrcode from 'qrcode-generator'
const props=defineProps<{code:string;unit:number}>()
const matrix=computed(()=>{if(!/^[A-Za-z0-9_-]{43}$/.test(props.code))return null;const qr=qrcode(0,'M');qr.addData(props.code,'Byte');qr.make();const n=qr.getModuleCount(),cells=[];for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(qr.isDark(y,x))cells.push(`M${x+4},${y+4}h1v1h-1z`);return {size:n+8,path:cells.join('')}})
</script>
<template><figure v-if="matrix" class="ticket-qr"><svg role="img" :aria-label="'第 '+unit+' 張入場 QR 碼'" :viewBox="`0 0 ${matrix.size} ${matrix.size}`" xmlns="http://www.w3.org/2000/svg" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/><path :d="matrix.path" fill="#000"/></svg><figcaption>由現場員工掃描後核對。請勿分享 QR 或票券碼。</figcaption></figure></template>
<style scoped>.ticket-qr{margin:1rem 0}.ticket-qr svg{display:block;width:240px;max-width:100%;height:auto;background:white}.ticket-qr figcaption{font-size:.85rem;margin-top:.6rem}</style>
