export interface StudentField { key: string; label: string; type: 'TEXT' | 'INTEGER' | 'DECIMAL' | 'DATE' | 'BOOLEAN'; required: boolean; options?: string[] }
export interface Student { studentNo: string; name: string; fields?: Record<string, unknown> }
export interface StudentPage { items: Student[]; total: number; page: number; pageSize: number }
export interface BatchRow { row: number; studentNo?: string; name?: string; newStudentNo?: string; before?: Student; after?: Student; operation: string; status: string; code: string; message: string }
export interface BatchReport { mode: string; total: number; successCount: number; failureCount: number; readyCount: number; unchangedCount: number; rows: BatchRow[] }
export interface Action { id: string; type: string; before?: Student; after?: Student; expiresAt: string; status: string; batch?: BatchReport }
export interface ReviewRef { reviewId: string; title: string; stage: string; issueCount: number; schemaChanged?: boolean }
export interface Operation { tool: string; success: boolean; code: string; message: string; data?: Student | StudentPage | BatchReport | ReviewRef; action?: Action; mutated: boolean }
export interface ProcessingStep { id: string; title: string; detail: string; status: string; time: string }
export interface Message { id: string; role: 'user' | 'assistant'; content: string; results: Operation[]; steps?: ProcessingStep[] }
export interface ReviewIssue { code: string; message: string; row?: number; column?: number; suggestion?: string; choices: string[] }
export interface ReviewColumn { index: number; label: string; target: string; newLabel: string; newType: string; samples: string[]; suggestions: { column: number; target: string; reason: string; source: string }[] }
export interface ReviewRow { number: number; values: Record<number, string>; excluded: boolean; issues: ReviewIssue[]; candidates: Student[] }
export interface ReviewView { id: string; revision: number; purpose: string; parentId?: string; mode: string; stage: string; message: string; expiresAt: string; source: string; sheetIndex: number; headerRow: number; sheets: {index: number; name: string}[]; columns: ReviewColumn[]; rows: ReviewRow[]; issues: ReviewIssue[]; fields: StudentField[]; proposedFields: StudentField[]; existingStudents: Student[]; backfill: Record<string, Record<string, unknown>>; notes: string[]; preview?: BatchReport; result?: Operation }
export interface Conversation { id: string; title: string; updatedAt: string }
export interface Status { aiConfigured: boolean; model: string; databaseConnected: boolean; studentCount?: number; databaseMessage?: string }
export class ApiError extends Error { constructor(message: string, public code: string, public status = 0) { super(message) } }
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try { response = await fetch('/api' + path, { ...options, headers: { ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }), ...options.headers } }) }
  catch { throw new ApiError('无法连接后端，请确认本地服务已启动', 'NETWORK_ERROR') }
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.success) throw new ApiError(result?.message || '请求失败，请稍后重试', result?.code || 'REQUEST_FAILED', response.status)
  return result.data as T
}
export async function streamChat(body: object, receive: (event: string, data: any) => void) {
  const response = await fetch('/api/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  if (!response.ok) {
    const result = await response.json().catch(() => null)
    throw new ApiError(result?.message || '会话请求失败', result?.code || 'CHAT_ERROR', response.status)
  }
  if (!response.body) throw new Error('浏览器不支持流式响应')
  const reader = response.body.getReader(), decoder = new TextDecoder()
  let buffer = '', finished = false
  function consume() {
    let index: number
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, index); buffer = buffer.slice(index + 2)
      const event = block.split('\n').find(line => line.startsWith('event:'))?.slice(6).trim() || 'message'
      const raw = block.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
      if (raw) { const data = JSON.parse(raw); if (event === 'done') finished = true; receive(event, data) }
    }
  }
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n'); consume()
    }
    buffer += decoder.decode(); consume()
    if (!finished) throw new Error('连接中断，请重新打开会话查看结果，并刷新学生列表核实；请勿重复提交写操作')
  } finally { reader.releaseLock() }
}
