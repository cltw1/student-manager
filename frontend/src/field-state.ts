import { ref } from 'vue'
import { api, type StudentField } from './api'
export const studentFields = ref<StudentField[]>([])
export async function refreshFields() { try { studentFields.value = await api<StudentField[]>('/fields') } catch { /* Connection status is shown by the workspace. */ } }
export function displayValue(value: unknown) { return value === true || value === 'true' ? '是' : value === false || value === 'false' ? '否' : value == null || value === '' ? '—' : String(value) }
export const typeLabels: Record<string, string> = { TEXT: '文本', INTEGER: '整数', DECIMAL: '小数', DATE: '日期', BOOLEAN: '是／否' }
