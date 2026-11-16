<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { GraduationCap, Sparkles, Plus, MessageSquare, UsersRound, Search, ArrowUp, ArrowUpRight, ChevronRight, ChevronLeft, Settings2, PanelLeftClose, PanelLeftOpen, CircleHelp, Database, Check, X, PencilLine, Trash2, UserRoundPlus, LoaderCircle, CircleAlert, RefreshCw, Copy, CheckCheck, SlidersHorizontal, ArrowRight, CircleCheck, Files, FileSpreadsheet } from 'lucide-vue-next'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import OperationCard from './components/OperationCard.vue'
import BatchPanel from './components/BatchPanel.vue'
import ReviewWizard from './components/ReviewWizard.vue'
import ProcessingSteps from './components/ProcessingSteps.vue'
import FieldValueInput from './components/FieldValueInput.vue'
import { studentFields, refreshFields, displayValue } from './field-state'
import { api, ApiError, streamChat, type Student, type StudentPage, type Conversation, type Message, type Operation, type Status } from './api'

const view = ref<'chat' | 'students'>('chat'), sidebarOpen = ref(true), settingsOpen = ref(false)
const activeReviewId = ref(''), formFields = ref<Record<string,unknown>>({}), fields = studentFields
const batchOpen = ref(false), batchSource = ref<'text' | 'excel'>('text')
const status = ref<Status | null>(null), backendConnected = ref(true)
const conversations = ref<Conversation[]>([]), conversationId = ref(''), messages = ref<Message[]>([])
const input = ref(''), sending = ref(false), phase = ref(''), loadingConversation = ref(false)
const composer = ref<HTMLTextAreaElement>(), scrollArea = ref<HTMLElement>()
const toasts = ref<{ id: number; message: string; kind: string }[]>([])
const students = ref<Student[]>([]), total = ref(0), page = ref(1), pageSize = 10
const customSearchField = ref(''), customSearchValue = ref<unknown>(''), customSearchOperator = ref('EQ')
const selectedSearchField = computed(() => fields.value.find(field => field.key === customSearchField.value))
const studentNoFilter = ref(''), nameFilter = ref(''), loadingStudents = ref(false), listError = ref('')
const studentDialog = ref(false), editing = ref<Student | null>(null), formStudentNo = ref(''), formName = ref(''), formError = ref(''), saving = ref(false)
const deleteTarget = ref<Student | null>(null), deleting = ref(false), deleteError = ref('')
const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const pendingCount = computed(() => messages.value.flatMap(m => m.results).filter(r => r.action?.status === 'PENDING').length)
const prompts = [
  { icon: UserRoundPlus, title: '批量添加新同学', detail: '粘贴多行学号和姓名', value: '添加下列学生信息：\n2670001 李明\n2670002 李华\n2670003 张硕', color: 'blue' },
  { icon: Search, title: '查找学生信息', detail: '按学号或姓名快速查询', value: '查询姓名包含“张”的学生', color: 'green' },
  { icon: PencilLine, title: '修改学生信息', detail: '更正姓名，或更新学号', value: '把学号 20260001 的学生姓名改成李四', color: 'orange' },
  { icon: Trash2, title: '删除学生记录', detail: '核对信息，确认后再删除', value: '删除学号 20260001 的学生', color: 'purple' },
]
function notify(message: string, kind = 'success') {
  const id = Date.now() + Math.random(); toasts.value.push({ id, message, kind })
  setTimeout(() => { toasts.value = toasts.value.filter(t => t.id !== id) }, 5200)
}
function errorMessage(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }
function markdown(text: string) { return DOMPurify.sanitize(marked.parse(text, { async: false }) as string, { FORBID_TAGS: ['img', 'iframe', 'style'], FORBID_ATTR: ['style'] }) }
async function refreshStatus() {
  try { status.value = await api<Status>('/status'); backendConnected.value = true }
  catch { backendConnected.value = false; status.value = null }
}
async function openBatch(source: 'text' | 'excel' = 'text') {
  if (sending.value || loadingConversation.value) return
  try {
    if (!conversationId.value) conversationId.value = (await api<Conversation>('/conversations', { method: 'POST' })).id
    batchSource.value = source; batchOpen.value = true
  } catch (error) { notify(errorMessage(error), 'error') }
}
function openReview(id: string) { activeReviewId.value = id; batchOpen.value = false }
async function syncBatch() {
  await refreshFields()
  await refreshStatus(); await refreshConversations().catch(() => {})
  if (conversationId.value) {
    const current = await api<{ messages: Message[] }>(`/conversations/${conversationId.value}`).catch(() => null)
    if (current) messages.value = current.messages
  }
  if (view.value === 'students') await loadStudents()
}
async function refreshConversations() { conversations.value = await api<Conversation[]>('/conversations') }
async function newChat() {
  if (sending.value) return
  view.value = 'chat'; conversationId.value = ''; messages.value = []; input.value = ''
  if (window.innerWidth < 800) sidebarOpen.value = false
  await nextTick(); composer.value?.focus()
}
async function selectConversation(id: string) {
  if (sending.value || loadingConversation.value) return
  loadingConversation.value = true
  try {
    const data = await api<{ id: string; title: string; messages: Message[] }>(`/conversations/${id}`)
    conversationId.value = id; messages.value = data.messages; view.value = 'chat'
    if (window.innerWidth < 800) sidebarOpen.value = false
    await nextTick(); scrollBottom()
  } catch (error) { notify(errorMessage(error), 'error'); await refreshConversations().catch(() => {}) }
  finally { loadingConversation.value = false }
}
function goTo(value: 'chat' | 'students') { view.value = value; if (window.innerWidth < 800) sidebarOpen.value = false }
async function deleteConversation(id: string) {
  if (sending.value) return
  try { await api(`/conversations/${id}`, { method: 'DELETE' }); if (conversationId.value === id) await newChat(); await refreshConversations() }
  catch (error) { notify(errorMessage(error), 'error') }
}
function fillPrompt(value: string) { input.value = value; nextTick(() => { composer.value?.focus(); resizeComposer() }) }
function resizeComposer() { if (composer.value) { composer.value.style.height = 'auto'; composer.value.style.height = Math.min(composer.value.scrollHeight, 180) + 'px' } }
function scrollBottom() { if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight }
async function send() {
  const text = input.value.trim()
  if (!text || sending.value || loadingConversation.value) return
  sending.value = true; phase.value = '正在连接 DeepSeek…'
  try {
    if (!conversationId.value) {
      const conversation = await api<Conversation>('/conversations', { method: 'POST' })
      conversationId.value = conversation.id
    }
    const id = conversationId.value
    const user: Message = { id: crypto.randomUUID(), role: 'user', content: text, results: [] }
    const answer: Message = { id: crypto.randomUUID(), role: 'assistant', content: '', results: [], steps: [] }
    messages.value.push(user, answer); input.value = ''; await nextTick(); resizeComposer(); scrollBottom()
    const index = messages.value.length - 1
    await streamChat({ conversationId: id, requestId: crypto.randomUUID(), message: text }, (event, data) => {
      const current = messages.value[index]!
      if (event === 'meta') { current.id = data.messageId; void refreshConversations().catch(() => {}) }
      if (event === 'phase') phase.value = data.message
      if (event === 'trace') { current.steps ||= []; const found = current.steps.findIndex(step => step.id === data.id); if (found < 0) current.steps.push(data); else current.steps[found] = data }
      if (event === 'delta') current.content += data.text
      if (event === 'result') { current.results.push(data as Operation); if (data.mutated) void refreshStatus() }
      if (event === 'error') notify(data.message, 'error')
      if (event === 'done') messages.value[index] = data as Message
      nextTick(scrollBottom)
    })
  } catch (error) {
    const message = errorMessage(error); notify(message, 'error')
    const last = messages.value[messages.value.length - 1]
    if (last?.role === 'assistant') last.content += '\n\n' + message
  } finally {
    sending.value = false; phase.value = ''; void refreshStatus(); void refreshConversations().catch(() => {}); nextTick(scrollBottom)
  }
}
function onComposerKey(event: KeyboardEvent) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); void send() } }
async function loadStudents() {
  loadingStudents.value = true; listError.value = ''
  try {
    const query = new URLSearchParams({ page: String(page.value), pageSize: String(pageSize), studentNo: studentNoFilter.value.trim(), name: nameFilter.value.trim() })
    const result = customSearchField.value ? await api<StudentPage>('/students/search', {method:'POST',body:JSON.stringify({page:page.value,pageSize,filters:[...(studentNoFilter.value.trim() ? [{field:'studentNo',operator:'EQ',value:studentNoFilter.value.trim()}] : []),...(nameFilter.value.trim() ? [{field:'name',operator:'CONTAINS',value:nameFilter.value.trim()}] : []),{field:customSearchField.value,operator:customSearchOperator.value,value:customSearchValue.value}]})}) : await api<StudentPage>('/students?' + query)
    students.value = result.items; total.value = result.total
    if (!students.value.length && page.value > 1) { page.value = Math.max(1, Math.ceil(total.value / pageSize)); await loadStudents() }
  } catch (error) { listError.value = errorMessage(error); students.value = [] }
  finally { loadingStudents.value = false }
}
async function searchStudents() { page.value = 1; await loadStudents() }
async function resetSearch() { customSearchField.value = ''; customSearchValue.value = ''; customSearchOperator.value = 'EQ'; studentNoFilter.value = ''; nameFilter.value = ''; await searchStudents() }
async function changePage(next: number) { if (next < 1 || next > pages.value || loadingStudents.value) return; page.value = next; await loadStudents() }
function openStudent(student?: Student) {
  formFields.value = { ...(student?.fields || {}) }; editing.value = student ? { ...student } : null; formStudentNo.value = student?.studentNo || ''; formName.value = student?.name || ''; formError.value = ''; studentDialog.value = true
  nextTick(() => document.getElementById('student-no')?.focus())
}
async function saveStudent() {
  if (saving.value) return
  saving.value = true; formError.value = ''
  try {
    const old = editing.value
    await api(old ? `/students/${encodeURIComponent(old.studentNo)}` : '/students', { method: old ? 'PUT' : 'POST', body: JSON.stringify({ studentNo: formStudentNo.value.trim(), name: formName.value.trim(), expectedName: old?.name, fields: formFields.value, expectedFields: old?.fields }) })
    notify(old ? '学生信息已更新' : '学生添加成功'); studentDialog.value = false; await loadStudents(); await refreshStatus()
  } catch (error) { formError.value = errorMessage(error) + (error instanceof ApiError && error.code === 'NETWORK_ERROR' ? '；执行结果待核实，请刷新列表后再操作' : '') }
  finally { saving.value = false }
}
async function removeStudent() {
  if (!deleteTarget.value || deleting.value) return
  deleting.value = true; deleteError.value = ''
  try {
    await api(`/students/${encodeURIComponent(deleteTarget.value.studentNo)}?expectedName=${encodeURIComponent(deleteTarget.value.name)}`, { method: 'DELETE' })
    deleteTarget.value = null; notify('学生记录已删除'); await loadStudents(); await refreshStatus()
  } catch (error) { deleteError.value = errorMessage(error) }
  finally { deleting.value = false }
}
async function copyText(text: string) { try { await navigator.clipboard.writeText(text); notify('回复已复制') } catch { notify('复制失败，请手动选择文字', 'error') } }
function escapeKey(event: KeyboardEvent) { if (event.key === 'Escape') { settingsOpen.value = false; if (!saving.value) studentDialog.value = false; if (!deleting.value) deleteTarget.value = null } }
watch(view, value => { if (value === 'students') void loadStudents() })
let statusTimer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  document.addEventListener('keydown', escapeKey)
  await Promise.allSettled([refreshStatus(), refreshConversations(), refreshFields()])
  if (window.innerWidth < 800) sidebarOpen.value = false
  statusTimer = setInterval(() => { void refreshStatus() }, 30000)
})
onUnmounted(() => { document.removeEventListener('keydown', escapeKey); if (statusTimer) clearInterval(statusTimer) })
</script>

