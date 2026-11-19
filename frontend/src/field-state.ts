import { ref } from 'vue'
import { api, type StudentField } from './api'
export const studentFields = ref<StudentField[]>([])
export function fieldOptions(field: StudentField) { return field.options?.length ? field.options : ['性别','gender','sex'].includes(field.label.normalize('NFKC').trim().toLowerCase()) ? ['男','女'] : [] }
export async function refreshFields() { try { studentFields.value = await api<StudentField[]>('/fields') } catch { /* Connection status is shown by the workspace. */ } }
export function displayValue(value: unknown) { return value === true || value === 'true' ? '是' : value === false || value === 'false' ? '否' : value == null || value === '' ? '—' : String(value) }
export const typeLabels: Record<string, string> = { TEXT: '文本', INTEGER: '整数', DECIMAL: '小数', DATE: '日期', BOOLEAN: '是／否' }
