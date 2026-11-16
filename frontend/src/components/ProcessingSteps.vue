<script setup lang="ts">
import type { ProcessingStep } from '../api'
import { ChevronDown, Workflow, CheckCircle2, CircleAlert, Clock3, LoaderCircle } from 'lucide-vue-next'
defineProps<{ steps?: ProcessingStep[] }>()
const labels: Record<string, string> = { RUNNING: '处理中', SUCCESS: '已完成', FAILED: '需调整', NEEDS_INPUT: '待补充', NEEDS_CONFIRMATION: '待确认' }
</script>
<template>
  <details v-if="steps?.length" class="processing-steps"><summary><Workflow :size="14" /><span>处理过程与依据（摘要）</span><small>{{ steps.length }} 项</small><ChevronDown :size="14" /></summary><p class="processing-caption">展示识别、校验和执行状态，便于核对操作依据。</p><ol><li v-for="step in steps" :key="step.id"><component :is="step.status === 'RUNNING' ? LoaderCircle : step.status === 'SUCCESS' ? CheckCircle2 : step.status === 'FAILED' ? CircleAlert : Clock3" :size="14" :class="{ spin: step.status === 'RUNNING' }" /><div><strong>{{ step.title }}</strong><span>{{ labels[step.status] || step.status }}</span><p>{{ step.detail }}</p></div></li></ol></details>
</template>
