<script setup>
import { nextTick, onMounted, ref } from 'vue'

const api = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
const token = ref(localStorage.getItem('homestay_admin_token') || '')
const username = ref('admin')
const password = ref('admin123')
const error = ref('')
const active = ref('概览')
const loading = ref(false)
const roomTypes = ref([])
const rooms = ref([])
const reservations = ref([])
const guests = ref([])
const stats = ref({})
const trends = ref([])
const propertySettings = ref(null)
const propertyForm = ref({ name: '', description: '', address: '', phone: '', checkInTime: '14:00', checkOutTime: '12:00', checkInRule: '', checkOutRule: '', status: 'OPEN' })
const calendarFrom = ref(new Date().toISOString().slice(0, 10))
const calendarData = ref(null)
const roomTypeDialog = ref(null)
const roomDialog = ref(null)
const orderDialog = ref(null)
const propertyDialog = ref(null)
const mediaDialog = ref(null)
const manualOrderDialog = ref(null)
const editingRoomType = ref(null)
const editingRoom = ref(null)
const selectedOrder = ref(null)
const selectedMediaRoomType = ref(null)
const selectedRoomIds = ref([])
const actionError = ref('')
const roomTypeForm = ref({ name: '', description: '', bedType: '', area: 0, maxGuests: 2, price: 0, coverImage: '' })
const roomForm = ref({ roomTypeId: '', roomNo: '', floorNo: '', status: 'AVAILABLE', remark: '' })
const mediaForm = ref({ imageUrl: '', altText: '', facilityName: '' })
const manualOrderForm = ref({ checkIn: new Date().toISOString().slice(0, 10), checkOut: new Date(Date.now() + 86400000).toISOString().slice(0, 10), guests: 1, contactName: '', contactPhone: '', roomTypeId: '', quantity: 1 })
const changeForm = ref({ newRoomId: '', reason: '', feeDifference: 0 })
const collectForm = ref({ amount: '', method: 'CASH', remark: '' })
const pages = ['概览', '民宿设置', '房型管理', '实体房间', '房态日历', '订单管理', '房客资料']

function headers(extra = {}) { return { Authorization: `Bearer ${token.value}`, ...extra } }
async function request(path, options = {}) {
  const response = await fetch(`${api}${path}`, { ...options, headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...headers(options.headers || {}) } })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(payload.message || payload.detail || payload.error || '请求失败')
  return payload
}
function openDialog(dialog) { nextTick(() => { if (dialog.value && !dialog.value.open) dialog.value.showModal() }) }
function closeDialog(dialog) { const target = dialog?.value || dialog; if (target?.open) target.close() }
function logout() { localStorage.removeItem('homestay_admin_token'); token.value = ''; error.value = '' }
function fail(message) { error.value = message; actionError.value = message }

async function login() {
  error.value = ''
  try {
    const payload = await request('/auth/login', { method: 'POST', headers: {}, body: JSON.stringify({ username: username.value, password: password.value }) })
    token.value = payload.data.token
    localStorage.setItem('homestay_admin_token', token.value)
    await selectPage('概览')
  } catch (e) { error.value = e.message }
}

async function selectPage(page) {
  active.value = page; actionError.value = ''
  if (!token.value) return
  loading.value = true
  try {
    if (page === '概览') await loadOverview()
    if (page === '民宿设置') await loadProperty()
    if (page === '房型管理') await loadRoomTypes()
    if (page === '实体房间') { await loadRoomTypes(); await loadRooms() }
    if (page === '房态日历') { await loadRooms(); await loadCalendar() }
    if (page === '订单管理') await loadReservations()
    if (page === '房客资料') await loadGuests()
  } catch (e) { fail(e.message) } finally { loading.value = false }
}

