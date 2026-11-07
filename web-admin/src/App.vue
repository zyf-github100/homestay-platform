<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'

const api = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
const token = ref(localStorage.getItem('homestay_admin_token') || '')
const username = ref('admin')
const password = ref('admin123')
const error = ref('')
const active = ref('概览')
const loading = ref(false)
const busyAction = ref('')
const operation = ref({ type: 'idle', text: '' })
const roomTypes = ref([])
const rooms = ref([])
const reservations = ref([])
const guests = ref([])
const reviews = ref([])
const auditLogs = ref([])
const stats = ref({})
const trends = ref([])
const roles = ref([])
const permissions = ref([])
const rbacUsers = ref([])
const roomTypeDialog = ref(null)
const roomDialog = ref(null)
const orderDialog = ref(null)
const guestDialog = ref(null)
const reviewDialog = ref(null)
const editingRoomType = ref(null)
const editingRoom = ref(null)
const selectedOrder = ref(null)
const selectedGuest = ref(null)
const selectedReview = ref(null)
const selectedRoomIds = ref([])
const roomTypeForm = ref({ name: '', description: '', bedType: '', area: 0, maxGuests: 2, price: 0, coverImage: '' })
const roomForm = ref({ roomTypeId: '', roomNo: '', floorNo: '', status: 'AVAILABLE', remark: '' })
const changeForm = ref({ newRoomId: '', reason: '', feeDifference: 0 })
const collectForm = ref({ amount: '', method: 'CASH', remark: '' })
const reviewReplyForm = ref({ reply: '' })
const statisticsRange = ref({ from: '', to: '' })
const auditFilters = ref({ action: '', resourceType: '', result: '', from: '', to: '' })
const reviewStatusFilter = ref('ALL')

// Navigation stays visible for staff accounts. Every request is authorized by the backend;
// a 403 is rendered as an explicit no-permission state instead of being inferred in the UI.
const pages = ['概览', '房型管理', '实体房间', '订单管理', '评价审核', '审计日志', 'RBAC 管理', '房客资料']

class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.status = status
  }
}

function headers(extra = {}) { return { Authorization: `Bearer ${token.value}`, ...extra } }

async function request(path, options = {}) {
  const response = await fetch(`${api}${path}`, {
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...headers(options.headers || {}) },
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) throw new ApiError(payload.message || payload.detail || payload.error || '请求失败', response.status)
  return payload
}

function setOperation(type, text) { operation.value = { type, text } }
function beginAction(label) { busyAction.value = label; setOperation('loading', `${label}…`) }
function finishAction(label) { busyAction.value = ''; setOperation('success', `${label}成功`) }
function failAction(e) {
  busyAction.value = ''
  setOperation(e?.status === 403 ? 'forbidden' : 'error', e?.status === 403 ? `无权限：${e.message}` : (e?.message || '请求失败'))
}
async function perform(label, task) {
  beginAction(label)
  try {
    const result = await task()
    finishAction(label)
    return result
  } catch (e) {
    failAction(e)
    throw e
  }
}
function openDialog(dialog) { nextTick(() => { if (dialog.value && !dialog.value.open) dialog.value.showModal() }) }
function closeDialog(dialog) { if (dialog.value?.open) dialog.value.close() }
function logout() { localStorage.removeItem('homestay_admin_token'); token.value = ''; error.value = ''; setOperation('idle', '') }
function fail(message) { setOperation(message?.status === 403 ? 'forbidden' : 'error', message?.message || message || '请求失败') }

async function login() {
  error.value = ''
  beginAction('登录')
  try {
    const payload = await request('/auth/login', { method: 'POST', headers: {}, body: JSON.stringify({ username: username.value, password: password.value }) })
    token.value = payload.data.token
    localStorage.setItem('homestay_admin_token', token.value)
    finishAction('登录')
    await selectPage('概览')
  } catch (e) {
    error.value = e.message
    failAction(e)
  }
}

async function selectPage(page) {
  active.value = page
  if (!token.value) return
  loading.value = true
  setOperation('loading', `正在加载${page}…`)
  try {
    if (page === '概览') await loadOverview()
    if (page === '房型管理') await loadRoomTypes()
    if (page === '实体房间') { await loadRoomTypes(); await loadRooms() }
    if (page === '订单管理') await loadReservations()
    if (page === '评价审核') await loadReviews()
    if (page === '审计日志') await loadAuditLogs()
    if (page === 'RBAC 管理') await loadRbac()
    if (page === '房客资料') await loadGuests()
    setOperation('success', `${page}加载成功`)
  } catch (e) { fail(e) } finally { loading.value = false }
}

