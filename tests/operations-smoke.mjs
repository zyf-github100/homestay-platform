const base = process.env.API_BASE_URL || 'http://localhost:8080/api/v1'
const prefix = process.env.TEST_PREFIX || `ops1794084302655`

async function request(path, options = {}) {
  const response = await fetch(`${base}${path}`, { ...options, headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) } })
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

try {
  const login = await expectStatus('/auth/login', 200, { method: 'POST', body: JSON.stringify({ username: process.env.E2E_ADMIN_USERNAME || 'admin', password: process.env.E2E_ADMIN_PASSWORD || 'admin123' }) })
  const auth = { Authorization: `Bearer ${login.data.token}` }

  const property = await expectStatus('/admin/property', 200, { headers: auth })
  const settings = { ...property.data, checkInTime: String(property.data.checkInTime || '14:00').slice(0, 5), checkOutTime: String(property.data.checkOutTime || '12:00').slice(0, 5) }
  await expectStatus('/admin/property', 200, { method: 'PUT', headers: auth, body: JSON.stringify(settings) })

  const roomTypes = await expectStatus('/admin/room-types', 200, { headers: auth })
  assert(roomTypes.data?.length > 0, 'admin room-types should be seeded')
  const roomType = roomTypes.data[0]
  const image = await expectStatus(`/admin/room-types/${roomType.id}/images`, 200, { method: 'POST', headers: auth, body: JSON.stringify({ imageUrl: `https://example.test/${prefix}.jpg`, altText: '接口测试图片', sortOrder: 99 }) })
  assert(image.data.images.some((item) => item.altText === '接口测试图片'), 'room type image should be created')
  const facility = await expectStatus(`/admin/room-types/${roomType.id}/facilities`, 200, { method: 'POST', headers: auth, body: JSON.stringify({ name: `接口测试设施-${prefix}`, sortOrder: 99 }) })
  const facilityId = facility.data.facilities.find((item) => item.name.includes(prefix))?.id
  assert(facilityId, 'room type facility should be created')
  const imageId = image.data.images.find((item) => item.altText === '接口测试图片')?.id
  await expectStatus(`/admin/room-types/${roomType.id}/images/${imageId}`, 200, { method: 'DELETE', headers: auth })
  await expectStatus(`/admin/room-types/${roomType.id}/facilities/${facilityId}`, 200, { method: 'DELETE', headers: auth })

  const rooms = await expectStatus('/admin/rooms', 200, { headers: auth })
  const today = new Date()
  const isoDate = (date) => date.toISOString().slice(0, 10)
  const availabilityCalendar = await expectStatus(`/admin/inventory/calendar?from=${isoDate(today)}&to=${isoDate(new Date(today.getTime() + 60 * 86400000))}`, 200, { headers: auth })
  const freeRoomIds = new Set(availabilityCalendar.data.days.flatMap((day) => day.rooms.filter((item) => item.category === 'AVAILABLE').map((item) => item.roomId)))
  const room = rooms.data.find((item) => item.status === 'AVAILABLE' && freeRoomIds.has(item.id))
  assert(room, 'an available room is required for room workflow smoke test')
  await expectStatus(`/admin/rooms/${room.id}/operations`, 200, { method: 'POST', headers: auth, body: JSON.stringify({ status: 'MAINTENANCE', reason: '接口测试报修' }) })
  await expectStatus(`/admin/rooms/${room.id}/operations`, 200, { method: 'POST', headers: auth, body: JSON.stringify({ status: 'AVAILABLE', reason: '接口测试恢复' }) })

  const from = new Date(Date.now() + 1000 * 86400000 + Math.floor(Math.random() * 100000) * 86400000)
  const to = new Date(from.getTime() + 86400000)
  const iso = (date) => date.toISOString().slice(0, 10)
  const manual = await expectStatus('/admin/reservations', 200, { method: 'POST', headers: auth, body: JSON.stringify({ checkIn: iso(from), checkOut: iso(to), guests: 1, contactName: '接口人工订单', contactPhone: '13900000000', items: [{ roomTypeId: roomType.id, quantity: 1 }] }) })
  const orderNo = manual.data.orderNo
  assert(manual.data.source === 'MANUAL', 'manual reservation should be marked MANUAL')
  await expectStatus('/admin/payments/collect', 200, { method: 'POST', headers: auth, body: JSON.stringify({ orderNo, amount: Number(manual.data.total), method: 'CASH', remark: '接口测试收款' }) })
  const detail = await expectStatus(`/admin/reservations/${orderNo}`, 200, { headers: auth })
  assert(detail.data.paymentStatus === 'PAID', 'manual order should be paid after collection')
  const availableRoom = (await expectStatus('/admin/rooms', 200, { headers: auth })).data.find((item) => item.status === 'AVAILABLE' && item.roomTypeName === roomType.name)
  assert(availableRoom, 'a room of the selected type is required for assignment')
  await expectStatus(`/admin/reservations/${orderNo}/assign-room`, 200, { method: 'POST', headers: auth, body: JSON.stringify({ roomIds: [availableRoom.id] }) })

  const calendar = await expectStatus(`/admin/inventory/calendar?from=${iso(from)}&to=${iso(to)}`, 200, { headers: auth })
  assert(calendar.data.days?.[0]?.summary && 'maintenance' in calendar.data.days[0].summary, 'calendar should expose all room states')
  const audit = await expectStatus('/admin/audit-logs?limit=20', 200, { headers: auth })
  assert(audit.data.some((item) => item.action === 'PROPERTY_UPDATE'), 'property update should be audited')
  assert(audit.data.some((item) => item.action === 'RESERVATION_CREATE_MANUAL'), 'manual reservation should be audited')

  console.log(JSON.stringify({ ok: true, checks: ['property-settings', 'room-type-media', 'room-workflow', 'manual-order', 'collection', 'assignment', 'inventory-calendar', 'audit'] }))
} catch (error) {
  console.error(error.stack || error)
  process.exitCode = 1
}