<template>
  <div class="workspace" :class="{ 'sidebar-collapsed': !sidebarOpen }">
    <div v-if="sidebarOpen" class="mobile-overlay" @click="sidebarOpen = false"></div>
    <aside class="sidebar">
      <a class="brand" href="#" aria-label="学序首页" @click.prevent="newChat"><span class="brand-mark"><GraduationCap :size="25" :stroke-width="1.8" /></span><span><strong>学序<span class="brand-dot">.</span></strong><small>STUDENT WORKSPACE</small></span></a>
      <button class="new-chat" :disabled="sending" @click="newChat"><Plus :size="18" />开启新会话<kbd>NEW</kbd></button>
      <div class="sidebar-label">工作空间</div>
      <nav class="main-nav"><button :class="{ active: view === 'chat' }" @click="goTo('chat')"><MessageSquare :size="18" /><span>AI 助手</span><span class="nav-tag">AI</span></button><button :class="{ active: view === 'students' }" @click="goTo('students')"><UsersRound :size="18" /><span>学生管理</span><span v-if="status?.databaseConnected" class="count-badge">{{ status.studentCount }}</span></button></nav>
      <div class="history-heading"><span class="sidebar-label">最近会话</span><span>{{ conversations.length ? conversations.length : '' }}</span></div>
      <div class="conversation-list"><div v-for="conversation in conversations.slice(0, 12)" :key="conversation.id" class="conversation-row" :class="{ selected: conversation.id === conversationId && view === 'chat' }"><button class="conversation-select" :disabled="sending || loadingConversation" @click="selectConversation(conversation.id)"><MessageSquare :size="14" /><span>{{ conversation.title }}</span></button><button class="conversation-delete icon-button" :disabled="sending" title="删除此会话" aria-label="删除此会话" @click="deleteConversation(conversation.id)"><Trash2 :size="13" /></button></div><div v-if="!conversations.length" class="history-empty">你的下一次高效管理<br />从一句话开始。</div></div>
      <div class="sidebar-bottom"><div class="workspace-info"><span class="workspace-avatar"><GraduationCap :size="18" /></span><div><strong>本地工作区</strong><span><i class="status-dot" :class="{ offline: !backendConnected }"></i>{{ backendConnected ? '服务运行中' : '服务未连接' }}</span></div><button class="icon-button" title="配置说明" aria-label="打开配置说明" @click="settingsOpen = true"><Settings2 :size="17" /></button></div><button class="sidebar-help" @click="settingsOpen = true"><CircleHelp :size="15" />使用与配置说明<ArrowUpRight :size="14" /></button></div>
    </aside>

    <main class="main-area">
      <header class="topbar"><div class="breadcrumbs"><button class="icon-button toggle-sidebar" :aria-label="sidebarOpen ? '收起侧栏' : '展开侧栏'" @click="sidebarOpen = !sidebarOpen"><PanelLeftClose v-if="sidebarOpen" :size="19" /><PanelLeftOpen v-else :size="19" /></button><span>工作台</span><ChevronRight :size="13" /><strong>{{ view === 'chat' ? 'AI 助手' : '学生管理' }}</strong></div><div class="topbar-right"><span class="local-badge"><span class="status-dot" :class="{ offline: !status?.databaseConnected }"></span>{{ status?.databaseConnected ? '数据已连接' : '数据库未连接' }}</span><button class="user-avatar" aria-label="本地工作区配置" @click="settingsOpen = true">学</button></div></header>
      <div v-if="!backendConnected || (status && !status.databaseConnected)" class="connection-banner"><CircleAlert :size="16" /><span>{{ !backendConnected ? '后端服务未连接，请启动本地服务。' : '数据库未连接，请检查 MySQL 和连接配置。' }}</span><button @click="settingsOpen = true">查看配置<ArrowRight :size="13" /></button></div>

      <section v-if="view === 'chat'" class="chat-area" :class="{ 'has-messages': messages.length }">
        <div ref="scrollArea" class="chat-scroll" aria-live="polite" aria-relevant="additions text">
          <div v-if="!messages.length" class="welcome">
            <div class="welcome-emblem"><Sparkles :size="31" :stroke-width="1.7" /><span class="emblem-spark">✦</span></div>
            <div class="eyebrow">你的学生管理 AI 助手</div>
            <h1>让学生管理，<span>简单一点。</span></h1>
            <p class="welcome-description">从一句话开始，完成学生信息的查询与管理。<br class="mobile-break" />把繁琐交给 AI，把时间留给更重要的事。</p>
            <div class="prompt-grid"><button v-for="prompt in prompts" :key="prompt.title" class="prompt-card" @click="fillPrompt(prompt.value)"><span class="prompt-icon" :class="prompt.color"><component :is="prompt.icon" :size="19" :stroke-width="1.8" /></span><strong>{{ prompt.title }}</strong><span class="prompt-description">{{ prompt.detail }}</span><ArrowUpRight class="prompt-arrow" :size="15" /></button></div>
            <button class="manual-entry" @click="goTo('students')">习惯表格操作？<span>前往学生管理<ArrowRight :size="14" /></span></button>
          </div>
          <div v-else class="message-list">
            <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
              <template v-if="message.role === 'user'"><div class="user-bubble">{{ message.content }}</div></template>
              <template v-else><span class="assistant-avatar"><Sparkles :size="20" /></span><div class="assistant-body"><div class="assistant-label">学序<span>DeepSeek</span></div><ProcessingSteps :steps="message.steps" /><div v-if="message.content" class="markdown" v-html="markdown(message.content)"></div><OperationCard v-for="(result, index) in message.results" :key="index" :result="result" :conversation-id="conversationId" :disabled="sending" @notify="notify" @updated="syncBatch" @review="openReview" /><div v-if="sending && message === messages[messages.length - 1]" class="thinking"><span class="thinking-dots"><i></i><i></i><i></i></span>{{ phase }}</div><button v-if="message.content && !sending" class="copy-reply icon-button" title="复制回复" aria-label="复制回复" @click="copyText(message.content)"><Copy :size="14" /></button></div></template>
            </article>
          </div>
        </div>
        <div class="composer-zone"><div class="composer-container"><div v-if="status && !status.aiConfigured" class="ai-config-note"><span><CircleAlert :size="14" />配置 DeepSeek API Key 后即可开始对话</span><button @click="settingsOpen = true">配置说明<ChevronRight :size="13" /></button></div><form class="composer" @submit.prevent="send"><textarea ref="composer" v-model="input" aria-label="向 AI 助手发送消息" placeholder="告诉我你的操作，可粘贴多行学生信息进行批量增删查改…" rows="2" maxlength="30000" :disabled="sending" @keydown="onComposerKey" @input="resizeComposer"></textarea><div class="composer-toolbar"><div class="composer-tools"><span class="model-badge"><Sparkles :size="14" />DeepSeek<span class="model-dot"></span></span><button class="composer-tool" type="button" :disabled="sending" title="上传Excel批量新增或更新" @click="openBatch('excel')"><FileSpreadsheet :size="15" /><span>Excel 导入</span></button><button class="composer-tool" type="button" :disabled="sending" title="批量文本处理" @click="openBatch('text')"><Files :size="15" /><span>批量处理</span></button></div><div class="composer-actions"><span class="keyboard-hint">{{ sending ? '正在处理中' : 'Enter 发送 · Shift + Enter 换行' }}</span><button class="send-button" type="submit" :disabled="sending || !input.trim()" aria-label="发送消息"><LoaderCircle v-if="sending" class="spin" :size="20" /><ArrowUp v-else :size="21" :stroke-width="2.2" /></button></div></div></form><p class="composer-caption"><span class="tiny-lock"><CheckCheck :size="12" /></span>数据以实际操作结果为准<span class="caption-dot">·</span>修改与删除需确认<span v-if="pendingCount" class="pending-count">{{ pendingCount }} 项待确认</span></p></div></div>
      </section>

      <section v-else class="students-area">
        <div class="page-heading"><div><div class="eyebrow">STUDENT DIRECTORY</div><h1>每一位同学，都清晰有序。</h1><p>在这里查看和管理学生信息，与 AI 助手实时同步。</p></div><div class="page-heading-actions"><button class="button" :disabled="sending" @click="openBatch('text')"><Files :size="16" />批量处理</button><button class="button" :disabled="sending" @click="openBatch('excel')"><FileSpreadsheet :size="16" />Excel 导入</button><button class="button primary" @click="openStudent()"><Plus :size="17" />添加学生</button></div></div>
        <div class="directory-summary"><div class="summary-icon"><UsersRound :size="23" /></div><div><span>学生总数</span><strong>{{ status?.databaseConnected ? status.studentCount : '—' }}<small>位同学</small></strong></div><div class="summary-divider"></div><div class="summary-note"><span class="status-dot" :class="{ offline: !status?.databaseConnected }"></span>{{ status?.databaseConnected ? '已连接本地数据库' : '数据库暂不可用' }}<small>学号唯一 · 支持同名学生</small></div><button class="summary-chat" @click="goTo('chat')"><Sparkles :size="15" />试试用一句话管理<ArrowUpRight :size="14" /></button></div>
        <div class="directory-panel"><div class="directory-title"><h2>学生列表<span>{{ total }}</span></h2><button class="icon-button" :disabled="loadingStudents" aria-label="刷新学生列表" title="刷新列表" @click="loadStudents(); refreshStatus()"><RefreshCw :size="16" :class="{ spin: loadingStudents }" /></button></div><form class="search-bar" @submit.prevent="searchStudents"><div class="search-field"><Search :size="16" /><input v-model="studentNoFilter" aria-label="按学号搜索" placeholder="输入完整学号" maxlength="32" /></div><div class="search-field"><UsersRound :size="16" /><input v-model="nameFilter" aria-label="按姓名搜索" placeholder="输入姓名关键词" maxlength="50" /></div><button class="button search-submit" :disabled="loadingStudents" type="submit"><SlidersHorizontal :size="15" />查询</button><button class="reset-search" type="button" @click="resetSearch">重置</button><div v-if="fields.length" class="custom-search"><select v-model="customSearchField" aria-label="按自定义字段查询" @change="customSearchValue = ''; customSearchOperator = 'EQ'"><option value="">更多字段条件（可选）</option><option v-for="field in fields" :key="field.key" :value="field.key">{{ field.label }}</option></select><template v-if="selectedSearchField"><select v-model="customSearchOperator" aria-label="比较方式"><option value="EQ">等于</option><option v-if="selectedSearchField.type === 'TEXT'" value="CONTAINS">包含</option><template v-if="['INTEGER','DECIMAL','DATE'].includes(selectedSearchField.type)"><option value="GT">大于</option><option value="GTE">大于等于</option><option value="LT">小于</option><option value="LTE">小于等于</option></template></select><FieldValueInput :field="selectedSearchField" :model-value="customSearchValue" @update:model-value="value => customSearchValue = value" /><span>与学号、姓名条件同时生效</span></template></div></form>
          <div v-if="listError" class="list-error"><CircleAlert :size="27" /><strong>暂时无法加载学生信息</strong><p>{{ listError }}</p><button class="button" @click="loadStudents">重新加载</button></div>
          <div v-else class="student-table-wrap" :class="{ loading: loadingStudents }"><table class="student-table"><thead><tr><th class="row-number">序号</th><th>学号</th><th>姓名</th><th v-for="field in fields" :key="field.key">{{ field.label }}</th><th class="table-actions">操作</th></tr></thead><tbody><tr v-for="(student, index) in students" :key="student.studentNo"><td class="row-number">{{ (page - 1) * pageSize + index + 1 }}</td><td><code>{{ student.studentNo }}</code></td><td><div class="student-name"><span>{{ student.name.slice(0, 1) }}</span>{{ student.name }}</div></td><td v-for="field in fields" :key="field.key" class="custom-field-cell">{{ displayValue(student.fields?.[field.key]) }}</td><td class="table-actions"><button class="table-edit" @click="openStudent(student)"><PencilLine :size="14" />编辑</button><button class="table-delete" @click="deleteTarget = student; deleteError = ''"><Trash2 :size="14" />删除</button></td></tr><tr v-if="!students.length"><td :colspan="4 + fields.length"><div class="table-empty"><span><UsersRound :size="32" :stroke-width="1.4" /></span><strong>{{ loadingStudents ? '正在读取学生信息…' : studentNoFilter || nameFilter ? '没有找到匹配的学生' : '这里即将认识第一位同学' }}</strong><p>{{ studentNoFilter || nameFilter ? '试试其他学号或姓名关键词' : '点击“添加学生”，或让 AI 助手帮你录入' }}</p><button v-if="!studentNoFilter && !nameFilter && !loadingStudents" class="button small" @click="openStudent()"><Plus :size="14" />添加第一位学生</button></div></td></tr></tbody></table></div><div class="pagination"><span>共 {{ total }} 条记录 · 每页 {{ pageSize }} 条</span><div><button class="icon-button" :disabled="page <= 1 || loadingStudents" aria-label="上一页" @click="changePage(page - 1)"><ChevronLeft :size="17" /></button><span>{{ page }} / {{ pages }}</span><button class="icon-button" :disabled="page >= pages || loadingStudents" aria-label="下一页" @click="changePage(page + 1)"><ChevronRight :size="17" /></button></div></div>
        </div><p class="directory-caption"><Database :size="13" />数据存储于本机 MySQL，AI 与人工操作共用同一份学生数据。</p>
      </section>
    </main>

    <BatchPanel v-if="batchOpen" :conversation-id="conversationId" :source="batchSource" @close="batchOpen = false" @updated="syncBatch" @notify="notify" @review="openReview" />
    <ReviewWizard v-if="activeReviewId" :conversation-id="conversationId" :review-id="activeReviewId" @close="activeReviewId = ''" @updated="syncBatch" @notify="notify" />
    <div class="toast-stack" aria-live="assertive"><div v-for="toast in toasts" :key="toast.id" class="toast" :class="toast.kind"><CircleAlert v-if="toast.kind === 'error'" :size="17" /><CircleCheck v-else :size="17" /><span>{{ toast.message }}</span><button class="icon-button" aria-label="关闭提示" @click="toasts = toasts.filter(t => t.id !== toast.id)"><X :size="14" /></button></div></div>

    <div v-if="studentDialog" class="modal-backdrop" @mousedown.self="!saving && (studentDialog = false)"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="student-dialog-title"><div class="modal-heading"><span class="modal-icon"><UserRoundPlus v-if="!editing" :size="22" /><PencilLine v-else :size="22" /></span><button class="icon-button" :disabled="saving" aria-label="关闭表单" @click="studentDialog = false"><X :size="19" /></button></div><h2 id="student-dialog-title">{{ editing ? '编辑学生信息' : '认识一位新同学' }}</h2><p>{{ editing ? '保存后，学生信息将同步更新。' : '填写学号和姓名，即可添加学生。' }}</p><form @submit.prevent="saveStudent"><label for="student-no">学号<span>*</span></label><input id="student-no" v-model="formStudentNo" class="form-input" placeholder="例如：20260001" required maxlength="32" pattern="[A-Za-z0-9_-]+" title="学号只允许英文字母、数字、下划线和连字符" autocomplete="off" /><small class="field-help">学号必须唯一，支持英文字母、数字、_ 和 -</small><label for="student-name">姓名<span>*</span></label><input id="student-name" v-model="formName" class="form-input" placeholder="请输入学生姓名" required maxlength="50" autocomplete="off" /><template v-for="field in fields" :key="field.key"><label>{{ field.label }}<span v-if="field.required">*</span></label><FieldValueInput :field="field" :model-value="formFields[field.key]" :disabled="saving" @update:model-value="value => formFields[field.key] = value" /></template><p v-if="formError" class="form-error" role="alert"><CircleAlert :size="15" />{{ formError }}</p><div class="modal-footer"><button class="button" type="button" :disabled="saving" @click="studentDialog = false">取消</button><button class="button primary" type="submit" :disabled="saving"><LoaderCircle v-if="saving" class="spin" :size="16" /><Check v-else :size="16" />{{ saving ? '保存中…' : editing ? '保存修改' : '添加学生' }}</button></div></form></section></div>
    <div v-if="deleteTarget" class="modal-backdrop" @mousedown.self="!deleting && (deleteTarget = null)"><section class="modal delete-modal" role="dialog" aria-modal="true" aria-labelledby="delete-title"><div class="modal-heading"><span class="modal-icon red"><Trash2 :size="22" /></span><button class="icon-button" :disabled="deleting" aria-label="关闭删除确认" @click="deleteTarget = null"><X :size="19" /></button></div><h2 id="delete-title">确认删除这位学生？</h2><p>请核对以下记录，删除后将从学生列表中移除。</p><div class="delete-student"><span class="student-initial">{{ deleteTarget.name.slice(0, 1) }}</span><div><strong>{{ deleteTarget.name }}</strong><code>{{ deleteTarget.studentNo }}</code></div></div><p v-if="deleteError" class="form-error" role="alert">{{ deleteError }}</p><div class="modal-footer"><button class="button" :disabled="deleting" @click="deleteTarget = null">保留记录</button><button class="button danger" :disabled="deleting" @click="removeStudent"><LoaderCircle v-if="deleting" class="spin" :size="16" /><Trash2 v-else :size="16" />确认删除</button></div></section></div>
    <div v-if="settingsOpen" class="modal-backdrop" @mousedown.self="settingsOpen = false"><section class="modal settings-modal" role="dialog" aria-modal="true" aria-labelledby="settings-title"><div class="modal-heading"><span class="modal-icon"><Settings2 :size="22" /></span><button class="icon-button" aria-label="关闭配置说明" @click="settingsOpen = false"><X :size="19" /></button></div><h2 id="settings-title">让工作区准备就绪</h2><p>配置保存在本机，API Key 仅由 Java 后端读取。</p><div class="config-status"><span><i class="status-dot" :class="{ offline: !status?.databaseConnected }"></i>MySQL 数据库</span><strong>{{ status?.databaseConnected ? '已连接' : '未连接' }}</strong></div><div class="config-status"><span><i class="status-dot" :class="{ offline: !status?.aiConfigured }"></i>DeepSeek API Key</span><strong>{{ status?.aiConfigured ? '已配置（连接尚需对话验证）' : '待配置' }}</strong></div><ol class="config-steps"><li>打开项目根目录的 <code>.env</code>，填写 <code>DEEPSEEK_API_KEY</code>。</li><li>使用自带数据库时，先运行 <code>scripts/Initialize-Database.ps1</code>；使用已有 MySQL 时，填写对应连接信息并导入建表脚本。</li><li>运行 <code>scripts/Start.ps1</code> 启动应用。修改配置后，请重新启动后端。</li></ol><div class="config-tip"><CircleHelp :size="16" /><p>学生管理页面可独立使用。会话暂存在内存中，后端重启后清空；学生数据会保留。</p></div><div class="modal-footer"><button class="button" @click="refreshStatus"><RefreshCw :size="15" />重新检查</button><button class="button primary" @click="settingsOpen = false">我知道了</button></div></section></div>
  </div>
</template>
