const base = process.env.API_BASE_URL || 'http://localhost:8080/api/v1'
const prefix = process.env.TEST_PREFIX || `smoke${Date.now()}`
const isoDate = (offsetDays) => {
  const date = new Date()
  date.setUTCDate(date.getUTCDate() + offsetDays)
  return date.toISOString().slice(0, 10)
}

async function request(path, options = {}) {
  const response = await fetch(`${base}${path}`, options)
  const text = await response.text()
  let body
  try { body = JSON.parse(text) } catch { body = text }
  return { response, body }
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function expectStatus(path, status, options = {}) {
  const result = await request(path, options)
  assert(result.response.status === status, `${path}: expected ${status}, got ${result.response.status}: ${JSON.stringify(result.body)}`)
  return result.body
}

const json = (value) => ({ 'Content-Type': 'application/json', ...(value || {}) })

try {
  const health = await expectStatus('/health', 200)
  assert(health.status === 'ok' && health.redis === 'ok' && health.database === 'ok', 'health should verify database and redis')
  const cors = await request('/room-types', { method: 'OPTIONS', headers: { Origin: 'http://localhost:5173', 'Access-Control-Request-Method': 'GET' } })
  assert(cors.response.ok, `CORS preflight failed: ${cors.response.status}`)
  assert(cors.response.headers.get('access-control-allow-origin') === 'http://localhost:5173', 'CORS origin header missing')

  const rooms = await expectStatus('/room-types', 200)
  assert(Array.isArray(rooms.data) && rooms.data.length > 0, 'room-types should return seeded rooms')
  const roomTypeId = rooms.data[0].id
  const orderBody = JSON.stringify({ checkIn: isoDate(30), checkOut: isoDate(32), guests: 2, contactName: '接口测试', contactPhone: '13600000000', items: [{ roomTypeId, quantity: 1 }] })
  await expectStatus('/reservations', 401, { method: 'POST', headers: json(), body: orderBody })

  const username = `${prefix}user`
  await expectStatus('/auth/register', 200, { method: 'POST', headers: json(), body: JSON.stringify({ username, phone: `136${String(Date.now()).slice(-8)}`, password: 'Pass1234', displayName: '接口测试用户' }) })
  const login = await expectStatus('/auth/login', 200, { method: 'POST', headers: json(), body: JSON.stringify({ username, password: 'Pass1234' }) })
  const auth = { Authorization: `Bearer ${login.data.token}` }
  const guest = await expectStatus('/guests', 200, { method: 'POST', headers: json(auth), body: JSON.stringify({ name: '张三', phone: '13800000000', idType: 'ID_CARD', idNo: '110101199001011234' }) })
  assert(guest.data.idNoMasked === '1101**********1234', 'guest ID should be masked')
  const created = await expectStatus('/reservations', 200, { method: 'POST', headers: { ...json(auth), 'Idempotency-Key': `${prefix}-order` }, body: orderBody })
  const orderNo = created.data.orderNo
  const createdNotifications = await expectStatus('/notifications?page=1&size=20', 200, { headers: auth })
  assert(createdNotifications.data.data.some((item) => item.notificationType === 'ORDER_CREATED' && item.relatedOrderNo === orderNo), 'order creation should notify the user')
  assert(Number(createdNotifications.data.unreadCount) > 0, 'new order notification should be unread')
  const ownOrders = await expectStatus('/reservations', 200, { headers: auth })
  assert(ownOrders.data.some((item) => item.orderNo === orderNo), 'user should see own order')

  const otherUsername = `${prefix}other`
  await expectStatus('/auth/register', 200, { method: 'POST', headers: json(), body: JSON.stringify({ username: otherUsername, phone: `137${String(Date.now()).slice(-8)}`, password: 'Pass1234', displayName: '其他用户' }) })
  const otherLogin = await expectStatus('/auth/login', 200, { method: 'POST', headers: json(), body: JSON.stringify({ username: otherUsername, password: 'Pass1234' }) })
  await expectStatus(`/reservations/${orderNo}`, 404, { headers: { Authorization: `Bearer ${otherLogin.data.token}` } })
  await expectStatus('/admin/room-types', 403, { headers: auth })

  const payment = await expectStatus(`/reservations/${orderNo}/payments`, 200, { method: 'POST', headers: auth })
  await expectStatus(`/payments/${payment.data.paymentNo}/simulate`, 200, { method: 'POST', headers: json(auth), body: JSON.stringify({ result: 'SUCCESS' }) })
  const paidNotifications = await expectStatus('/notifications?page=1&size=20', 200, { headers: auth })
  assert(paidNotifications.data.data.some((item) => item.notificationType === 'PAYMENT_SUCCESS' && item.relatedOrderNo === orderNo), 'payment success should notify the user')
  const firstNotification = paidNotifications.data.data[0]
  if (firstNotification) await expectStatus(`/notifications/${firstNotification.id}/read`, 200, { method: 'PATCH', headers: auth })
  await expectStatus(`/reservations/${orderNo}/cancel`, 200, { method: 'POST', headers: auth })
  await expectStatus('/notifications/read-all', 200, { method: 'POST', headers: auth })
  await expectStatus(`/guests/${guest.data.id}`, 200, { method: 'PUT', headers: json(auth), body: JSON.stringify({ name: '张三更新', phone: '13900000000', idType: 'ID_CARD', remark: '保留原证件' }) })
  await expectStatus(`/guests/${guest.data.id}`, 200, { method: 'DELETE', headers: auth })

  console.log(JSON.stringify({ ok: true, checks: ['health', 'cors', 'room-types', 'unauthenticated-order', 'guest-masking', 'guest-crud', 'order-isolation', 'admin-rbac', 'payment', 'notifications', 'cancel'] }))
} catch (error) {
  console.error(error.stack || error)
  process.exitCode = 1
}