async function loadOverview() {
  const [overview, trend] = await Promise.all([request('/admin/statistics/overview'), request('/admin/statistics/trends')])
  stats.value = overview.data || {}; trends.value = trend.data || []
}
async function loadRoomTypes() { roomTypes.value = (await request('/admin/room-types')).data || [] }
async function loadRooms() { rooms.value = (await request('/admin/rooms')).data || [] }
async function loadReservations() { reservations.value = (await request('/admin/reservations')).data || [] }
async function loadGuests() { guests.value = (await request('/admin/guests')).data || [] }
async function loadProperty() {
  propertySettings.value = (await request('/admin/property')).data
  propertyForm.value = { ...propertyForm.value, ...propertySettings.value, checkInTime: String(propertySettings.value.checkInTime || '14:00').slice(0, 5), checkOutTime: String(propertySettings.value.checkOutTime || '12:00').slice(0, 5) }
}
async function saveProperty() {
  try { await request('/admin/property', { method: 'PUT', body: JSON.stringify(propertyForm.value) }); await loadProperty(); actionError.value = '' } catch (e) { actionError.value = e.message }
}
async function loadCalendar() { calendarData.value = (await request(`/admin/inventory/calendar?from=${calendarFrom.value}`)).data || null }

function newRoomType() { editingRoomType.value = null; roomTypeForm.value = { name: '', description: '', bedType: '', area: 0, maxGuests: 2, price: 0, coverImage: '' }; openDialog(roomTypeDialog) }
function editRoomType(room) { editingRoomType.value = room; roomTypeForm.value = { name: room.name, description: room.description || '', bedType: room.bedType || '', area: room.area || 0, maxGuests: room.maxGuests || 2, price: room.price || 0, coverImage: room.coverImage || '' }; openDialog(roomTypeDialog) }
async function saveRoomType() {
  try { await request(editingRoomType.value ? `/admin/room-types/${editingRoomType.value.id}` : '/admin/room-types', { method: editingRoomType.value ? 'PUT' : 'POST', body: JSON.stringify(roomTypeForm.value) }); closeDialog(roomTypeDialog); await loadRoomTypes() } catch (e) { actionError.value = e.message }
}
async function openMedia(roomType) {
  try { selectedMediaRoomType.value = roomType; const data = (await request(`/admin/room-types/${roomType.id}/media`)).data; selectedMediaRoomType.value = { ...roomType, ...data }; mediaForm.value = { imageUrl: '', altText: '', facilityName: '' }; actionError.value = ''; openDialog(mediaDialog) } catch (e) { actionError.value = e.message }
}
async function addMedia(kind) {
  try {
    const roomTypeId = selectedMediaRoomType.value.id
    const path = kind === 'image' ? `/admin/room-types/${roomTypeId}/images` : `/admin/room-types/${roomTypeId}/facilities`
    const body = kind === 'image' ? { imageUrl: mediaForm.value.imageUrl, altText: mediaForm.value.altText, sortOrder: 0 } : { name: mediaForm.value.facilityName, sortOrder: 0 }
    const data = (await request(path, { method: 'POST', body: JSON.stringify(body) })).data
    selectedMediaRoomType.value = { ...selectedMediaRoomType.value, ...data }; mediaForm.value = { ...mediaForm.value, imageUrl: '', altText: '', facilityName: '' }; await loadRoomTypes()
  } catch (e) { actionError.value = e.message }
}
async function deleteMedia(kind, id) {
  try { const roomTypeId = selectedMediaRoomType.value.id; const suffix = kind === 'image' ? 'images' : 'facilities'; const data = (await request(`/admin/room-types/${roomTypeId}/${suffix}/${id}`, { method: 'DELETE' })).data; selectedMediaRoomType.value = { ...selectedMediaRoomType.value, ...data }; await loadRoomTypes() } catch (e) { actionError.value = e.message }
}
async function toggleRoomType(room) {
  try { await request(`/admin/room-types/${room.id}/status?status=${room.status === 'PUBLISHED' ? 'DRAFT' : 'PUBLISHED'}`, { method: 'PATCH' }); await loadRoomTypes() } catch (e) { actionError.value = e.message }
}
function newRoom() { editingRoom.value = null; roomForm.value = { roomTypeId: roomTypes.value[0]?.id || '', roomNo: '', floorNo: '', status: 'AVAILABLE', remark: '' }; openDialog(roomDialog) }
function editRoom(room) { editingRoom.value = room; roomForm.value = { roomTypeId: room.roomTypeId || '', roomNo: room.roomNo, floorNo: room.floorNo || '', status: room.status, remark: room.remark || '' }; openDialog(roomDialog) }
async function saveRoom() {
  try { await request(editingRoom.value ? `/admin/rooms/${editingRoom.value.id}` : '/admin/rooms', { method: editingRoom.value ? 'PUT' : 'POST', body: JSON.stringify(roomForm.value) }); closeDialog(roomDialog); await loadRooms() } catch (e) { actionError.value = e.message }
}
async function setRoomStatus(room, status) {
  try { await request(`/admin/rooms/${room.id}/operations`, { method: 'POST', body: JSON.stringify({ status, reason: `管理端标记为${roomStatusLabel(status)}` }) }); await loadRooms(); if (active.value === '房态日历') await loadCalendar() } catch (e) { actionError.value = e.message }
}
async function createManualOrder() {
  try {
    const payload = { checkIn: manualOrderForm.value.checkIn, checkOut: manualOrderForm.value.checkOut, guests: Number(manualOrderForm.value.guests), contactName: manualOrderForm.value.contactName, contactPhone: manualOrderForm.value.contactPhone, items: [{ roomTypeId: Number(manualOrderForm.value.roomTypeId), quantity: Number(manualOrderForm.value.quantity) }] }
    const created = await request('/admin/reservations', { method: 'POST', body: JSON.stringify(payload) }); closeDialog(manualOrderDialog); manualOrderForm.value = { ...manualOrderForm.value, contactName: '', contactPhone: '' }; await loadReservations(); const summary = created.data?.orderNo ? `人工订单 ${created.data.orderNo} 已创建` : '人工订单已创建'; actionError.value = summary
  } catch (e) { actionError.value = e.message }
}
async function openManualOrder() {
  try { await loadRoomTypes(); manualOrderForm.value.roomTypeId = manualOrderForm.value.roomTypeId || roomTypes.value[0]?.id || ''; openDialog(manualOrderDialog) } catch (e) { actionError.value = e.message }
}
async function openOrder(order) {
  try { selectedOrder.value = (await request(`/admin/reservations/${order.orderNo}`)).data; selectedRoomIds.value = []; changeForm.value = { newRoomId: '', reason: '', feeDifference: 0 }; collectForm.value = { amount: '', method: 'CASH', remark: '' }; actionError.value = ''; openDialog(orderDialog) } catch (e) { actionError.value = e.message }
}
async function assignRooms() {
  try { await request(`/admin/reservations/${selectedOrder.value.orderNo}/assign-room`, { method: 'POST', body: JSON.stringify({ roomIds: selectedRoomIds.value.map(Number) }) }); await refreshOrder() } catch (e) { actionError.value = e.message }
}
async function checkIn() { try { await request(`/admin/reservations/${selectedOrder.value.orderNo}/check-in`, { method: 'POST', body: '{}' }); await refreshOrder() } catch (e) { actionError.value = e.message } }
async function checkOut() { try { await request(`/admin/reservations/${selectedOrder.value.orderNo}/check-out`, { method: 'POST', body: '{}' }); await refreshOrder() } catch (e) { actionError.value = e.message } }
async function changeRoom() {
  const current = selectedOrder.value.rooms?.find((room) => room.status === 'CHECKED_IN')
  if (!current) { actionError.value = '当前没有可换的入住记录'; return }
  try { await request(`/admin/reservations/${selectedOrder.value.orderNo}/change-room`, { method: 'POST', body: JSON.stringify({ reservationRoomId: current.reservationRoomId, newRoomId: Number(changeForm.value.newRoomId), reason: changeForm.value.reason, feeDifference: Number(changeForm.value.feeDifference || 0) }) }); await refreshOrder() } catch (e) { actionError.value = e.message }
}
async function collectPayment() {
  try { await request('/admin/payments/collect', { method: 'POST', body: JSON.stringify({ orderNo: selectedOrder.value.orderNo, amount: Number(collectForm.value.amount), method: collectForm.value.method, remark: collectForm.value.remark }) }); await refreshOrder() } catch (e) { actionError.value = e.message }
}
async function refreshOrder() { selectedOrder.value = (await request(`/admin/reservations/${selectedOrder.value.orderNo}`)).data; await loadReservations() }
function statusLabel(status) { return { PENDING_PAYMENT: '待支付', CONFIRMED: '已确认', CHECKED_IN: '入住中', PARTIALLY_CHECKED_IN: '部分入住', PARTIALLY_CHECKED_OUT: '部分退房', COMPLETED: '已完成', CANCELLED: '已取消' }[status] || status }
function roomStatusLabel(status) { return { AVAILABLE: '可售', RESERVED: '预订', OCCUPIED: '入住', MAINTENANCE: '维修', DISABLED: '停用', DIRTY: '待清洁' }[status] || status }
function money(value) { return `¥${Number(value || 0).toLocaleString()}` }
onMounted(() => { if (token.value) selectPage('概览') })
</script>