function rangeQuery(range) {
  const params = new URLSearchParams()
  if (range.from) params.set('from', range.from)
  if (range.to) params.set('to', range.to)
  const query = params.toString()
  return query ? `?${query}` : ''
}
async function loadOverview() {
  const suffix = rangeQuery(statisticsRange.value)
  const [overview, trend] = await Promise.all([request(`/admin/statistics/overview${suffix}`), request(`/admin/statistics/trends${suffix}`)])
  stats.value = overview.data || {}; trends.value = trend.data || []
}
async function loadRoomTypes() { roomTypes.value = (await request('/admin/room-types')).data || [] }
async function loadRooms() { rooms.value = (await request('/admin/rooms')).data || [] }
async function loadReservations() { reservations.value = (await request('/admin/reservations')).data || [] }
async function loadGuests() { guests.value = (await request('/admin/guests')).data || [] }
async function loadReviews() { reviews.value = (await request('/admin/reviews')).data || [] }
async function loadAuditLogs() { auditLogs.value = (await request('/admin/audit-logs?limit=200')).data || [] }
async function loadRbac() {
  const [rolePayload, permissionPayload, userPayload] = await Promise.all([
    request('/admin/rbac/roles'), request('/admin/rbac/permissions'), request('/admin/rbac/users'),
  ])
  roles.value = rolePayload.data || []; permissions.value = permissionPayload.data || []; rbacUsers.value = userPayload.data || []
}

function newRoomType() { editingRoomType.value = null; roomTypeForm.value = { name: '', description: '', bedType: '', area: 0, maxGuests: 2, price: 0, coverImage: '' }; openDialog(roomTypeDialog) }
function editRoomType(room) { editingRoomType.value = room; roomTypeForm.value = { name: room.name, description: room.description || '', bedType: room.bedType || '', area: room.area || 0, maxGuests: room.maxGuests || 2, price: room.price || 0, coverImage: room.coverImage || '' }; openDialog(roomTypeDialog) }
async function saveRoomType() {
  try {
    await perform('保存房型', () => request(editingRoomType.value ? `/admin/room-types/${editingRoomType.value.id}` : '/admin/room-types', { method: editingRoomType.value ? 'PUT' : 'POST', body: JSON.stringify(roomTypeForm.value) }))
    closeDialog(roomTypeDialog); await loadRoomTypes()
  } catch {}
}
async function toggleRoomType(room) {
  try { await perform(room.status === 'PUBLISHED' ? '下架房型' : '上架房型', () => request(`/admin/room-types/${room.id}/status?status=${room.status === 'PUBLISHED' ? 'DRAFT' : 'PUBLISHED'}`, { method: 'PATCH' })); await loadRoomTypes() } catch {}
}
function newRoom() { editingRoom.value = null; roomForm.value = { roomTypeId: roomTypes.value[0]?.id || '', roomNo: '', floorNo: '', status: 'AVAILABLE', remark: '' }; openDialog(roomDialog) }
function editRoom(room) { editingRoom.value = room; roomForm.value = { roomTypeId: room.roomTypeId || '', roomNo: room.roomNo, floorNo: room.floorNo || '', status: room.status, remark: room.remark || '' }; openDialog(roomDialog) }
async function saveRoom() {
  try {
    await perform('保存房间', () => request(editingRoom.value ? `/admin/rooms/${editingRoom.value.id}` : '/admin/rooms', { method: editingRoom.value ? 'PUT' : 'POST', body: JSON.stringify(roomForm.value) }))
    closeDialog(roomDialog); await loadRooms()
  } catch {}
}
async function openOrder(order) {
  try {
    selectedOrder.value = (await request(`/admin/reservations/${order.orderNo}`)).data
    selectedRoomIds.value = []; changeForm.value = { newRoomId: '', reason: '', feeDifference: 0 }; collectForm.value = { amount: '', method: 'CASH', remark: '' }; openDialog(orderDialog)
  } catch (e) { fail(e) }
}
async function assignRooms() {
  try { await perform('分配房间', () => request(`/admin/reservations/${selectedOrder.value.orderNo}/assign-room`, { method: 'POST', body: JSON.stringify({ roomIds: selectedRoomIds.value.map(Number) }) })); await refreshOrder() } catch {}
}
async function checkIn() { try { await perform('办理入住', () => request(`/admin/reservations/${selectedOrder.value.orderNo}/check-in`, { method: 'POST', body: '{}' })); await refreshOrder() } catch {} }
async function checkOut() { try { await perform('办理退房', () => request(`/admin/reservations/${selectedOrder.value.orderNo}/check-out`, { method: 'POST', body: '{}' })); await refreshOrder() } catch {} }
async function changeRoom() {
  const current = selectedOrder.value.rooms?.find((room) => room.status === 'CHECKED_IN')
  if (!current) { setOperation('error', '当前没有可换的入住记录'); return }
  try { await perform('提交换房', () => request(`/admin/reservations/${selectedOrder.value.orderNo}/change-room`, { method: 'POST', body: JSON.stringify({ reservationRoomId: current.reservationRoomId, newRoomId: Number(changeForm.value.newRoomId), reason: changeForm.value.reason, feeDifference: Number(changeForm.value.feeDifference || 0) }) })); await refreshOrder() } catch {}
}
async function collectPayment() {
  try { await perform('登记收款', () => request('/admin/payments/collect', { method: 'POST', body: JSON.stringify({ orderNo: selectedOrder.value.orderNo, amount: Number(collectForm.value.amount), method: collectForm.value.method, remark: collectForm.value.remark }) })); await refreshOrder() } catch {}
}
async function refreshOrder() { selectedOrder.value = (await request(`/admin/reservations/${selectedOrder.value.orderNo}`)).data; await loadReservations() }

