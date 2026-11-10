<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'

const api = import.meta.env.VITE_API_BASE_URL || '/api/v1'
const menuOpen = ref(false)
const activeFilter = ref('全部')
const today = new Date().toISOString().slice(0, 10)
const addDays = (date, days) => new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10)
const checkIn = ref(addDays(today, 1))
const checkOut = ref(addDays(today, 3))
const guests = ref(2)
const toast = ref('')
const loadingRooms = ref(false)
const authBusy = ref(false)
const bookingBusy = ref(false)
const authError = ref('')
const bookingError = ref('')
const authMode = ref('login')
const authToken = ref(localStorage.getItem('homestay_user_token') || '')
const currentUser = ref(JSON.parse(localStorage.getItem('homestay_user') || 'null'))
// Staff accounts continue into the operations console from the same login box.
const STAFF_ROLES = ['FRONT_DESK', 'OPERATOR', 'OPERATION_ADMIN', 'ADMIN', 'SYSTEM_ADMIN']
const authDialog = ref(null)
const bookingDialog = ref(null)
const selectedRoom = ref(null)
const bookingQuantity = ref(1)
const bookingGuestIds = ref([])
const pendingRoom = ref(null)
const rooms = ref([])
const orders = ref([])
const orderDetail = ref(null)
const guestsList = ref([])
const selectedPayment = ref(null)
const paymentBusy = ref(false)
const cancelResult = ref(null)
const page = ref('home')
const pageBusy = ref(false)
const pageError = ref('')
const guestsBusy = ref(false)
const guestFormOpen = ref(false)
const editingGuestId = ref(null)
const guestError = ref('')
const guestForm = ref({ name: '', gender: '', phone: '', idType: 'ID_CARD', idNo: '', remark: '' })
const reviewBusy = ref(false)
const reviewError = ref('')
const reviewForm = ref({ orderNo: '', score: 5, content: '' })
const reviewedOrders = ref(new Set())
const authForm = ref({ username: '', password: '', displayName: '', phone: '' })
const bookingForm = ref({ contactName: '', contactPhone: '' })
const profileBusy = ref(false)
const profileError = ref('')
const profileForm = ref({ displayName: '', phone: '' })

const filters = ['全部', '山景房', '溪景房', '整栋包院']
const filteredRooms = computed(() => activeFilter.value === '全部'
  ? rooms.value
  : rooms.value.filter((room) => {
    const keyword = activeFilter.value.replace('房', '')
    return room.tag.includes(keyword) || room.name.includes(keyword) || room.type.includes(keyword)
  }))
const completedOrders = computed(() => orders.value.filter((order) => order.status === 'COMPLETED'))
const paymentStatusLabel = computed(() => ({ PENDING: '待支付', SUCCESS: '支付成功', FAILED: '支付失败', CANCELLED: '支付已取消', EXPIRED: '支付已过期' }[selectedPayment.value?.status] || selectedPayment.value?.status || '待支付'))

class ApiError extends Error {
  constructor(message, status = 0, kind = 'api') {
    super(message)
    this.status = status
    this.kind = kind
  }
}

function statusLabel(status) {
  return { PENDING_PAYMENT: '待支付', CONFIRMED: '已确认', CHECKED_IN: '入住中', COMPLETED: '已完成', CANCELLED: '已取消' }[status] || status
}
function paymentLabel(status) {
  return { UNPAID: '未支付', PAID: '已支付', CANCELLED: '已取消', REFUNDED: '已退款', FAILED: '支付失败', EXPIRED: '已过期' }[status] || status
}
function userHeaders(extra = {}) { return authToken.value ? { Authorization: `Bearer ${authToken.value}`, ...extra } : { ...extra } }
function notify(message) { toast.value = message; window.setTimeout(() => { toast.value = '' }, 3200) }
function openDialog(dialog) { nextTick(() => { if (dialog.value && !dialog.value.open) dialog.value.showModal() }) }
function closeDialog(dialog) {
  const target = dialog?.value || dialog
  if (target?.open) target.close()
}
function scrollTop() { window.scrollTo({ top: 0, behavior: 'smooth' }) }

function clearSession(message = '登录状态已过期，请重新登录') {
  authToken.value = ''
  currentUser.value = null
  orders.value = []
  page.value = 'home'
  localStorage.removeItem('homestay_user_token')
  localStorage.removeItem('homestay_user')
  if (message) notify(message)
}

async function request(path, options = {}) {
  let response
  try {
    const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) }
    response = await fetch(`${api}${path}`, { ...options, headers })
  } catch {
    throw new ApiError('网络连接失败，请检查网络后重试', 0, 'network')
  }
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    const message = payload.message || payload.detail || payload.error || (response.status === 401 ? '请先登录' : response.status === 403 ? '当前账号没有权限' : `请求失败（${response.status}）`)
    const error = new ApiError(message, response.status)
    if (response.status === 401 && authToken.value) clearSession()
    throw error
  }
  return payload
}

function handlePageError(error) {
  if (error.kind === 'network') pageError.value = '网络连接失败，请检查网络后重试。'
  else if (error.status === 403) pageError.value = '当前账号没有访问权限。'
  else pageError.value = error.message || '加载失败，请稍后重试。'
}

function showAuth(mode = 'login') {
  authMode.value = mode
  authError.value = ''
  openDialog(authDialog)
}