<template>
  <div v-if="!token" class="login-page"><div class="login-card"><div class="logo">栖</div><p class="kicker">QIYE OPERATIONS</p><h1>欢迎回来</h1><p class="sub">管理房源、订单与每一段入住时光。</p><form @submit.prevent="login"><label>账号<input v-model="username" autocomplete="username" required></label><label>密码<input v-model="password" type="password" autocomplete="current-password" required></label><button type="submit">进入管理台 <span>→</span></button><p v-if="error" class="error" role="alert">{{ error }}</p></form></div></div>
  <div v-else class="admin-shell"><aside class="sidebar"><div class="brand"><span>栖</span><div><strong>栖野</strong><small>OPERATIONS</small></div></div><nav><button v-for="item in pages" :key="item" type="button" :class="{ active: active === item }" @click="selectPage(item)">{{ item }}</button></nav><button class="logout" type="button" @click="logout">退出登录 ↗</button></aside><main class="content"><header><div><p class="kicker">QIYE OPERATIONS</p><h1>{{ active }}</h1></div><span class="avatar">{{ username.slice(0, 1).toUpperCase() }}</span></header><p v-if="actionError" class="error banner" role="alert">{{ actionError }}</p>
    <section v-if="active === '概览'"><div v-if="loading" class="panel empty">正在加载…</div><template v-else><div class="stats"><article><span>今日入住</span><strong>{{ stats.todayCheckInOrders || 0 }}</strong><small>{{ stats.todayCheckInRooms || 0 }} 间房</small></article><article><span>当前在住</span><strong>{{ stats.stayingOrders || 0 }}</strong><small>{{ stats.stayingRooms || 0 }} 间房</small></article><article><span>统计收入</span><strong>{{ money(stats.revenue) }}</strong><small>退款 {{ money(stats.refunds) }}</small></article><article><span>入住率</span><strong>{{ stats.occupancyRate || 0 }}%</strong><small>{{ stats.from }} 至 {{ stats.to }}</small></article></div><div class="grid-two"><article class="panel"><p class="eyebrow">REVENUE OVERVIEW</p><h2>收入趋势</h2><div class="bars"><i v-for="row in trends" :key="row.date" :style="{ height: `${Math.min(100, Number(row.revenue || 0) / Math.max(1, Number(stats.revenue || 1)) * 100)}%` }"></i></div><div class="days"><span v-for="row in trends" :key="`${row.date}-label`">{{ row.date.slice(5) }}</span></div></article><article class="panel"><p class="eyebrow">ROOM STATUS</p><h2>房态概览</h2><div class="room-status"><div>🟢 可售 <strong>{{ stats.availableRooms || 0 }}</strong></div><div>🟠 在住 <strong>{{ stats.stayingRooms || 0 }}</strong></div><div>⚪ 待清洁 <strong>{{ stats.dirtyRooms || 0 }}</strong></div><div>🔴 维修 <strong>{{ stats.maintenanceRooms || 0 }}</strong></div></div></article></div></template></section>
    <section v-else-if="active === '民宿设置'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">PROPERTY SETTINGS</p><h2>民宿资料设置</h2></div><span class="status-chip" :class="propertyForm.status === 'OPEN' ? 'ok' : 'muted'">{{ propertyForm.status === 'OPEN' ? '营业中' : '已停业' }}</span></div><form class="property-form" @submit.prevent="saveProperty"><div class="form-grid"><label>民宿名称<input v-model="propertyForm.name" required></label><label>联系电话<input v-model="propertyForm.phone"></label></div><label>地址<input v-model="propertyForm.address" required></label><label>简介<textarea v-model="propertyForm.description" rows="3"></textarea></label><div class="form-grid"><label>入住时间<input v-model="propertyForm.checkInTime" type="time"></label><label>退房时间<input v-model="propertyForm.checkOutTime" type="time"></label></div><div class="form-grid"><label>入住规则<textarea v-model="propertyForm.checkInRule" rows="3" placeholder="例如：14:00 后办理入住"></textarea></label><label>退房规则<textarea v-model="propertyForm.checkOutRule" rows="3" placeholder="例如：12:00 前退房"></textarea></label></div><label>营业状态<select v-model="propertyForm.status"><option value="OPEN">营业中</option><option value="CLOSED">暂停营业</option></select></label><button class="primary compact-button" type="submit">保存民宿资料 <span>→</span></button></form></section>
    <section v-else-if="active === '房型管理'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">ROOM TYPES</p><h2>房型管理</h2></div><button class="primary compact-button" type="button" @click="newRoomType">+ 新增房型</button></div><div v-if="loading" class="empty">正在加载…</div><div v-else-if="roomTypes.length" class="data-table"><div class="data-row data-head"><span>房型</span><span>价格</span><span>状态</span><span>操作</span></div><div v-for="room in roomTypes" :key="room.id" class="data-row"><div><strong>{{ room.name }}</strong><small>{{ room.description || room.bedType }} · {{ room.images?.length || 0 }} 张图片 · {{ room.facilities?.length || 0 }} 项设施</small></div><span>{{ money(room.price) }} / 晚</span><em :class="room.status === 'PUBLISHED' ? 'ok' : 'muted'">{{ room.status === 'PUBLISHED' ? '已上架' : '已下架' }}</em><div class="row-actions"><button type="button" @click="editRoomType(room)">编辑</button><button type="button" @click="openMedia(room)">图片/设施</button><button type="button" @click="toggleRoomType(room)">{{ room.status === 'PUBLISHED' ? '下架' : '上架' }}</button></div></div></div><div v-else class="empty"><strong>还没有房型</strong><small>点击右上角新增第一个房型。</small></div></section>
    <section v-else-if="active === '实体房间'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">ROOMS</p><h2>实体房间</h2></div><button class="primary compact-button" type="button" @click="newRoom">+ 新增房间</button></div><div v-if="loading" class="empty">正在加载…</div><div v-else class="data-table"><div class="data-row data-head"><span>房号</span><span>房型</span><span>状态</span><span>操作</span></div><div v-for="room in rooms" :key="room.id" class="data-row"><div><strong>{{ room.roomNo }}</strong><small>{{ room.floorNo || '—' }}</small></div><span>{{ room.roomTypeName }}</span><em :class="room.status === 'AVAILABLE' ? 'ok' : 'muted'">{{ roomStatusLabel(room.status) }}</em><div class="row-actions"><button type="button" @click="editRoom(room)">编辑</button><button v-if="['AVAILABLE','DIRTY'].includes(room.status)" type="button" @click="setRoomStatus(room, 'MAINTENANCE')">报修</button><button v-if="room.status === 'AVAILABLE'" type="button" @click="setRoomStatus(room, 'DISABLED')">停用</button><button v-if="room.status === 'DIRTY'" type="button" @click="setRoomStatus(room, 'AVAILABLE')">已清洁</button><button v-if="room.status === 'MAINTENANCE' || room.status === 'DISABLED'" type="button" @click="setRoomStatus(room, 'AVAILABLE')">恢复可售</button></div></div></div></section>
    <section v-else-if="active === '房态日历'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">INVENTORY CALENDAR</p><h2>房晚库存和房态日历</h2></div><div class="calendar-toolbar"><label>开始日期<input v-model="calendarFrom" type="date"></label><button class="secondary" type="button" @click="loadCalendar">查询</button></div></div><div v-if="loading" class="empty">正在加载…</div><div v-else-if="calendarData" class="calendar-grid"><article v-for="day in calendarData.days" :key="day.date" class="calendar-day"><header><strong>{{ day.date }}</strong><small>可售 {{ day.summary.available }} · 预订 {{ day.summary.reserved }}</small></header><div class="calendar-summary"><span class="available">可售 {{ day.summary.available }}</span><span class="reserved">预订 {{ day.summary.reserved }}</span><span class="occupied">入住 {{ day.summary.occupied }}</span><span class="maintenance">维修 {{ day.summary.maintenance }}</span><span class="disabled">停用 {{ day.summary.disabled }}</span><span class="dirty">待清洁 {{ day.summary.dirty }}</span></div><div class="calendar-rooms"><span v-for="room in day.rooms" :key="room.roomId" :class="['room-pill', room.category.toLowerCase()]" :title="room.roomTypeName">{{ room.roomNo }}</span></div></article></div><div v-else class="empty"><strong>暂无日历数据</strong><small>选择日期后查询房态。</small></div></section>
    <section v-else-if="active === '订单管理'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">RESERVATIONS</p><h2>订单管理</h2></div><div class="panel-actions"><button class="secondary" type="button" @click="loadReservations">刷新</button><button class="primary compact-button" type="button" @click="openManualOrder">+ 人工创建订单</button></div></div><div v-if="loading" class="empty">正在加载…</div><div v-else-if="reservations.length" class="data-table"><div class="data-row data-head"><span>订单</span><span>入住日期</span><span>金额</span><span>状态</span></div><button v-for="order in reservations" :key="order.orderNo" class="data-row clickable" type="button" @click="openOrder(order)"><div><strong>{{ order.orderNo }}</strong><small>{{ order.contactName }} · {{ order.contactPhone }}<template v-if="order.source === 'MANUAL'"> · 人工</template></small></div><span>{{ order.checkIn }} → {{ order.checkOut }}</span><span>{{ money(order.total) }}</span><em>{{ statusLabel(order.status) }}</em></button></div><div v-else class="empty"><strong>还没有订单</strong><small>用户创建订单后会出现在这里。</small></div></section>
    <section v-else class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">GUESTS</p><h2>房客资料</h2></div><button class="secondary" type="button" @click="loadGuests">刷新</button></div><div v-if="loading" class="empty">正在加载…</div><div v-else class="data-table"><div class="data-row data-head"><span>姓名</span><span>账号</span><span>证件</span><span>联系电话</span></div><div v-for="guest in guests" :key="guest.id" class="data-row"><div><strong>{{ guest.name }}</strong><small>{{ guest.gender || '—' }}</small></div><span>{{ guest.username }}</span><span>{{ guest.idNoMasked }}</span><span>{{ guest.phone || '—' }}</span></div></div></section>
  </main></div>

  <dialog ref="mediaDialog" class="admin-dialog order-dialog" aria-labelledby="media-title"><div class="dialog-card" v-if="selectedMediaRoomType"><button class="dialog-close" type="button" aria-label="关闭" @click="closeDialog(mediaDialog)">×</button><p class="eyebrow">ROOM TYPE MEDIA</p><h2 id="media-title">{{ selectedMediaRoomType.name }} 的图片和设施</h2><div class="media-section"><h3>图片</h3><div class="media-list"><div v-for="image in selectedMediaRoomType.images" :key="image.id" class="media-item"><img :src="image.imageUrl" :alt="image.altText || selectedMediaRoomType.name"><span>{{ image.altText || image.imageUrl }}</span><button type="button" @click="deleteMedia('image', image.id)">删除</button></div><p v-if="!selectedMediaRoomType.images?.length" class="muted-text">暂无图片</p></div><div class="media-add"><input v-model="mediaForm.imageUrl" placeholder="图片 URL"><input v-model="mediaForm.altText" placeholder="图片说明"><button class="secondary" type="button" :disabled="!mediaForm.imageUrl" @click="addMedia('image')">添加图片</button></div></div><div class="media-section"><h3>设施</h3><div class="facility-list"><span v-for="facility in selectedMediaRoomType.facilities" :key="facility.id">{{ facility.name }} <button type="button" @click="deleteMedia('facility', facility.id)" aria-label="删除设施">×</button></span><p v-if="!selectedMediaRoomType.facilities?.length" class="muted-text">暂无设施</p></div><div class="media-add"><input v-model="mediaForm.facilityName" placeholder="例如：高速 Wi-Fi"><button class="secondary" type="button" :disabled="!mediaForm.facilityName" @click="addMedia('facility')">添加设施</button></div></div></div></dialog>
  <dialog ref="manualOrderDialog" class="admin-dialog" aria-labelledby="manual-order-title"><form class="dialog-card" @submit.prevent="createManualOrder"><button class="dialog-close" type="button" aria-label="关闭" @click="closeDialog(manualOrderDialog)">×</button><p class="eyebrow">WALK-IN RESERVATION</p><h2 id="manual-order-title">人工创建订单</h2><div class="form-grid"><label>入住日期<input v-model="manualOrderForm.checkIn" type="date" required></label><label>退房日期<input v-model="manualOrderForm.checkOut" type="date" required></label><label>入住人数<input v-model.number="manualOrderForm.guests" type="number" min="1" required></label></div><div class="form-grid"><label>联系人<input v-model="manualOrderForm.contactName" required></label><label>联系电话<input v-model="manualOrderForm.contactPhone" required></label></div><div class="form-grid"><label>房型<select v-model="manualOrderForm.roomTypeId" required><option v-for="type in roomTypes" :key="type.id" :value="type.id">{{ type.name }} · {{ money(type.price) }}/晚</option></select></label><label>房间数量<input v-model.number="manualOrderForm.quantity" type="number" min="1" required></label></div><button class="primary" type="submit">创建人工订单 <span>→</span></button></form></dialog>
  <dialog ref="roomTypeDialog" class="admin-dialog" aria-labelledby="room-type-title"><form class="dialog-card" @submit.prevent="saveRoomType"><button class="dialog-close" type="button" aria-label="关闭" @click="closeDialog(roomTypeDialog)">×</button><p class="eyebrow">ROOM TYPE</p><h2 id="room-type-title">{{ editingRoomType ? '编辑房型' : '新增房型' }}</h2><label>名称<input v-model="roomTypeForm.name" required></label><label>描述<input v-model="roomTypeForm.description"></label><label>床型<input v-model="roomTypeForm.bedType" required></label><div class="form-grid"><label>面积<input v-model.number="roomTypeForm.area" type="number" min="0"></label><label>最多入住<input v-model.number="roomTypeForm.maxGuests" type="number" min="1" required></label><label>价格<input v-model.number="roomTypeForm.price" type="number" min="0" required></label></div><label>封面地址<input v-model="roomTypeForm.coverImage" placeholder="/assets/room-warm.svg"></label><button class="primary" type="submit">保存房型 <span>→</span></button></form></dialog>
  <dialog ref="roomDialog" class="admin-dialog" aria-labelledby="room-title"><form class="dialog-card" @submit.prevent="saveRoom"><button class="dialog-close" type="button" aria-label="关闭" @click="closeDialog(roomDialog)">×</button><p class="eyebrow">ROOM</p><h2 id="room-title">{{ editingRoom ? '编辑房间状态' : '新增实体房间' }}</h2><label>房型<select v-model="roomForm.roomTypeId" required><option v-for="type in roomTypes" :key="type.id" :value="type.id">{{ type.name }}</option></select></label><label>房号<input v-model="roomForm.roomNo" required :disabled="Boolean(editingRoom)"></label><label>楼层<input v-model="roomForm.floorNo"></label><label>状态<select v-model="roomForm.status"><option>AVAILABLE</option><option>DIRTY</option><option>MAINTENANCE</option><option>DISABLED</option></select></label><label>备注<input v-model="roomForm.remark"></label><button class="primary" type="submit">保存房间 <span>→</span></button></form></dialog>
  <dialog ref="orderDialog" class="admin-dialog order-dialog" aria-labelledby="order-title"><div class="dialog-card" v-if="selectedOrder"><button class="dialog-close" type="button" aria-label="关闭" @click="closeDialog(orderDialog)">×</button><p class="eyebrow">RESERVATION DETAIL</p><h2 id="order-title">{{ selectedOrder.orderNo }}</h2><p class="dialog-sub">{{ selectedOrder.contactName }} · {{ selectedOrder.contactPhone }} · {{ selectedOrder.checkIn }} → {{ selectedOrder.checkOut }}</p><div class="detail-grid"><span>状态<strong>{{ statusLabel(selectedOrder.status) }}</strong></span><span>应收<strong>{{ money(selectedOrder.total) }}</strong></span><span>支付<strong>{{ selectedOrder.paymentStatus }}</strong></span><span>房间数<strong>{{ selectedOrder.roomCount }}</strong></span></div><h3>已分配房间</h3><ul class="assigned-list"><li v-for="room in selectedOrder.rooms" :key="room.reservationRoomId">{{ room.roomNo }} · {{ room.status }}</li><li v-if="!selectedOrder.rooms?.length">尚未分房</li></ul><div v-if="selectedOrder.status === 'CONFIRMED' && !selectedOrder.rooms?.length" class="action-block"><label>选择房间<small>按订单房间数选择</small><select v-model="selectedRoomIds" multiple><option v-for="room in rooms.filter((item) => ['AVAILABLE','RESERVED'].includes(item.status))" :key="room.id" :value="room.id">{{ room.roomNo }} · {{ room.roomTypeName }}</option></select></label><button class="primary" type="button" @click="assignRooms">确认分房</button></div><div v-if="['CONFIRMED','PARTIALLY_CHECKED_IN'].includes(selectedOrder.status)" class="action-block"><button class="primary" type="button" @click="checkIn">办理入住</button></div><div v-if="['CHECKED_IN','PARTIALLY_CHECKED_IN'].includes(selectedOrder.status)" class="action-block"><label>换房到<select v-model="changeForm.newRoomId"><option value="">选择新房间</option><option v-for="room in rooms.filter((item) => item.status === 'AVAILABLE')" :key="room.id" :value="room.id">{{ room.roomNo }} · {{ room.roomTypeName }}</option></select></label><label>换房原因<input v-model="changeForm.reason" placeholder="例如：客人申请换房"></label><button class="secondary" type="button" @click="changeRoom">提交换房</button><button class="primary" type="button" @click="checkOut">办理退房</button></div><div v-if="['PENDING_PAYMENT','CONFIRMED','CHECKED_IN','PARTIALLY_CHECKED_IN','PARTIALLY_CHECKED_OUT'].includes(selectedOrder.status)" class="action-block"><label>收款金额<input v-model="collectForm.amount" type="number" min="0.01" step="0.01"></label><label>方式<select v-model="collectForm.method"><option>CASH</option><option>CARD</option><option>OTHER</option></select></label><button class="secondary" type="button" @click="collectPayment">登记收款</button></div></div></dialog>
</template>