async function moderateReview(review, status) {
  try { await perform(status === 'HIDDEN' ? '隐藏评价' : '发布评价', () => request(`/admin/reviews/${review.id}/status?status=${status}`, { method: 'PATCH' })); await loadReviews() } catch {}
}
function openReviewReply(review) { selectedReview.value = review; reviewReplyForm.value = { reply: review.reply || '' }; openDialog(reviewDialog) }
async function submitReviewReply() {
  try { await perform('回复评价', () => request(`/admin/reviews/${selectedReview.value.id}/reply`, { method: 'POST', body: JSON.stringify({ reply: reviewReplyForm.value.reply }) })); closeDialog(reviewDialog); await loadReviews() } catch {}
}

async function viewSensitive(guest) {
  try { selectedGuest.value = (await perform('查看敏感资料', () => request(`/admin/guests/${guest.id}/sensitive`))).data; openDialog(guestDialog) } catch {}
}
async function assignRole(user) {
  try { await perform('分配角色', () => request(`/admin/rbac/users/${user.id}/role`, { method: 'PUT', body: JSON.stringify({ roleCode: user.roleCode }) })); await loadRbac() } catch {}
}
async function refreshPage() { await selectPage(active.value) }

const trendMax = computed(() => Math.max(1, ...trends.value.map((row) => Number(row.revenue || 0))))
const filteredReviews = computed(() => reviewStatusFilter.value === 'ALL' ? reviews.value : reviews.value.filter((review) => review.status === reviewStatusFilter.value))
const auditActionOptions = computed(() => [...new Set(auditLogs.value.map((row) => row.action).filter(Boolean))])
const auditResourceOptions = computed(() => [...new Set(auditLogs.value.map((row) => row.resourceType).filter(Boolean))])
const filteredAuditLogs = computed(() => auditLogs.value.filter((row) => {
  const created = String(row.createdAt || '').slice(0, 10)
  return (!auditFilters.value.action || row.action === auditFilters.value.action)
    && (!auditFilters.value.resourceType || row.resourceType === auditFilters.value.resourceType)
    && (!auditFilters.value.result || row.result === auditFilters.value.result)
    && (!auditFilters.value.from || created >= auditFilters.value.from)
    && (!auditFilters.value.to || created <= auditFilters.value.to)
}))
function statusLabel(status) { return { PENDING_PAYMENT: '待支付', CONFIRMED: '已确认', CHECKED_IN: '入住中', PARTIALLY_CHECKED_IN: '部分入住', COMPLETED: '已完成', CANCELLED: '已取消', PUBLISHED: '已发布', HIDDEN: '已隐藏' }[status] || status }
function money(value) { return `¥${Number(value || 0).toLocaleString()}` }
function formatDate(value) { return value ? new Date(value).toLocaleString('zh-CN', { dateStyle: 'short', timeStyle: 'short' }) : '—' }
onMounted(() => { if (token.value) selectPage('概览') })
</script>

