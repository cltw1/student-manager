<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { X, Files, Upload, FileSpreadsheet, LoaderCircle, Eye, Download, CircleAlert, RotateCcw } from 'lucide-vue-next'
import { api, type Operation, type ReviewView } from '../api'
import OperationCard from './OperationCard.vue'
const props = defineProps<{ conversationId: string; source: 'text' | 'excel' }>()
const emit = defineEmits<{ close: []; updated: []; notify: [message: string, kind?: string]; review: [id: string] }>()
const source = ref(props.source), mode = ref(props.source === 'excel' ? 'UPSERT' : 'CREATE'), text = ref(''), file = ref<File | null>(null)
const working = ref(false), confirming = ref(false), error = ref(''), result = ref<Operation | null>(null), fileInput = ref<HTMLInputElement>()
const busy = computed(() => working.value || confirming.value)
const modes = computed(() => source.value === 'excel' ? [{ value: 'UPSERT', label: '新增或更新（按学号匹配）' }, { value: 'CREATE', label: '仅新增学生' }, { value: 'UPDATE', label: '仅更新已有学生' }] : [{ value: 'CREATE', label: '批量新增' }, { value: 'QUERY', label: '批量查找' }, { value: 'UPDATE', label: '批量修改' }, { value: 'DELETE', label: '批量删除' }, { value: 'UPSERT', label: '新增或更新' }])
const placeholder = computed(() => ['QUERY', 'DELETE'].includes(mode.value) ? '每行一个学号，例如：\n2670001\n2670002\n2670003' : '每行“学号 姓名”，支持粘贴Excel两列：\n2670001 李明\n2670002 李华\n2670003 张硕')
watch(source, value => { if (value === 'excel' && !['CREATE', 'UPDATE', 'UPSERT'].includes(mode.value)) mode.value = 'UPSERT'; error.value = '' })
function chooseFile(selected?: File) {
  if (!selected || result.value || busy.value) return
  if (!/\.(xlsx|xls)$/i.test(selected.name)) { error.value = '仅支持.xlsx和.xls文件'; return }
  if (selected.size > 2 * 1024 * 1024) { error.value = '文件不能超过2MB'; return }
  file.value = selected; error.value = ''
}
function drop(event: DragEvent) { chooseFile(event.dataTransfer?.files[0]) }
async function preview() {
  if (busy.value) return
  working.value = true; error.value = ''
  try {
    if (source.value === 'excel') {
      if (!file.value) throw new Error('请先选择Excel文件')
      const data = new FormData(); data.append('file', file.value); data.append('mode', mode.value); data.append('conversationId', props.conversationId)
      const view = await api<ReviewView>('/reviews/excel', { method: 'POST', body: data }); emit('review', view.id); emit('close')
    } else { const view = await api<ReviewView>('/reviews/text', { method: 'POST', body: JSON.stringify({ conversationId: props.conversationId, mode: mode.value, text: text.value }) }); emit('review', view.id); emit('close') }
    emit('updated')
  } catch (ex) { error.value = ex instanceof Error ? ex.message : '预览失败' }
  finally { working.value = false }
}
async function reset() {
  if (busy.value) return
  working.value = true; error.value = ''
  try {
    if (result.value?.action?.status === 'PENDING') await api(`/chat/actions/${result.value.action.id}/cancel`, { method: 'POST', body: JSON.stringify({ conversationId: props.conversationId }) })
    result.value = null; emit('updated')
  } catch (ex) { error.value = ex instanceof Error ? ex.message : '重置失败' }
  finally { working.value = false }
}
</script>
<template>
  <div class="modal-backdrop" @mousedown.self="!busy && emit('close')"><section class="modal batch-modal" role="dialog" aria-modal="true" aria-labelledby="batch-title"><div class="modal-heading"><span class="modal-icon"><Files :size="23" /></span><button class="icon-button" :disabled="busy" aria-label="关闭批量处理" @click="emit('close')"><X :size="19" /></button></div><h2 id="batch-title">一次处理，多位同学。</h2><p>每批最多500条，先识别字段、补全问题数据，再确认执行。</p><div class="batch-source-tabs"><button :class="{ active: source === 'text' }" :disabled="busy || !!result" @click="source = 'text'"><Files :size="15" />批量文本</button><button :class="{ active: source === 'excel' }" :disabled="busy || !!result" @click="source = 'excel'"><FileSpreadsheet :size="15" />Excel 导入</button></div><label for="batch-mode">操作类型</label><select id="batch-mode" v-model="mode" class="form-input" :disabled="busy || !!result"><option v-for="option in modes" :key="option.value" :value="option.value">{{ option.label }}</option></select><p class="batch-mode-help">{{ mode === 'UPSERT' ? '学号不存在时新增，已存在时更新提供的字段；内容相同则跳过。' : mode === 'CREATE' ? '学号已存在的记录会标为失败，不覆盖原数据。' : mode === 'UPDATE' ? '按现有学号更新姓名及提供的字段；不存在的学号会标为失败。' : mode === 'DELETE' ? '先展示目标记录，确认后删除有效记录。' : '逐条展示查询结果，包含不存在学号的提示。' }}</p>
    <template v-if="source === 'text'"><label for="batch-text">学生记录</label><textarea id="batch-text" v-model="text" class="batch-textarea" :placeholder="placeholder" maxlength="30000" :disabled="busy || !!result"></textarea></template>
    <template v-else><input ref="fileInput" type="file" accept=".xlsx,.xls" class="batch-file-input" aria-label="选择Excel文件" :disabled="busy || !!result" @change="chooseFile(($event.target as HTMLInputElement).files?.[0])" /><button class="excel-dropzone" :disabled="busy || !!result" @click="fileInput?.click()" @dragover.prevent @drop.prevent="drop"><Upload :size="25" /><strong>{{ file?.name || '点击选择，或拖入 Excel 文件' }}</strong><span>支持 .xlsx / .xls · 最大 2MB</span></button><p class="excel-help">支持识别学号、姓名及其他字段。别名、重复列、缺失值和新增字段会进入校对界面逐项确认。学号建议设为文本，保留前导零。</p><a class="template-download" href="/api/batches/template" download="学生导入模板.xlsx"><Download :size="14" />下载当前字段模板</a></template>
    <p v-if="error" class="form-error" role="alert"><CircleAlert :size="15" />{{ error }}</p><OperationCard v-if="result" :result="result" :conversation-id="conversationId" @notify="(message, kind) => emit('notify', message, kind)" @updated="emit('updated')" @processing="confirming = $event" /><div class="modal-footer"><button v-if="result" class="button" :disabled="busy" @click="reset"><RotateCcw :size="15" />重新选择</button><button class="button" :disabled="busy" @click="emit('close')">{{ result ? '关闭' : '取消' }}</button><button v-if="!result" class="button primary" :disabled="busy || (source === 'text' ? !text.trim() : !file)" @click="preview"><LoaderCircle v-if="busy" class="spin" :size="16" /><Eye v-else :size="16" />{{ busy ? '正在校验…' : '识别数据并进入校对' }}</button></div></section></div>
</template>