async function loadRooms() {
  loadingRooms.value = true
  try {
    const query = new URLSearchParams({ checkIn: checkIn.value, checkOut: checkOut.value, guests: String(guests.value) })
    const payload = await request(`/room-types?${query}`)
    rooms.value = Array.isArray(payload.data) ? payload.data.map((room) => ({
      id: room.id,
      name: room.name,
      type: room.description || room.bedType || '舒适房型',
      price: Number(room.price),
      tag: room.availableCount > 0 ? '可立即预订' : '即将满房',
      image: room.coverImage || '/assets/room-warm.svg',
      availableCount: Number(room.availableCount || 0)
    })) : []
  } catch (error) {
    rooms.value = []
    notify(error.kind === 'network' ? '网络连接失败，暂时无法查询房源' : error.message)
  } finally {
    loadingRooms.value = false
  }
}

async function submitAuth() {
  authBusy.value = true
  authError.value = ''
  try {
    if (authMode.value === 'register') {
      await request('/auth/register', { method: 'POST', body: JSON.stringify(authForm.value) })
      authMode.value = 'login'
      authForm.value.password = ''
      notify('注册成功，请登录')
    } else {
      const payload = await request('/auth/login', { method: 'POST', body: JSON.stringify({ username: authForm.value.username, password: authForm.value.password }) })
      authToken.value = payload.data.token
      currentUser.value = payload.data.user
      localStorage.setItem('homestay_user_token', authToken.value)
      localStorage.setItem('homestay_user', JSON.stringify(currentUser.value))
      if (STAFF_ROLES.includes(payload.data.user.role)) {
        // Same origin, so the operations console picks the session straight up.
        localStorage.setItem('homestay_admin_token', payload.data.token)
        window.location.assign('/admin/')
        return
      }
      closeDialog(authDialog)
      notify(`欢迎回来，${currentUser.value.displayName || currentUser.value.username}`)
      if (pendingRoom.value) {
        selectedRoom.value = pendingRoom.value
        pendingRoom.value = null
        bookingForm.value = { contactName: currentUser.value?.displayName || '', contactPhone: '' }
        bookingQuantity.value = 1
        bookingGuestIds.value = []
        await loadGuests()
        openDialog(bookingDialog)
      }
    }
  } catch (error) {
    authError.value = error.kind === 'network' ? '网络连接失败，请稍后重试' : error.message
  } finally {
    authBusy.value = false
  }
}

function logout() {
  if (authToken.value) request('/auth/logout', { method: 'POST', headers: userHeaders() }).catch(() => {})
  clearSession('已退出登录')
  page.value = 'home'
}

async function searchRooms() {
  if (checkOut.value <= checkIn.value) {
    notify('离店日期必须晚于入住日期')
    return
  }
  await loadRooms()
  notify(`已为你查找 ${checkIn.value} 至 ${checkOut.value} 的房源`)
}

async function reserve(room) {
  if (!room.availableCount) {
    notify('这个房型当前没有可售房间')
    return
  }
  if (!authToken.value) {
    pendingRoom.value = room
    showAuth('login')
    return
  }
  selectedRoom.value = room
  bookingForm.value = { contactName: currentUser.value?.displayName || '', contactPhone: '' }
  bookingQuantity.value = 1
  bookingGuestIds.value = []
  bookingError.value = ''
  await loadGuests()
  openDialog(bookingDialog)
}

