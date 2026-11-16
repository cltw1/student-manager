<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, CheckCircle2, CircleAlert, Clock3, X, ArrowRight, LoaderCircle } from 'lucide-vue-next'
import { api, type Operation, type Student, type StudentPage } from '../api'
import BatchReportTable from './BatchReportTable.vue'
import { studentFields, displayValue } from '../field-state'
const props = defineProps<{ result: Operation; conversationId: string; disabled?: boolean }>()
const emit = defineEmits<{ updated: []; notify: [message: string, kind?: string]; processing: [value: boolean]; review: [id: string] }>()
const busy = ref(false)
const pending = computed(() => props.result.action?.status === 'PENDING')
const rows = computed<Student[]>(() => {
  const data = props.result.data
  if (!data) return []
  if ('items' in data) return (data as StudentPage).items
  if ('studentNo' in data) return [data as Student]
  return []
})
const query = computed(() => props.result.tool.includes('search') || props.result.tool === 'get_student')
const isDelete = computed(() => props.result.tool.includes('delete'))
const title = computed(() => pending.value ? props.result.action?.batch ? '批量处理预览' : isDelete.value ? '确认删除学生' : '确认修改信息' : props.result.message)
async function retry() { if (!props.result.data || !('rows' in props.result.data)) return; busy.value = true; emit('processing',true); try { const view = await api<{id:string}>('/reviews/retry', {method:'POST',body:JSON.stringify({conversationId:props.conversationId,report:props.result.data})}); emit('review',view.id); emit('updated') } catch (error) { emit('notify',error instanceof Error ? error.message : '无法生成修正界面','error') } finally { busy.value = false; emit('processing',false) } }
async function resolve(confirm: boolean) {
  if (!props.result.action || busy.value) return
  busy.value = true
  emit('processing', true)
  try {
    const action = props.result.action
    const result = await api<Operation>(`/chat/actions/${action.id}/${confirm ? 'confirm' : 'cancel'}`, { method: 'POST', body: JSON.stringify({ conversationId: props.conversationId }) })
    Object.assign(props.result, result, { action: { ...action, status: result.code === 'ACTION_CANCELLED' ? 'CANCELLED' : result.success ? 'CONFIRMED' : result.mutated ? 'PARTIAL' : 'FAILED' } })
    emit('notify', result.message, result.success ? 'success' : 'error'); emit('updated')
  } catch (error) { emit('notify', error instanceof Error ? error.message : '处理失败', 'error') }
  finally { busy.value = false; emit('processing', false) }
}
</script>
<template>
  <div class="operation-card" :class="{ failed: !result.success, pending }">
    <div class="operation-heading"><component :is="pending ? Clock3 : result.success ? CheckCircle2 : CircleAlert" :size="17" /><strong>{{ title }}</strong><span v-if="query && result.success" class="small-label">实时查询</span></div>
    <div v-if="result.data && 'reviewId' in result.data" class="review-launch"><p>{{ result.message }}</p><button class="button primary" :disabled="busy || disabled" @click="emit('review',result.data.reviewId)">校对、补全并继续</button><small>{{ result.data.schemaChanged ? '新字段和全员补全值已保存；本批学生操作仍需最终确认。' : '当前尚未执行学生数据操作。字段对应和修正内容由你确认。' }}</small></div>
    <template v-else-if="pending && result.action">
      <p class="muted operation-note">{{ result.action.batch ? '核对逐行结果，仅处理有效记录；无效记录会跳过。' : '核对以下信息，点击确认后执行。' }}5 分钟内有效。</p>
      <BatchReportTable v-if="result.action.batch" :report="result.action.batch" />
      <div v-else-if="result.action.before" class="change-preview"><div><span>{{ result.action.after ? '修改前' : '待删除学生' }}</span><strong>{{ result.action.before.name }}</strong><code>{{ result.action.before.studentNo }}</code></div><ArrowRight v-if="result.action.after" :size="18" /><div v-if="result.action.after"><span>修改后</span><strong>{{ result.action.after.name }}</strong><code>{{ result.action.after.studentNo }}</code></div></div>
      <div class="operation-actions"><button class="button small" :disabled="busy || disabled" @click="resolve(false)"><X :size="14" />取消</button><button class="button small" :class="isDelete ? 'danger' : 'primary'" :disabled="busy || disabled" @click="resolve(true)"><LoaderCircle v-if="busy" class="spin" :size="14" /><Check v-else :size="14" />{{ result.action.batch ? `确认处理 ${result.action.batch.readyCount} 条` : isDelete ? '确认删除' : '确认修改' }}</button></div>
    </template>
    <BatchReportTable v-else-if="result.data && 'rows' in result.data" :report="result.data" />
    <template v-else-if="result.success && (rows.length || query)">
      <div class="result-table-wrap"><table class="result-table"><thead><tr><th>学号</th><th>姓名</th><th v-for="field in studentFields" :key="field.key">{{ field.label }}</th></tr></thead><tbody><tr v-for="student in rows" :key="student.studentNo"><td><code>{{ student.studentNo }}</code></td><td>{{ student.name }}</td><td v-for="field in studentFields" :key="field.key">{{ displayValue(student.fields?.[field.key]) }}</td></tr><tr v-if="!rows.length"><td :colspan="2 + studentFields.length" class="empty-result">未找到匹配的学生</td></tr></tbody></table></div>
      <div v-if="result.data && 'items' in result.data" class="result-footer">共 {{ result.data.total }} 条 · 第 {{ result.data.page }} 页 · 当前显示 {{ rows.length }} 条</div>
    </template>
    <p v-else-if="!result.success" class="operation-note">{{ result.message }}<span class="error-code">{{ result.code }}</span></p>
    <div v-if="!result.success && result.data && 'rows' in result.data" class="operation-actions"><button class="button small" :disabled="busy || disabled" @click="retry">修正失败记录并重新确认</button></div>
  </div>
</template>
