import assert from 'node:assert/strict'

const base = process.env.TEST_BASE_URL || 'http://127.0.0.1:8080'
let assertions = 0
const createdIds = new Set()
let requiredFields = {}
async function call(method, path, body, expected = 200) {
  if (body && ['POST','PUT'].includes(method) && /^\/students(?:\/[^?]+)?$/.test(path) && !body.fields) body = { ...body, fields: requiredFields }
  const response = await fetch(base + '/api' + path, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
  assert.equal(response.status, expected, `${method} ${path}: unexpected HTTP status`); assertions++
  const result = await response.json()
  assert.equal(result.success, expected < 400); assertions++
  if (expected < 400 && result.data?.studentNo) {
    if (method === 'POST' && path === '/students') createdIds.add(result.data.studentNo)
    if (method === 'PUT') { createdIds.delete(path.split('/')[2]); createdIds.add(result.data.studentNo) }
    if (method === 'DELETE') createdIds.delete(result.data.studentNo)
  }
  return result
}
const prefix = 'TEST_' + Date.now(), ids = []
let conversationId = ''
try {
  const status = (await call('GET', '/status')).data
  assert.equal(status.databaseConnected, true, 'A real database must be connected'); assertions++
  const fields = (await call('GET', '/fields')).data
  requiredFields = Object.fromEntries(fields.map(field => [field.key, field.options?.[0] ?? ({TEXT:'联调字段',INTEGER:'0',DECIMAL:'0',DATE:'2026-01-01',BOOLEAN:false})[field.type]]))
  for (let attempt = 0; ids.length < 3 && attempt < 30; attempt++) {
    const id = String(9000000 + Math.floor(Math.random() * 1000000))
    if (ids.includes(id)) continue
    const existing = await fetch(base + '/api/students/' + id)
    if (existing.status === 404) ids.push(id)
    else if (!existing.ok) throw new Error('Cannot reserve an unused seven-digit test student number')
  }
  assert.equal(ids.length, 3, 'Could not reserve three unused test identifiers')
  await call('POST', '/students', { studentNo: ids[0], name: '联调学生甲' })
  await call('POST', '/students', { studentNo: ids[1], name: '联调学生甲' })
  await call('POST', '/students', { studentNo: ids[0], name: '重复学号' }, 409)
  await call('POST', '/students', { studentNo: prefix, name: '  ' }, 400)
  const found = (await call('GET', '/students?studentNo=' + ids[0])).data
  assert.equal(found.items[0].name, '联调学生甲'); assertions++
  await call('PUT', '/students/' + ids[0], { studentNo: ids[1], name: '冲突修改', expectedName: '联调学生甲' }, 409)
  assert.equal((await call('GET', '/students/' + ids[0])).data.name, '联调学生甲'); assertions++
  await call('PUT', '/students/' + ids[0], { studentNo: ids[2], name: '联调学生乙', expectedName: '联调学生甲' })
  await call('GET', '/students/' + ids[0], undefined, 404)
  await call('DELETE', '/students/' + ids[2] + '?expectedName=' + encodeURIComponent('旧姓名'), undefined, 409)
  await call('DELETE', '/students/' + ids[2] + '?expectedName=' + encodeURIComponent('联调学生乙'))
  await call('DELETE', '/students/' + ids[2], undefined, 404)
  await call('GET', '/students?page=0', undefined, 400)
  if (!status.aiConfigured) {
    conversationId = (await call('POST', '/conversations')).data.id
    const response = await fetch(base + '/api/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ conversationId, requestId: 'smoke-missing-key', message: '查询所有学生' }) })
    assert.equal(response.status, 200); assertions++
    const events = await response.text()
    assert.ok(events.includes('AI_NOT_CONFIGURED'), 'Missing key must receive explicit feedback'); assertions++
    assert.ok(events.includes('event:done'), 'Chat must finish after the error'); assertions++
  }
  console.log(`PASS: ${assertions} HTTP/database assertions. Temporary test records will be removed.`)
} finally {
  for (const id of createdIds) await fetch(base + '/api/students/' + id, { method: 'DELETE' }).catch(() => {})
  if (conversationId) await fetch(base + '/api/conversations/' + conversationId, { method: 'DELETE' }).catch(() => {})
}
