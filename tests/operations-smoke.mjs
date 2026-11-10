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
  const adminUsername = process.env.E2E_ADMIN_USERNAME
  const adminPassword = process.env.E2E_ADMIN_PASSWORD
  assert(adminUsername && adminPassword, 'E2E_ADMIN_USERNAME and E2E_ADMIN_PASSWORD are required')
  const login = await expectStatus('/auth/login', 200, { method: 'POST', body: JSON.stringify({ username: adminUsername, password: adminPassword }) })
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
  const candidates = rooms.data.filter((item) => item.status === 'AVAILABLE' && freeRoomIds.has(item.id) && item.roomTypeName !== roomType.name)
  assert(candidates.length > 0, 'an available room is required for room workflow smoke test')
  let room = null
  for (const candidate of candidates) {
    const maintenance = await request(`/admin/rooms/${candidate.id}/operations`, { method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' }, body: JSON.stringify({ status: 'MAINTENANCE', reason: '接口测试报修' }) })
    if (maintenance.response.status === 200) {
      room = candidate
      break
    }
    assert(maintenance.response.status === 409, `/admin/rooms/${candidate.id}/operations: expected 200 or 409, got ${maintenance.response.status}: ${JSON.stringify(maintenance.body)}`)
  }
  assert(room, 'an available room without an active assignment is required for room workflow smoke test')
  await expectStatus(`/admin/rooms/${room.id}/operations`, 200, { method: 'POST', headers: auth, body: JSON.stringify({ status: 'AVAILABLE', reason: '接口测试恢复' }) })

  const blockDate = isoDate(new Date(Date.now() + 75 * 86400000))
  await expectStatus('/admin/inventory/overrides', 200, { method: 'POST', headers: auth, body: JSON.stringify({ roomId: room.id, stayDate: blockDate, reason: '接口测试封房' }) })
  const blockedCalendar = await expectStatus(`/admin/inventory/calendar?from=${blockDate}&to=${blockDate}`, 200, { headers: auth })
  assert(blockedCalendar.data.days[0].rooms.some((item) => item.roomId === room.id && item.category === 'BLOCKED'), 'manual inventory block should appear on calendar')
  await expectStatus(`/admin/inventory/overrides?roomId=${room.id}&stayDate=${blockDate}`, 200, { method: 'DELETE', headers: auth })

  const from = new Date(Date.now() + (30 + Math.floor(Math.random() * 30)) * 86400000)
  const to = new Date(from.getTime() + 86400000)
  const iso = (date) => date.toISOString().slice(0, 10)
  const manual = await expectStatus('/admin/reservations', 200, { method: 'POST', headers: auth, body: JSON.stringify({ checkIn: iso(from), checkOut: iso(to), guests: 1, contactName: '接口人工订单', contactPhone: '13900000000', items: [{ roomTypeId: roomType.id, quantity: 1 }] }) })
  const orderNo = manual.data.orderNo
  assert(manual.data.source === 'MANUAL', 'manual reservation should be marked MANUAL')
  await expectStatus('/admin/payments/collect', 200, { method: 'POST', headers: auth, body: JSON.stringify({ orderNo, amount: Number(manual.data.total), method: 'CASH', remark: '接口测试收款' }) })
  await expectStatus('/admin/payments/adjust', 200, { method: 'POST', headers: auth, body: JSON.stringify({ orderNo, type: 'DISCOUNT', amount: 1, reason: '接口测试折扣' }) })
  const charge = await expectStatus(`/admin/reservations/${orderNo}/charges`, 200, { method: 'POST', headers: auth, body: JSON.stringify({ type: 'TEST', amount: 2, description: '接口测试费用' }) })
  const detail = await expectStatus(`/admin/reservations/${orderNo}`, 200, { headers: auth })
  assert(detail.data.paymentStatus === 'PAID', 'manual order should be paid after collection')
  assert(detail.data.adjustments?.some((item) => item.type === 'DISCOUNT'), 'settlement adjustment should be recorded')
  const overview = await expectStatus('/admin/statistics/overview', 200, { headers: auth })
  assert('grossRevenue' in overview.data && 'discounts' in overview.data && 'outstandingAmount' in overview.data, 'statistics should expose net settlement fields')
  const chargeId = detail.data.charges.find((item) => item.type === 'TEST')?.id
  assert(chargeId, 'test charge should be created')
  await expectStatus(`/admin/reservations/${orderNo}/charges/${chargeId}/void`, 200, { method: 'POST', headers: auth, body: JSON.stringify({ reason: '接口测试冲正' }) })
  const availableRoom = (await expectStatus('/admin/rooms', 200, { headers: auth })).data.find((item) => item.status === 'AVAILABLE' && item.roomTypeName === roomType.name)
  assert(availableRoom, 'a room of the selected type is required for assignment')
  await expectStatus(`/admin/reservations/${orderNo}/assign-room`, 200, { method: 'POST', headers: auth, body: JSON.stringify({ roomIds: [availableRoom.id] }) })

  const calendar = await expectStatus(`/admin/inventory/calendar?from=${iso(from)}&to=${iso(to)}`, 200, { headers: auth })
  assert(calendar.data.days?.[0]?.summary && 'maintenance' in calendar.data.days[0].summary, 'calendar should expose all room states')
  const audit = await expectStatus('/admin/audit-logs?limit=20', 200, { headers: auth })
  assert(audit.data.some((item) => item.action === 'PROPERTY_UPDATE'), 'property update should be audited')
  assert(audit.data.some((item) => item.action === 'RESERVATION_CREATE_MANUAL'), 'manual reservation should be audited')
  const tasks = await expectStatus('/admin/room-tasks?status=OPEN', 200, { headers: auth })
  const maintenanceTask = tasks.data.find((item) => item.roomId === room.id && item.taskType === 'MAINTENANCE')
  assert(maintenanceTask, 'maintenance operation should create a room task')
  await expectStatus(`/admin/room-tasks/${maintenanceTask.id}`, 200, { method: 'PATCH', headers: auth, body: JSON.stringify({ status: 'DONE', reason: '接口测试完成维修' }) })

  console.log(JSON.stringify({ ok: true, checks: ['property-settings', 'room-type-media', 'room-workflow', 'inventory-override', 'manual-order', 'collection', 'settlement-adjustment', 'statistics-settlement-fields', 'charge-void', 'assignment', 'housekeeping-task', 'inventory-calendar', 'audit'] }))
} catch (error) {
  console.error(error.stack || error)
  process.exitCode = 1
}
