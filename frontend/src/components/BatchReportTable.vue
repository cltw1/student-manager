<script setup lang="ts">
import type { BatchReport } from '../api'
import { studentFields, displayValue } from '../field-state'
defineProps<{ report: BatchReport }>()
const labels: Record<string, string> = { CREATE: '新增', UPDATE: '更新', DELETE: '删除', QUERY: '查询', UPSERT: '新增或更新', READY: '待处理', INVALID: '无效', FAILED: '失败', SUCCESS: '成功', UNCHANGED: '跳过' }
</script>
<template>
  <div class="batch-summary"><span>共 <b>{{ report.total }}</b> 条</span><span v-if="report.readyCount" class="ready">待处理 {{ report.readyCount }}</span><span class="succeeded">成功 {{ report.successCount }}</span><span :class="{ errors: report.failureCount }">失败 {{ report.failureCount }}</span><span>跳过 {{ report.unchangedCount }}</span></div>
  <div class="batch-table-scroll"><table class="batch-table"><thead><tr><th>行</th><th>学号</th><th>原姓名</th><th>新姓名 / 姓名</th><th v-for="field in studentFields" :key="field.key">{{ field.label }}</th><th>操作</th><th>状态</th><th>说明</th></tr></thead><tbody><tr v-for="(row, index) in report.rows" :key="index"><td>{{ row.row }}</td><td><code>{{ row.studentNo || '—' }}</code><template v-if="row.newStudentNo"><br /><code class="new-number">→ {{ row.newStudentNo }}</code></template></td><td>{{ row.before?.name || '—' }}</td><td>{{ row.after?.name || row.name || '—' }}</td><td v-for="field in studentFields" :key="field.key">{{ displayValue(row.after?.fields?.[field.key] ?? row.before?.fields?.[field.key]) }}</td><td>{{ labels[row.operation] || row.operation }}</td><td><span class="batch-row-status" :class="row.status.toLowerCase()">{{ labels[row.status] || row.status }}</span></td><td class="batch-row-message">{{ row.message }}</td></tr></tbody></table></div>
</template>