async function submitBooking() {
  if (!selectedRoom.value) return
  bookingBusy.value = true
  bookingError.value = ''
  const idempotencyKey = `web-${crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`}`
  try {
    const payload = await request('/reservations', {
      method: 'POST',
      headers: userHeaders({ 'Idempotency-Key': idempotencyKey }),
      body: JSON.stringify({ checkIn: checkIn.value, checkOut: checkOut.value, guests: guests.value, contactName: bookingForm.value.contactName, contactPhone: bookingForm.value.contactPhone, items: [{ roomTypeId: selectedRoom.value.id, quantity: Number(bookingQuantity.value) }], guestIds: bookingGuestIds.value.map(Number) })
    })
    const orderNo = payload.data.orderNo
    const payment = await request(`/reservations/${orderNo}/payments`, { method: 'POST', headers: userHeaders() })
    closeDialog(bookingDialog)
    selectedPayment.value = { ...payment.data, orderNo, amount: payment.data.amount || payload.data.total }
    page.value = 'payment'
    pageError.value = ''
    notify(`订单 ${orderNo} 已创建，请完成支付`)
    scrollTop()
  } catch (error) {
    bookingError.value = error.kind === 'network' ? '网络连接失败，请稍后重试' : error.message
  } finally {
    bookingBusy.value = false
  }
}

async function loadOrders() {
  if (!authToken.value) return
  pageBusy.value = true
  pageError.value = ''
  try {
    orders.value = (await request('/reservations', { headers: userHeaders() })).data || []
  } catch (error) {
    handlePageError(error)
  } finally {
    pageBusy.value = false
  }
}

async function loadOrderDetail(orderNo) {
  pageBusy.value = true
  pageError.value = ''
  orderDetail.value = null
  try {
    orderDetail.value = (await request(`/reservations/${encodeURIComponent(orderNo)}`, { headers: userHeaders() })).data
  } catch (error) {
    handlePageError(error)
  } finally {
    pageBusy.value = false
  }
}

async function openPage(nextPage) {
  menuOpen.value = false
  page.value = nextPage
  pageError.value = ''
  cancelResult.value = null
  scrollTop()
  if (!authToken.value) {
    showAuth('login')
    page.value = 'home'
    return
  }
  if (nextPage === 'orders') await loadOrders()
  if (nextPage === 'guests') await loadGuests()
  if (nextPage === 'reviews') await loadOrders()
  if (nextPage === 'profile') await loadProfile()
}

async function loadProfile() {
  profileBusy.value = true
  profileError.value = ''
  try {
    const payload = await request('/auth/me', { headers: userHeaders() })
    profileForm.value = { displayName: payload.data?.displayName || '', phone: payload.data?.phone || '' }
  } catch (error) {
    profileError.value = error.kind === 'network' ? '网络连接失败，请稍后重试' : error.message
  } finally {
    profileBusy.value = false
  }
}

async function saveProfile() {
  profileBusy.value = true
  profileError.value = ''
  try {
    const payload = await request('/users/me', { method: 'PUT', headers: userHeaders(), body: JSON.stringify(profileForm.value) })
    currentUser.value = { ...currentUser.value, ...(payload.data || {}) }
    localStorage.setItem('homestay_user', JSON.stringify(currentUser.value))
    notify('个人资料已更新')
  } catch (error) {
    profileError.value = error.kind === 'network' ? '网络连接失败，请稍后重试' : error.message
  } finally {
    profileBusy.value = false
  }
}

async function openOrderDetail(orderNo) {
  if (!authToken.value) {
    showAuth('login')
    return
  }
  page.value = 'order-detail'
  await loadOrderDetail(orderNo)
  scrollTop()
}

async function retryPage() {
  if (page.value === 'orders' || page.value === 'reviews') return loadOrders()
  if (page.value === 'guests') return loadGuests()
  if (page.value === 'order-detail' && orderDetail.value) return loadOrderDetail(orderDetail.value.orderNo)
  if (page.value === 'payment' && selectedPayment.value?.orderNo) return startPayment({ orderNo: selectedPayment.value.orderNo, total: selectedPayment.value.amount })
  pageError.value = ''
}

async function startPayment(order) {
  paymentBusy.value = true
  pageError.value = ''
  try {
    const payment = await request(`/reservations/${encodeURIComponent(order.orderNo)}/payments`, { method: 'POST', headers: userHeaders() })
    selectedPayment.value = { ...payment.data, orderNo: order.orderNo, amount: payment.data.amount || order.total }
    page.value = 'payment'
    scrollTop()
  } catch (error) {
    handlePageError(error)
  } finally {
    paymentBusy.value = false
  }
}

async function simulatePayment(result) {
  if (!selectedPayment.value?.paymentNo) return
  paymentBusy.value = true
  pageError.value = ''
  try {
    const payload = await request(`/payments/${encodeURIComponent(selectedPayment.value.paymentNo)}/simulate`, { method: 'POST', headers: userHeaders(), body: JSON.stringify({ result }) })
    selectedPayment.value = { ...selectedPayment.value, ...(payload.data || {}), status: payload.data?.status || (result === 'SUCCESS' ? 'SUCCESS' : 'CANCELLED') }
    notify(result === 'SUCCESS' ? '支付成功，订单已确认' : '支付未完成，订单已取消')
    await loadOrders()
  } catch (error) {
    handlePageError(error)
  } finally {
    paymentBusy.value = false
  }
}

async function cancelOrder(order) {
  pageBusy.value = true
  pageError.value = ''
  try {
    const preview = await request(`/reservations/${encodeURIComponent(order.orderNo)}/cancel-preview`, { headers: userHeaders() })
    if (!preview.data?.allowed) { notify(preview.message || '当前订单不能取消'); return }
    const fee = Number(preview.data.cancellationFee || 0)
    const refund = Number(preview.data.refundAmount || 0)
    const summary = fee > 0 ? `将扣除取消费用 ¥${fee.toLocaleString()}，预计退款 ¥${refund.toLocaleString()}。` : `预计退款 ¥${refund.toLocaleString()}。`
    if (!window.confirm(`确定取消订单 ${order.orderNo} 吗？\n${summary}`)) return
    const payload = await request(`/reservations/${encodeURIComponent(order.orderNo)}/cancel`, { method: 'POST', headers: userHeaders() })
    cancelResult.value = payload.data || { orderNo: order.orderNo, status: 'CANCELLED' }
    notify(payload.message || '订单已取消')
    await loadOrders()
    if (page.value === 'order-detail') await loadOrderDetail(order.orderNo)
  } catch (error) {
    handlePageError(error)
  } finally {
    pageBusy.value = false
  }
}

async function loadGuests() {
  guestsBusy.value = true
  pageError.value = ''
  try {
    guestsList.value = (await request('/guests', { headers: userHeaders() })).data || []
  } catch (error) {
    handlePageError(error)
  } finally {
    guestsBusy.value = false
  }
}

function startGuestCreate() {
  editingGuestId.value = null
  guestForm.value = { name: '', gender: '', phone: '', idType: 'ID_CARD', idNo: '', remark: '' }
  guestError.value = ''
  guestFormOpen.value = true
}
function startGuestEdit(guest) {
  editingGuestId.value = guest.id
  guestForm.value = { name: guest.name || '', gender: guest.gender || '', phone: guest.phone || '', idType: guest.idType || 'ID_CARD', idNo: '', remark: guest.remark || '' }
  guestError.value = ''
  guestFormOpen.value = true
}
async function saveGuest() {
  guestsBusy.value = true
  guestError.value = ''
  try {
    const body = { ...guestForm.value }
    if (editingGuestId.value && !body.idNo) delete body.idNo
    const payload = editingGuestId.value
      ? await request(`/guests/${editingGuestId.value}`, { method: 'PUT', headers: userHeaders(), body: JSON.stringify(body) })
      : await request('/guests', { method: 'POST', headers: userHeaders(), body: JSON.stringify(body) })
    const saved = payload.data
    guestsList.value = editingGuestId.value ? guestsList.value.map((guest) => guest.id === saved.id ? saved : guest) : [saved, ...guestsList.value]
    guestFormOpen.value = false
    notify(editingGuestId.value ? '房客资料已更新' : '房客资料已保存')
  } catch (error) {
    guestError.value = error.kind === 'network' ? '网络连接失败，请稍后重试' : error.message
  } finally {
    guestsBusy.value = false
  }
}
async function deleteGuest(guest) {
  if (!window.confirm(`确定删除 ${guest.name} 的资料吗？`)) return
  guestsBusy.value = true
  try {
    await request(`/guests/${guest.id}`, { method: 'DELETE', headers: userHeaders() })
    guestsList.value = guestsList.value.filter((item) => item.id !== guest.id)
    notify('房客资料已删除')
  } catch (error) {
    handlePageError(error)
  } finally {
    guestsBusy.value = false
  }
}

function openReview(order) {
  reviewForm.value = { orderNo: order.orderNo, score: 5, content: '' }
  reviewError.value = ''
  page.value = 'reviews'
  scrollTop()
}
async function submitReview() {
  reviewBusy.value = true
  reviewError.value = ''
  try {
    await request(`/reviews?orderNo=${encodeURIComponent(reviewForm.value.orderNo)}`, { method: 'POST', headers: userHeaders(), body: JSON.stringify({ score: Number(reviewForm.value.score), content: reviewForm.value.content }) })
    reviewedOrders.value = new Set([...reviewedOrders.value, reviewForm.value.orderNo])
    notify('感谢你的评价')
    reviewForm.value = { orderNo: '', score: 5, content: '' }
  } catch (error) {
    reviewError.value = error.kind === 'network' ? '网络连接失败，请稍后重试' : error.message
  } finally {
    reviewBusy.value = false
  }
}

onMounted(() => { loadRooms() })
</script>

<template>
  <div class="site-shell">
    <header class="topbar" :class="{ 'topbar-solid': page !== 'home' }">
      <button class="brand" type="button" aria-label="返回栖野首页" @click="page = 'home'; menuOpen = false; scrollTop()"><span class="brand-mark">栖</span><span><strong>栖野</strong><small>Q I Y E  H O M E S T A Y</small></span></button>
      <button class="menu-toggle" type="button" aria-label="打开导航" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">☰</button>
      <nav class="nav-links" :class="{ open: menuOpen }" aria-label="主导航">
        <button type="button" @click="page = 'home'; menuOpen = false; scrollTop()">精选房型</button>
        <button v-if="currentUser" type="button" @click="openPage('orders')">我的订单</button>
        <button v-if="currentUser" type="button" @click="openPage('guests')">房客资料</button>
        <button v-if="currentUser" type="button" @click="openPage('reviews')">我的评价</button>
        <button v-if="currentUser" type="button" @click="openPage('profile')">个人资料</button>
        <button v-else class="login-link" type="button" @click="showAuth('login')">登录 / 注册</button>
        <button v-if="currentUser" class="login-link" type="button" @click="logout">{{ currentUser.displayName || currentUser.username }} · 退出</button>
      </nav>
    </header>

    <main v-if="page === 'home'" id="top">
      <section class="hero" aria-labelledby="hero-title"><div class="hero-copy"><p class="eyebrow">STAY CLOSE TO NATURE</p><h1 id="hero-title">把日子<br /><em>住进风景里。</em></h1><p class="hero-intro">在山与溪之间，留一晚给自己。推开窗是晨雾，晚风里有松木香。</p><a class="text-link" href="#rooms">查看可售房型 <span>↗</span></a></div><div class="hero-card"><span class="hero-card-label">THIS WEEKEND</span><strong>山野慢生活</strong><p>一间房，一场日落，和一段不被打扰的时间。</p><span class="hero-card-link">探索栖野手记 <b>↗</b></span></div><div class="hero-note"><span class="note-line"></span><span>浙江 · 莫干山<br />距离城市 1.5 小时</span></div></section>
      <section class="search-panel" aria-label="查询房源"><form @submit.prevent="searchRooms"><div class="field"><label for="check-in">入住日期</label><input id="check-in" v-model="checkIn" name="checkIn" type="date" :min="today" required /></div><div class="field"><label for="check-out">离店日期</label><input id="check-out" v-model="checkOut" name="checkOut" type="date" :min="checkIn" required /></div><div class="field guests-field"><label for="guests">入住人数</label><select id="guests" v-model="guests" name="guests"><option :value="1">1 位成人</option><option :value="2">2 位成人</option><option :value="3">3 位成人</option><option :value="4">4 位成人</option></select></div><button class="search-button" type="submit" :disabled="loadingRooms">{{ loadingRooms ? '查询中…' : '查询房源' }} <span>→</span></button></form></section>
      <section class="trust-strip" aria-label="栖野服务承诺"><div><span class="trust-icon">✦</span><strong>真实房态</strong><small>实时查询可售房间</small></div><div><span class="trust-icon">⌁</span><strong>灵活取消</strong><small>入住前可查看规则</small></div><div><span class="trust-icon">◌</span><strong>安心入住</strong><small>全程保护个人资料</small></div><div><span class="trust-icon">↗</span><strong>慢生活指南</strong><small>为你准备周边灵感</small></div></section>
      <section id="rooms" class="section room-section" aria-labelledby="rooms-title"><div class="section-heading"><div><p class="eyebrow">A PLACE TO EXHALE</p><h2 id="rooms-title">住一晚，<br /><em>慢下来。</em></h2></div><p class="section-description">每一间房都朝向风景，保留了木头、阳光和安静。<br />选一处喜欢的角落，剩下的交给时间。</p></div><div class="filter-row" role="tablist" aria-label="房型筛选"><button v-for="filter in filters" :key="filter" type="button" :class="{ active: activeFilter === filter }" role="tab" :aria-selected="activeFilter === filter" @click="activeFilter = filter">{{ filter }}</button><span class="result-count">{{ filteredRooms.length }} 间房型</span></div><div v-if="loadingRooms" class="loading-state" role="status">正在查询可用房型…</div><div v-else-if="!rooms.length" class="empty-state"><strong>暂时没有可用房型</strong><span>请调整入住日期后重新查询。</span></div><div v-else class="room-grid"><article v-for="room in filteredRooms" :key="room.id" class="room-card"><div class="room-image"><img :src="room.image" :alt="room.name" width="640" height="512" loading="lazy" /><span class="room-tag">{{ room.tag }}</span><button class="heart" type="button" :aria-label="`收藏${room.name}`">♡</button></div><div class="room-body"><div><h3>{{ room.name }}</h3><p>{{ room.type }}</p><div class="room-meta"><span>◌ {{ room.availableCount ? `可售 ${room.availableCount} 间` : '今日满房' }}</span><span>⌂ 含基础设施</span></div></div><div class="room-price"><strong>¥{{ room.price.toLocaleString() }}</strong><span>/ 晚起</span></div></div><button class="room-cta" type="button" :disabled="!room.availableCount" @click="reserve(room)">{{ room.availableCount ? '立即预订' : '暂时满房' }} <span>↗</span></button></article></div></section>
      <section id="story" class="story-section section"><div class="story-image"><img src="/assets/mountain-dawn.svg" alt="山间小屋与树林" width="800" height="752" loading="lazy" /><span class="vertical-caption">THE ART OF SLOW LIVING</span></div><div class="story-copy"><p class="eyebrow">OUR PHILOSOPHY</p><h2>不赶路，<br /><em>去感受路。</em></h2><p>栖野不是一个“打卡地”。我们把房子藏在山里，让每一次到来都像回到一个久违的朋友家。</p><p>早起听鸟鸣，午后在院子里晒太阳，夜里看一场没有剪辑的星空。</p><a class="outlined-link" href="#journal">读读我们的故事 <span>↗</span></a></div></section>
      <section id="journal" class="journal-section section" aria-labelledby="journal-title"><div class="section-heading compact"><div><p class="eyebrow">FROM THE MOUNTAIN</p><h2 id="journal-title">山野手记</h2></div><a class="text-link" href="#journal">查看全部 <span>↗</span></a></div><div class="journal-grid"><article><div class="journal-icon">☼</div><p class="journal-date">01 / 旅行灵感</p><h3>在莫干山，找到一段不被打扰的时间</h3><a href="#journal">阅读手记 →</a></article><article><div class="journal-icon">⌁</div><p class="journal-date">02 / 栖野日常</p><h3>一杯咖啡的时间，足够看完一场云</h3><a href="#journal">阅读手记 →</a></article><article><div class="journal-icon">✳</div><p class="journal-date">03 / 慢食光</p><h3>跟着当季食材，吃一顿山里的晚餐</h3><a href="#journal">阅读手记 →</a></article></div></section><section class="promise-strip"><p>“愿你每次离开栖野，<br /><em>都比来时更接近自己。”</em></p><span>— 栖野主理人</span></section>
    </main>

    <main v-else class="account-shell">
      <section class="account-hero"><p class="eyebrow">QIYE ACCOUNT</p><h1>{{ page === 'orders' ? '我的订单' : page === 'order-detail' ? '订单详情' : page === 'payment' ? '支付订单' : page === 'guests' ? '房客资料' : page === 'reviews' ? '完成你的评价' : page === 'profile' ? '个人资料' : '栖野账户' }}</h1><p>你好，{{ currentUser?.displayName || currentUser?.username }}。你的山野旅程都在这里。</p></section>
      <section class="account-content">
        <div v-if="pageError" class="error-banner" role="alert"><span>{{ pageError }}</span><button type="button" @click="retryPage">重试</button></div>

        <template v-if="page === 'orders'">
          <div v-if="pageBusy" class="loading-state" role="status">正在加载订单…</div>
          <div v-else-if="!orders.length" class="empty-state"><strong>还没有订单</strong><span>去精选房型选一处喜欢的角落吧。</span><button class="primary-button" type="button" @click="page = 'home'; scrollTop()">浏览房型</button></div>
          <div v-else class="orders-page-list"><article v-for="order in orders" :key="order.orderNo" class="order-card"><div class="order-card-head"><div><p class="eyebrow">ORDER {{ order.orderNo }}</p><h2>{{ order.checkIn }} → {{ order.checkOut }}</h2><p>{{ order.contactName }} · {{ order.roomCount }} 间房 · {{ order.guests }} 位入住</p></div><span class="status-pill" :class="`status-${order.status.toLowerCase()}`">{{ statusLabel(order.status) }}</span></div><div class="order-card-foot"><div><strong>¥{{ Number(order.total).toLocaleString() }}</strong><span>{{ paymentLabel(order.paymentStatus) }}</span></div><div class="order-actions"><button class="secondary-button" type="button" @click="openOrderDetail(order.orderNo)">查看详情</button><button v-if="order.status === 'PENDING_PAYMENT'" class="primary-button" type="button" :disabled="paymentBusy" @click="startPayment(order)">去支付</button><button v-if="['PENDING_PAYMENT','CONFIRMED'].includes(order.status)" class="danger-button" type="button" @click="cancelOrder(order)">取消订单</button><button v-if="order.status === 'COMPLETED' && !reviewedOrders.has(order.orderNo)" class="secondary-button" type="button" @click="openReview(order)">去评价</button></div></div></article></div>
          <div v-if="cancelResult" class="result-banner success-result"><strong>订单已取消</strong><span>{{ cancelResult.refundAmount && Number(cancelResult.refundAmount) > 0 ? `已记录模拟退款 ¥${Number(cancelResult.refundAmount).toLocaleString()}` : '本订单未产生退款' }}</span></div>
        </template>

        <template v-else-if="page === 'order-detail'">
          <div v-if="pageBusy" class="loading-state" role="status">正在加载订单详情…</div>
          <div v-else-if="orderDetail" class="detail-layout"><div class="detail-main"><div v-if="cancelResult" class="result-banner success-result"><strong>订单已取消</strong><span>{{ cancelResult.refundAmount && Number(cancelResult.refundAmount) > 0 ? `已记录模拟退款 ¥${Number(cancelResult.refundAmount).toLocaleString()}` : '本订单未产生退款' }}</span></div><div class="detail-card"><div class="detail-card-head"><div><p class="eyebrow">ORDER {{ orderDetail.orderNo }}</p><h2>{{ statusLabel(orderDetail.status) }}</h2></div><span class="status-pill" :class="`status-${orderDetail.status.toLowerCase()}`">{{ paymentLabel(orderDetail.paymentStatus) }}</span></div><dl class="detail-grid"><div><dt>入住日期</dt><dd>{{ orderDetail.checkIn }}</dd></div><div><dt>离店日期</dt><dd>{{ orderDetail.checkOut }}</dd></div><div><dt>联系人</dt><dd>{{ orderDetail.contactName }}</dd></div><div><dt>联系电话</dt><dd>{{ orderDetail.contactPhone }}</dd></div><div><dt>入住人数</dt><dd>{{ orderDetail.guests }} 位</dd></div><div><dt>订单金额</dt><dd>¥{{ Number(orderDetail.total).toLocaleString() }}</dd></div></dl></div><div class="detail-card"><h3>房型明细</h3><ul class="detail-list"><li v-for="item in orderDetail.items" :key="item.id"><span>{{ item.roomTypeName }} × {{ item.quantity }}</span><span>¥{{ Number(item.unitPrice).toLocaleString() }} × {{ item.nights }} 晚</span></li></ul></div><div v-if="orderDetail.rooms?.length" class="detail-card"><h3>入住安排</h3><ul class="detail-list"><li v-for="room in orderDetail.rooms" :key="room.reservationRoomId"><span>房间 {{ room.roomNo }}</span><span>{{ room.status }}</span></li></ul></div><div v-if="orderDetail.guests?.length" class="detail-card"><h3>入住人</h3><ul class="detail-list"><li v-for="guest in orderDetail.guests" :key="guest.id"><span>{{ guest.name }}</span><span>{{ guest.idNoMasked }}</span></li></ul></div><div v-if="orderDetail.charges?.length" class="detail-card"><h3>附加费用</h3><ul class="detail-list"><li v-for="charge in orderDetail.charges" :key="charge.id"><span>{{ charge.type }} · {{ charge.description || "—" }}</span><span>¥{{ Number(charge.amount).toLocaleString() }}</span></li></ul></div><div v-if="orderDetail.payments?.length" class="detail-card"><h3>支付记录</h3><ul class="detail-list"><li v-for="payment in orderDetail.payments" :key="payment.paymentNo"><span>{{ payment.paymentNo }} · {{ payment.method }}</span><span>{{ payment.status }} · ¥{{ Number(payment.amount).toLocaleString() }}</span></li></ul></div></div><aside class="detail-aside"><button class="secondary-button full-width" type="button" @click="openPage('orders')">← 返回订单</button><button v-if="orderDetail.status === 'PENDING_PAYMENT'" class="primary-button full-width" type="button" @click="startPayment(orderDetail)">去支付</button><button v-if="['PENDING_PAYMENT','CONFIRMED'].includes(orderDetail.status)" class="danger-button full-width" type="button" @click="cancelOrder(orderDetail)">取消订单</button><button v-if="orderDetail.status === 'COMPLETED' && !reviewedOrders.has(orderDetail.orderNo)" class="secondary-button full-width" type="button" @click="openReview(orderDetail)">评价本次入住</button></aside></div>
        </template>

        <template v-else-if="page === 'payment'">
          <div class="payment-layout"><div class="payment-card"><p class="eyebrow">SIMULATED PAYMENT</p><h2>确认支付</h2><p>订单 {{ selectedPayment?.orderNo }} 的模拟支付单已创建。选择结果完成本次演示流程。</p><div class="payment-amount">¥{{ Number(selectedPayment?.amount || 0).toLocaleString() }}</div><dl class="payment-meta"><div><dt>支付单号</dt><dd>{{ selectedPayment?.paymentNo }}</dd></div><div><dt>支付方式</dt><dd>{{ selectedPayment?.method || 'SIMULATED' }}</dd></div><div><dt>状态</dt><dd>{{ paymentStatusLabel }}</dd></div></dl><div v-if="selectedPayment?.status === 'PENDING'" class="payment-actions"><button class="primary-button" type="button" :disabled="paymentBusy" @click="simulatePayment('SUCCESS')">{{ paymentBusy ? '处理中…' : '模拟支付成功' }}</button><button class="secondary-button" type="button" :disabled="paymentBusy" @click="simulatePayment('FAILURE')">模拟支付失败</button><button class="text-button" type="button" :disabled="paymentBusy" @click="simulatePayment('CANCEL')">取消支付</button></div><div v-else class="result-banner" :class="selectedPayment?.status === 'SUCCESS' ? 'success-result' : 'warning-result'"><strong>{{ paymentStatusLabel }}</strong><span>{{ selectedPayment?.status === 'SUCCESS' ? '订单已确认，可以在订单详情中查看。' : '你可以返回订单列表查看最新状态。' }}</span></div></div><aside class="payment-help"><h3>安全提示</h3><p>这是栖野的模拟支付环境，不会收集真实银行卡信息。</p><button class="secondary-button full-width" type="button" @click="openPage('orders')">返回我的订单</button></aside></div>
        </template>

        <template v-else-if="page === 'guests'">
          <div class="page-heading"><div><p class="eyebrow">TRAVEL COMPANIONS</p><h2>房客资料</h2><p>证件信息会加密保存，页面仅展示脱敏结果。</p></div><button class="primary-button" type="button" @click="startGuestCreate">+ 新增房客</button></div>
          <div v-if="guestsBusy && !guestsList.length" class="loading-state" role="status">正在加载房客资料…</div><div v-else-if="!guestsList.length && !guestFormOpen" class="empty-state"><strong>还没有房客资料</strong><span>保存常用同行人，预订时更方便。</span><button class="primary-button" type="button" @click="startGuestCreate">新增第一位房客</button></div><div v-else class="guest-grid"><article v-for="guest in guestsList" :key="guest.id" class="guest-card"><div class="guest-avatar">{{ guest.name?.slice(0, 1) }}</div><div class="guest-info"><h3>{{ guest.name }}</h3><p>{{ guest.phone || '未填写手机号' }}</p><p>{{ guest.idType }} · {{ guest.idNoMasked }}</p><p v-if="guest.remark">{{ guest.remark }}</p></div><div class="guest-actions"><button class="secondary-button" type="button" @click="startGuestEdit(guest)">编辑</button><button class="danger-button" type="button" @click="deleteGuest(guest)">删除</button></div></article></div>
          <form v-if="guestFormOpen" class="inline-form-card" @submit.prevent="saveGuest"><div class="page-heading compact-heading"><div><p class="eyebrow">{{ editingGuestId ? 'UPDATE PROFILE' : 'NEW PROFILE' }}</p><h2>{{ editingGuestId ? '编辑房客' : '新增房客' }}</h2></div><button class="text-button" type="button" @click="guestFormOpen = false">关闭</button></div><div class="form-grid"><label for="guest-name">姓名<input id="guest-name" v-model="guestForm.name" autocomplete="name" required /></label><label for="guest-gender">性别<select id="guest-gender" v-model="guestForm.gender" autocomplete="sex"><option value="">请选择</option><option value="FEMALE">女</option><option value="MALE">男</option><option value="OTHER">其他</option></select></label><label for="guest-phone">手机号<input id="guest-phone" v-model="guestForm.phone" type="tel" autocomplete="tel" /></label><label for="guest-id-type">证件类型<select id="guest-id-type" v-model="guestForm.idType"><option value="ID_CARD">居民身份证</option><option value="PASSPORT">护照</option><option value="OTHER">其他</option></select></label><label for="guest-id-no">{{ editingGuestId ? '更新证件号码（可留空）' : '证件号码' }}<input id="guest-id-no" v-model="guestForm.idNo" inputmode="text" :required="!editingGuestId" :placeholder="editingGuestId ? '留空则保留原证件' : '请输入证件号码'" /></label><label for="guest-remark">备注<textarea id="guest-remark" v-model="guestForm.remark" rows="2" maxlength="255"></textarea></label></div><p v-if="guestError" class="form-error" role="alert">{{ guestError }}</p><button class="primary-button" type="submit" :disabled="guestsBusy">{{ guestsBusy ? '保存中…' : '保存资料' }}</button></form>
        </template>

        <template v-else-if="page === 'profile'">
          <div class="review-layout"><div class="review-card"><p class="eyebrow">YOUR PROFILE</p><h2>个人资料</h2><form class="review-form" @submit.prevent="saveProfile"><label for="profile-username">账号<input id="profile-username" :value="currentUser?.username" disabled /></label><label for="profile-display-name">姓名<input id="profile-display-name" v-model="profileForm.displayName" required /></label><label for="profile-phone">手机号<input id="profile-phone" v-model="profileForm.phone" type="tel" autocomplete="tel" /></label><p v-if="profileError" class="form-error" role="alert">{{ profileError }}</p><button class="primary-button" type="submit" :disabled="profileBusy">{{ profileBusy ? '保存中…' : '保存资料' }}</button></form></div><aside class="review-help"><h3>账户信息</h3><p>修改后的资料会用于下次预订和订单联系人。</p><button class="secondary-button full-width" type="button" @click="logout">退出登录</button></aside></div>
        </template>

        <template v-else-if="page === 'reviews'">
          <div class="review-layout"><div class="review-card"><p class="eyebrow">YOUR FEEDBACK</p><h2>评价一次入住</h2><p class="review-intro">完成入住后，告诉我们哪些细节让这段旅程更特别。</p><form class="review-form" @submit.prevent="submitReview"><label for="review-order">选择已完成订单<select id="review-order" v-model="reviewForm.orderNo" required><option value="" disabled>请选择订单</option><option v-for="order in completedOrders" :key="order.orderNo" :value="order.orderNo" :disabled="reviewedOrders.has(order.orderNo)">{{ order.orderNo }} · {{ order.checkIn }} → {{ order.checkOut }}{{ reviewedOrders.has(order.orderNo) ? '（已评价）' : '' }}</option></select></label><label for="review-score">评分<select id="review-score" v-model="reviewForm.score"><option :value="5">5 分 · 非常满意</option><option :value="4">4 分 · 满意</option><option :value="3">3 分 · 一般</option><option :value="2">2 分 · 需要改进</option><option :value="1">1 分 · 不满意</option></select></label><label for="review-content">评价内容<textarea id="review-content" v-model="reviewForm.content" rows="5" maxlength="2000" placeholder="分享这次入住的感受"></textarea></label><p v-if="reviewError" class="form-error" role="alert">{{ reviewError }}</p><button class="primary-button" type="submit" :disabled="reviewBusy || !completedOrders.length">{{ reviewBusy ? '提交中…' : '提交评价' }}</button></form></div><aside class="review-help"><h3>还没有可评价的订单</h3><p v-if="!completedOrders.length">订单完成退房后会出现在这里。感谢你把真实体验告诉下一位住客。</p><p v-else>每个已完成订单只能评价一次，提交后无法重复创建。</p><button class="secondary-button full-width" type="button" @click="openPage('orders')">查看我的订单</button></aside></div>
        </template>
      </section>
    </main>

    <footer v-if="page === 'home'" class="footer"><div class="footer-brand"><span class="brand-mark">栖</span><p>把日子住进风景里。<br /><small>MADE FOR SLOW DAYS.</small></p></div><div class="footer-links"><div><strong>探索</strong><button type="button" @click="page = 'home'; scrollTop()">精选房型</button><a href="#story">关于栖野</a><a href="#journal">山野手记</a></div><div><strong>联系</strong><span>浙江省湖州市德清县</span><span>hello@qiye-stay.com</span><span>+86 572 806 8899</span></div></div><div class="footer-bottom"><span>© 2026 QIYE HOMESTAY</span><span>隐私政策 · 预订须知</span></div></footer>

    <dialog ref="authDialog" class="modal" aria-labelledby="auth-title"><div class="modal-card"><button class="modal-close" type="button" aria-label="关闭" @click="closeDialog(authDialog)">×</button><p class="eyebrow">QIYE ACCOUNT</p><h2 id="auth-title">{{ authMode === 'login' ? '欢迎回来' : '加入栖野' }}</h2><p class="modal-intro">{{ authMode === 'login' ? '登录后即可预订房型并查看订单。' : '注册一个账号，保存你的山野旅程。' }}</p><form class="modal-form" @submit.prevent="submitAuth"><label for="auth-username">账号<input id="auth-username" v-model="authForm.username" autocomplete="username" required /></label><label v-if="authMode === 'register'" for="auth-display-name">姓名<input id="auth-display-name" v-model="authForm.displayName" autocomplete="name" required /></label><label v-if="authMode === 'register'" for="auth-phone">手机号<input id="auth-phone" v-model="authForm.phone" type="tel" inputmode="tel" autocomplete="tel" required /></label><label for="auth-password">密码<input id="auth-password" v-model="authForm.password" type="password" :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'" minlength="8" required /></label><p v-if="authError" class="form-error" role="alert">{{ authError }}</p><button class="modal-submit" type="submit" :disabled="authBusy">{{ authBusy ? '处理中…' : authMode === 'login' ? '登录' : '注册' }} <span>→</span></button></form><button class="modal-switch" type="button" @click="authMode = authMode === 'login' ? 'register' : 'login'; authError = ''">{{ authMode === 'login' ? '还没有账号？立即注册' : '已有账号？返回登录' }}</button></div></dialog>
    <dialog ref="bookingDialog" class="modal" aria-labelledby="booking-title"><div class="modal-card"><button class="modal-close" type="button" aria-label="关闭" @click="closeDialog(bookingDialog)">×</button><p class="eyebrow">YOUR STAY</p><h2 id="booking-title">确认预订</h2><p class="modal-intro">{{ selectedRoom?.name }} · {{ checkIn }} 至 {{ checkOut }}</p><form class="modal-form" @submit.prevent="submitBooking"><label for="contact-name">联系人姓名<input id="contact-name" v-model="bookingForm.contactName" autocomplete="name" required /></label><label for="contact-phone">联系电话<input id="contact-phone" v-model="bookingForm.contactPhone" type="tel" inputmode="tel" autocomplete="tel" required /></label><label for="booking-quantity">房间数量<input id="booking-quantity" v-model.number="bookingQuantity" type="number" min="1" :max="selectedRoom?.availableCount || 1" required /></label><label v-if="guestsList.length" for="booking-guests">入住人资料<select id="booking-guests" v-model="bookingGuestIds" multiple size="3"><option v-for="guest in guestsList" :key="guest.id" :value="guest.id">{{ guest.name }} · {{ guest.idNoMasked }}</option></select><small>可多选常用入住人，订单会保存当时的资料快照。</small></label><div class="booking-summary"><span>{{ selectedRoom?.price?.toLocaleString() }} × {{ bookingQuantity }} 间 × {{ Math.max(1, Math.round((new Date(checkOut) - new Date(checkIn)) / 86400000)) }} 晚</span><strong>创建订单后进入支付</strong></div><p v-if="bookingError" class="form-error" role="alert">{{ bookingError }}</p><button class="modal-submit" type="submit" :disabled="bookingBusy">{{ bookingBusy ? '正在创建订单…' : '进入支付' }} <span>→</span></button></form></div></dialog>
    <Transition name="toast"><div v-if="toast" class="toast" role="status" aria-live="polite">{{ toast }}</div></Transition>
  </div>
</template>