<template>
  <div v-if="!token" class="login-page">
    <div class="login-card">
      <div class="logo">栖</div><p class="kicker">QIYE OPERATIONS</p><h1>欢迎回来</h1><p class="sub">管理房源、订单与每一段入住时光。</p>
      <form @submit.prevent="login">
        <label for="login-username">账号<input id="login-username" v-model="username" autocomplete="username" required></label>
        <label for="login-password">密码<input id="login-password" v-model="password" type="password" autocomplete="current-password" required></label>
        <button type="submit" :disabled="busyAction === '登录'">{{ busyAction === '登录' ? '登录中…' : '进入管理台' }} <span>→</span></button>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
      </form>
    </div>
  </div>

  <div v-else class="admin-shell">
    <aside class="sidebar">
      <div class="brand"><span>栖</span><div><strong>栖野</strong><small>OPERATIONS</small></div></div>
      <nav aria-label="管理端导航"><button v-for="item in pages" :key="item" type="button" :class="{ active: active === item }" @click="selectPage(item)">{{ item }}</button></nav>
      <button class="logout" type="button" @click="logout">退出登录 ↗</button>
    </aside>

    <main id="admin-content" class="content">
      <header><div><p class="kicker">QIYE OPERATIONS</p><h1>{{ active }}</h1></div><span class="avatar" :aria-label="`${username} 当前登录`">{{ username.slice(0, 1).toUpperCase() }}</span></header>
      <div v-if="operation.text" class="operation-status" :class="`is-${operation.type}`" role="status" aria-live="polite">{{ operation.text }}</div>

      <div v-if="operation.type === 'forbidden'" class="panel empty permission-state"><strong>当前账号无权访问此页面</strong><small>服务端返回 403，请联系系统管理员申请对应权限。</small></div>
      <template v-else>
      <section v-if="active === '概览'">
        <div class="panel filter-panel"><form @submit.prevent="refreshPage"><label for="stats-from">开始日期<input id="stats-from" v-model="statisticsRange.from" type="date"></label><label for="stats-to">结束日期<input id="stats-to" v-model="statisticsRange.to" type="date"></label><button class="secondary" type="submit" :disabled="loading">更新统计</button></form></div>
        <div v-if="loading" class="panel empty" aria-label="统计加载中"><progress aria-label="正在加载统计"></progress><span>正在加载…</span></div>
        <template v-else>
          <div class="stats"><article><span>今日入住</span><strong>{{ stats.todayCheckInOrders || 0 }}</strong><small>{{ stats.todayCheckInRooms || 0 }} 间房</small></article><article><span>当前在住</span><strong>{{ stats.stayingOrders || 0 }}</strong><small>{{ stats.stayingRooms || 0 }} 间房</small></article><article><span>统计收入</span><strong>{{ money(stats.revenue) }}</strong><small>退款 {{ money(stats.refunds) }}</small></article><article><span>入住率</span><strong>{{ stats.occupancyRate || 0 }}%</strong><small>{{ stats.from }} 至 {{ stats.to }}</small></article></div>
          <div class="grid-two"><article class="panel"><p class="eyebrow">REVENUE OVERVIEW</p><h2>收入趋势</h2><div class="bars" role="img" aria-label="按日收入趋势"><i v-for="row in trends" :key="row.date" :style="{ height: `${Math.max(3, Number(row.revenue || 0) / trendMax * 100)}%` }" :title="`${row.date} ${money(row.revenue)}`"></i></div><div class="days"><span v-for="row in trends" :key="`${row.date}-label`">{{ row.date.slice(5) }}</span></div><div class="sr-only"><table><caption>每日收入趋势数据</caption><thead><tr><th scope="col">日期</th><th scope="col">收入</th><th scope="col">订单</th></tr></thead><tbody><tr v-for="row in trends" :key="`${row.date}-table`"><td>{{ row.date }}</td><td>{{ money(row.revenue) }}</td><td>{{ row.orderCount }}</td></tr></tbody></table></div></article><article class="panel"><p class="eyebrow">ROOM STATUS</p><h2>房态概览</h2><div class="room-status"><div>🟢 可售 <strong>{{ stats.availableRooms || 0 }}</strong></div><div>🟠 在住 <strong>{{ stats.stayingRooms || 0 }}</strong></div><div>⚪ 待清洁 <strong>{{ stats.dirtyRooms || 0 }}</strong></div><div>🔴 维修 <strong>{{ stats.maintenanceRooms || 0 }}</strong></div></div></article></div>
        </template>
      </section>

      <section v-else-if="active === '房型管理'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">ROOM TYPES</p><h2>房型管理</h2></div><button class="primary compact-button" type="button" @click="newRoomType">+ 新增房型</button></div><div v-if="loading" class="empty"><progress aria-label="正在加载房型"></progress><span>正在加载…</span></div><div v-else-if="roomTypes.length" class="data-table"><div class="data-row data-head"><span>房型</span><span>价格</span><span>状态</span><span>操作</span></div><div v-for="room in roomTypes" :key="room.id" class="data-row"><div><strong>{{ room.name }}</strong><small>{{ room.description || room.bedType }}</small></div><span>{{ money(room.price) }} / 晚</span><em :class="room.status === 'PUBLISHED' ? 'ok' : 'muted'">{{ statusLabel(room.status) }}</em><div class="row-actions"><button type="button" @click="editRoomType(room)">编辑</button><button type="button" @click="toggleRoomType(room)">{{ room.status === 'PUBLISHED' ? '下架' : '上架' }}</button></div></div></div><div v-else class="empty"><strong>还没有房型</strong><small>点击右上角新增第一个房型。</small></div></section>

      <section v-else-if="active === '实体房间'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">ROOMS</p><h2>实体房间</h2></div><button class="primary compact-button" type="button" @click="newRoom">+ 新增房间</button></div><div v-if="loading" class="empty"><progress aria-label="正在加载房间"></progress><span>正在加载…</span></div><div v-else class="data-table"><div class="data-row data-head"><span>房号</span><span>房型</span><span>状态</span><span>操作</span></div><div v-for="room in rooms" :key="room.id" class="data-row"><div><strong>{{ room.roomNo }}</strong><small>{{ room.floorNo || '—' }}</small></div><span>{{ room.roomTypeName }}</span><em :class="room.status === 'AVAILABLE' ? 'ok' : 'muted'">{{ room.status }}</em><div class="row-actions"><button type="button" @click="editRoom(room)">编辑</button></div></div></div></section>

      <section v-else-if="active === '订单管理'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">RESERVATIONS</p><h2>订单管理</h2></div><button class="secondary" type="button" @click="refreshPage">刷新</button></div><div v-if="loading" class="empty"><progress aria-label="正在加载订单"></progress><span>正在加载…</span></div><div v-else-if="reservations.length" class="data-table"><div class="data-row data-head"><span>订单</span><span>入住日期</span><span>金额</span><span>状态</span></div><button v-for="order in reservations" :key="order.orderNo" class="data-row clickable" type="button" @click="openOrder(order)"><div><strong>{{ order.orderNo }}</strong><small>{{ order.contactName }} · {{ order.contactPhone }}</small></div><span>{{ order.checkIn }} → {{ order.checkOut }}</span><span>{{ money(order.total) }}</span><em>{{ statusLabel(order.status) }}</em></button></div><div v-else class="empty"><strong>还没有订单</strong><small>用户创建订单后会出现在这里。</small></div></section>

      <section v-else-if="active === '评价审核'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">REVIEWS</p><h2>评价审核与回复</h2></div><div class="toolbar"><label for="review-status">状态<select id="review-status" v-model="reviewStatusFilter"><option value="ALL">全部</option><option value="PUBLISHED">已发布</option><option value="HIDDEN">已隐藏</option></select></label><button class="secondary" type="button" @click="refreshPage">刷新</button></div></div><div v-if="loading" class="empty"><progress aria-label="正在加载评价"></progress><span>正在加载…</span></div><div v-else-if="filteredReviews.length" class="table-scroll" tabindex="0" role="region" aria-labelledby="reviews-caption"><table><caption id="reviews-caption">评价审核列表</caption><thead><tr><th scope="col">房客</th><th scope="col">评分与内容</th><th scope="col">状态</th><th scope="col">回复</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="review in filteredReviews" :key="review.id"><td><strong>{{ review.displayName || '匿名房客' }}</strong><small>{{ formatDate(review.createdAt) }}</small></td><td><span class="stars" :aria-label="`${review.score} 分`">{{ '★'.repeat(review.score) }}{{ '☆'.repeat(5 - review.score) }}</span><p>{{ review.content || '（无文字内容）' }}</p></td><td><em :class="review.status === 'PUBLISHED' ? 'ok' : 'muted'">{{ statusLabel(review.status) }}</em></td><td>{{ review.reply || '尚未回复' }}</td><td class="row-actions"><button type="button" @click="moderateReview(review, review.status === 'HIDDEN' ? 'PUBLISHED' : 'HIDDEN')">{{ review.status === 'HIDDEN' ? '显示' : '隐藏' }}</button><button type="button" @click="openReviewReply(review)">回复</button></td></tr></tbody></table></div><div v-else class="empty"><strong>没有匹配的评价</strong><small>评价提交后会出现在这里。</small></div></section>

      <section v-else-if="active === '审计日志'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">AUDIT LOGS</p><h2>审计日志</h2></div><button class="secondary" type="button" @click="refreshPage">刷新</button></div><div class="filter-grid"><label for="audit-action">动作<select id="audit-action" v-model="auditFilters.action"><option value="">全部动作</option><option v-for="value in auditActionOptions" :key="value" :value="value">{{ value }}</option></select></label><label for="audit-resource">资源<select id="audit-resource" v-model="auditFilters.resourceType"><option value="">全部资源</option><option v-for="value in auditResourceOptions" :key="value" :value="value">{{ value }}</option></select></label><label for="audit-result">结果<select id="audit-result" v-model="auditFilters.result"><option value="">全部结果</option><option value="SUCCESS">成功</option><option value="FAILURE">失败</option></select></label><label for="audit-from">起始日期<input id="audit-from" v-model="auditFilters.from" type="date"></label><label for="audit-to">结束日期<input id="audit-to" v-model="auditFilters.to" type="date"></label></div><div v-if="loading" class="empty"><progress aria-label="正在加载审计日志"></progress><span>正在加载…</span></div><div v-else-if="filteredAuditLogs.length" class="table-scroll" tabindex="0" role="region" aria-labelledby="audit-caption"><table><caption id="audit-caption">系统审计日志</caption><thead><tr><th scope="col">时间</th><th scope="col">动作</th><th scope="col">资源</th><th scope="col">结果</th><th scope="col">详情</th><th scope="col">操作人</th></tr></thead><tbody><tr v-for="log in filteredAuditLogs" :key="log.id"><td>{{ formatDate(log.createdAt) }}</td><td><code>{{ log.action }}</code></td><td>{{ log.resourceType }} / {{ log.resourceId || '—' }}</td><td><em :class="log.result === 'SUCCESS' ? 'ok' : 'muted'">{{ log.result === 'SUCCESS' ? '成功' : log.result }}</em></td><td>{{ log.detail || '—' }}</td><td>{{ log.operatorId || '系统' }}</td></tr></tbody></table></div><div v-else class="empty"><strong>没有匹配的日志</strong><small>调整筛选条件后重试。</small></div></section>

      <section v-else-if="active === 'RBAC 管理'" class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">ACCESS CONTROL</p><h2>角色、权限与用户分配</h2></div><button class="secondary" type="button" @click="refreshPage">刷新</button></div><div v-if="loading" class="empty"><progress aria-label="正在加载 RBAC 数据"></progress><span>正在加载…</span></div><template v-else><div class="rbac-grid"><article><h3>角色</h3><ul class="token-list"><li v-for="role in roles" :key="role.id"><strong>{{ role.name }}</strong><code>{{ role.code }}</code></li></ul></article><article><h3>权限</h3><ul class="token-list permission-list"><li v-for="permission in permissions" :key="permission.id"><span>{{ permission.name }}</span><code>{{ permission.code }}</code></li></ul></article></div><div class="table-scroll" tabindex="0" role="region" aria-labelledby="users-caption"><table><caption id="users-caption">用户角色分配</caption><thead><tr><th scope="col">用户</th><th scope="col">状态</th><th scope="col">当前角色</th><th scope="col">最后登录</th><th scope="col">保存</th></tr></thead><tbody><tr v-for="user in rbacUsers" :key="user.id"><td><strong>{{ user.displayName || user.username }}</strong><small>{{ user.username }} · #{{ user.id }}</small></td><td>{{ user.status }}</td><td><select v-model="user.roleCode" :aria-label="`为 ${user.username} 选择角色`"><option v-for="role in roles" :key="role.code" :value="role.code">{{ role.name }}</option></select></td><td>{{ formatDate(user.lastLoginAt) }}</td><td><button class="secondary" type="button" @click="assignRole(user)">保存角色</button></td></tr></tbody></table></div></template></section>

      <section v-else class="panel list-panel"><div class="panel-head"><div><p class="eyebrow">GUESTS</p><h2>房客资料</h2><small>列表只展示脱敏证件；明文查看由后端权限和审计控制。</small></div><button class="secondary" type="button" @click="refreshPage">刷新</button></div><div v-if="loading" class="empty"><progress aria-label="正在加载房客资料"></progress><span>正在加载…</span></div><div v-else-if="guests.length" class="table-scroll" tabindex="0" role="region" aria-labelledby="guests-caption"><table><caption id="guests-caption">房客资料列表</caption><thead><tr><th scope="col">姓名</th><th scope="col">账号</th><th scope="col">证件</th><th scope="col">联系电话</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="guest in guests" :key="guest.id"><td><strong>{{ guest.name }}</strong><small>{{ guest.gender || '—' }}</small></td><td>{{ guest.username }}</td><td>{{ guest.idNoMasked }}</td><td>{{ guest.phone || '—' }}</td><td><button class="secondary" type="button" @click="viewSensitive(guest)">查看敏感资料</button></td></tr></tbody></table></div><div v-else class="empty"><strong>还没有房客资料</strong><small>房客资料创建后会出现在这里。</small></div></section>
      </template>
    </main>
  </div>

  <dialog ref="roomTypeDialog" class="admin-dialog" aria-labelledby="room-type-title"><form class="dialog-card" @submit.prevent="saveRoomType"><button class="dialog-close" type="button" aria-label="关闭" @click="closeDialog(roomTypeDialog)">×</button><p class="eyebrow">ROOM TYPE</p><h2 id="room-type-title">{{ editingRoomType ? '编辑房型' : '新增房型' }}</h2><label for="room-type-name">名称<input id="room-type-name" v-model="roomTypeForm.name" required></label><label for="room-type-description">描述<input id="room-type-description" v-model="roomTypeForm.description"></label><label for="room-type-bed">床型<input id="room-type-bed" v-model="roomTypeForm.bedType" required></label><div class="form-grid"><label for="room-type-area">面积<input id="room-type-area" v-model.number="roomTypeForm.area" type="number" min="0"></label><label for="room-type-guests">最多入住<input id="room-type-guests" v-model.number="roomTypeForm.maxGuests" type="number" min="1" required></label><label for="room-type-price">价格<input id="room-type-price" v-model.number="roomTypeForm.price" type="number" min="0" required></label></div><label for="room-type-cover">封面地址<input id="room-type-cover" v-model="roomTypeForm.coverImage" placeholder="/assets/room-warm.svg"></label><button class="primary" type="submit" :disabled="Boolean(busyAction)">保存房型 <span>→</span></button></form></dialog>
  <dialog ref="roomDialog" class="admin-dialog" aria-labelledby="room-title"><form class="dialog-card" @submit.prevent="saveRoom"><button class="dialog-close" type="button" aria-label="关闭" @click="closeDialog(roomDialog)">×</button><p class="eyebrow">ROOM</p><h2 id="room-title">{{ editingRoom ? '编辑房间状态' : '新增实体房间' }}</h2><label for="room-type-select">房型<select id="room-type-select" v-model="roomForm.roomTypeId" required><option v-for="type in roomTypes" :key="type.id" :value="type.id">{{ type.name }}</option></select></label><label for="room-number">房号<input id="room-number" v-model="roomForm.roomNo" required :disabled="Boolean(editingRoom)"></label><label for="room-floor">楼层<input id="room-floor" v-model="roomForm.floorNo"></label><label for="room-status">状态<select id="room-status" v-model="roomForm.status"><option>AVAILABLE</option><option>DIRTY</option><option>MAINTENANCE</option><option>DISABLED</option></select></label><label for="room-remark">备注<input id="room-remark" v-model="roomForm.remark"></label><button class="primary" type="submit" :disabled="Boolean(busyAction)">保存房间 <span>→</span></button></form></dialog>
  <dialog ref="orderDialog" class="admin-dialog order-dialog" aria-labelledby="order-title"><div class="dialog-card" v-if="selectedOrder"><button class="dialog-close" type="button" aria-label="关闭" @click="closeDialog(orderDialog)">×</button><p class="eyebrow">RESERVATION DETAIL</p><h2 id="order-title">{{ selectedOrder.orderNo }}</h2><p class="dialog-sub">{{ selectedOrder.contactName }} · {{ selectedOrder.contactPhone }} · {{ selectedOrder.checkIn }} → {{ selectedOrder.checkOut }}</p><div class="detail-grid"><span>状态<strong>{{ statusLabel(selectedOrder.status) }}</strong></span><span>应收<strong>{{ money(selectedOrder.total) }}</strong></span><span>支付<strong>{{ selectedOrder.paymentStatus }}</strong></span><span>房间数<strong>{{ selectedOrder.roomCount }}</strong></span></div><h3>已分配房间</h3><ul class="assigned-list"><li v-for="room in selectedOrder.rooms" :key="room.reservationRoomId">{{ room.roomNo }} · {{ room.status }}</li><li v-if="!selectedOrder.rooms?.length">尚未分房</li></ul><div v-if="selectedOrder.status === 'CONFIRMED' && !selectedOrder.rooms?.length" class="action-block"><label for="assign-rooms">选择房间<small>按订单房间数选择</small><select id="assign-rooms" v-model="selectedRoomIds" multiple><option v-for="room in rooms.filter((item) => ['AVAILABLE','RESERVED'].includes(item.status))" :key="room.id" :value="room.id">{{ room.roomNo }} · {{ room.roomTypeName }}</option></select></label><button class="primary" type="button" @click="assignRooms">确认分房</button></div><div v-if="['CONFIRMED','PARTIALLY_CHECKED_IN'].includes(selectedOrder.status)" class="action-block"><button class="primary" type="button" @click="checkIn">办理入住</button></div><div v-if="['CHECKED_IN','PARTIALLY_CHECKED_IN'].includes(selectedOrder.status)" class="action-block"><label for="change-room">换房到<select id="change-room" v-model="changeForm.newRoomId"><option value="">选择新房间</option><option v-for="room in rooms.filter((item) => item.status === 'AVAILABLE')" :key="room.id" :value="room.id">{{ room.roomNo }} · {{ room.roomTypeName }}</option></select></label><label for="change-reason">换房原因<input id="change-reason" v-model="changeForm.reason" placeholder="例如：客人申请换房"></label><button class="secondary" type="button" @click="changeRoom">提交换房</button><button class="primary" type="button" @click="checkOut">办理退房</button></div><div v-if="['PENDING_PAYMENT','CONFIRMED','CHECKED_IN','PARTIALLY_CHECKED_IN'].includes(selectedOrder.status)" class="action-block"><label for="collect-amount">收款金额<input id="collect-amount" v-model="collectForm.amount" type="number" min="0.01" step="0.01"></label><label for="collect-method">方式<select id="collect-method" v-model="collectForm.method"><option>CASH</option><option>CARD</option><option>OTHER</option></select></label><button class="secondary" type="button" @click="collectPayment">登记收款</button></div></div></dialog>
  <dialog ref="reviewDialog" class="admin-dialog" aria-labelledby="review-dialog-title"><form class="dialog-card" @submit.prevent="submitReviewReply"><button class="dialog-close" type="button" aria-label="关闭" @click="closeDialog(reviewDialog)">×</button><p class="eyebrow">REVIEW REPLY</p><h2 id="review-dialog-title">回复评价</h2><p v-if="selectedReview" class="dialog-sub">{{ selectedReview.displayName || '匿名房客' }}：{{ selectedReview.content || '（无文字内容）' }}</p><label for="review-reply">回复内容<textarea id="review-reply" v-model="reviewReplyForm.reply" rows="5" required aria-describedby="review-reply-hint"></textarea><small id="review-reply-hint">回复会记录到审计日志并展示给房客。</small></label><button class="primary" type="submit">提交回复 <span>→</span></button></form></dialog>
  <dialog ref="guestDialog" class="admin-dialog" aria-labelledby="guest-dialog-title"><div class="dialog-card" v-if="selectedGuest"><button class="dialog-close" type="button" aria-label="关闭" @click="closeDialog(guestDialog)">×</button><p class="eyebrow">SENSITIVE GUEST DATA</p><h2 id="guest-dialog-title">敏感资料</h2><dl class="sensitive-grid"><div><dt>姓名</dt><dd>{{ selectedGuest.name }}</dd></div><div><dt>证件类型</dt><dd>{{ selectedGuest.idType }}</dd></div><div><dt>证件号码</dt><dd>{{ selectedGuest.idNo }}</dd></div><div><dt>联系电话</dt><dd>{{ selectedGuest.phone || '—' }}</dd></div></dl><p class="dialog-sub">本次查看已写入审计日志。</p></div></dialog>
</template>
