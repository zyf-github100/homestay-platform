<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'

const api = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
const menuOpen = ref(false)
const activeFilter = ref('全部')
const checkIn = ref('2026-11-14')
const checkOut = ref('2026-11-16')
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
const authDialog = ref(null)
const bookingDialog = ref(null)
const ordersDialog = ref(null)
const selectedRoom = ref(null)
const pendingRoom = ref(null)
const orders = ref([])
const authForm = ref({ username: '', password: '', displayName: '', phone: '' })
const bookingForm = ref({ contactName: '', contactPhone: '' })

const fallbackRooms = [
  { id: 1, name: '松风大床房', type: '山景 · 1 张大床', price: 688, tag: '最受欢迎', image: '/assets/room-warm.svg', availableCount: 2 },
  { id: 2, name: '云岚双床房', type: '溪景 · 2 张单人床', price: 758, tag: '适合朋友', image: '/assets/room-stream.svg', availableCount: 2 },
  { id: 3, name: '栖迟整栋小院', type: '庭院 · 3 室 1 厅', price: 2280, tag: '整栋包院', image: '/assets/courtyard.svg', availableCount: 1 }
]
const rooms = ref(fallbackRooms)
const filters = ['全部', '山景房', '溪景房', '整栋包院']
const filteredRooms = computed(() => activeFilter.value === '全部'
  ? rooms.value
  : rooms.value.filter((room) => {
    const keyword = activeFilter.value.replace('房', '')
    return room.tag.includes(keyword) || room.name.includes(keyword) || room.type.includes(keyword)
  }))

async function request(path, options = {}) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) }
  const response = await fetch(`${api}${path}`, { ...options, headers })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(payload.message || payload.detail || payload.error || '请求失败')
  return payload
}

function userHeaders(extra = {}) { return { Authorization: `Bearer ${authToken.value}`, ...extra } }
function notify(message) { toast.value = message; window.setTimeout(() => { toast.value = '' }, 3200) }
function openDialog(dialog) { nextTick(() => { if (dialog.value && !dialog.value.open) dialog.value.showModal() }) }
function closeDialog(dialog) { const target = dialog?.value || dialog; if (target?.open) target.close() }
function showAuth(mode = 'login') { authMode.value = mode; authError.value = ''; openDialog(authDialog) }

async function loadRooms() {
  loadingRooms.value = true
  try {
    const query = new URLSearchParams({ checkIn: checkIn.value, checkOut: checkOut.value, guests: String(guests.value) })
    const payload = await request(`/room-types?${query}`)
    if (Array.isArray(payload.data) && payload.data.length) {
      rooms.value = payload.data.map((room) => ({ id: room.id, name: room.name, type: room.description || room.bedType, price: Number(room.price), tag: room.availableCount > 0 ? '可立即预订' : '即将满房', image: room.coverImage || '/assets/room-warm.svg', availableCount: Number(room.availableCount || 0) }))
    }
  } catch { notify('暂时无法连接房源服务，已显示演示房型') } finally { loadingRooms.value = false }
}

async function submitAuth() {
  authBusy.value = true; authError.value = ''
  try {
    if (authMode.value === 'register') {
      await request('/auth/register', { method: 'POST', body: JSON.stringify(authForm.value) })
      authMode.value = 'login'; notify('注册成功，请登录')
    } else {
      const payload = await request('/auth/login', { method: 'POST', body: JSON.stringify({ username: authForm.value.username, password: authForm.value.password }) })
      authToken.value = payload.data.token; currentUser.value = payload.data.user
      localStorage.setItem('homestay_user_token', authToken.value); localStorage.setItem('homestay_user', JSON.stringify(currentUser.value))
      closeDialog(authDialog); notify(`欢迎回来，${currentUser.value.displayName || currentUser.value.username}`)
      if (pendingRoom.value) { selectedRoom.value = pendingRoom.value; pendingRoom.value = null; openDialog(bookingDialog) }
    }
  } catch (error) { authError.value = error.message } finally { authBusy.value = false }
}

function logout() {
  if (authToken.value) request('/auth/logout', { method: 'POST', headers: userHeaders() }).catch(() => {})
  authToken.value = ''; currentUser.value = null; orders.value = []
  localStorage.removeItem('homestay_user_token'); localStorage.removeItem('homestay_user'); notify('已退出登录')
}

async function searchRooms() {
  if (checkOut.value <= checkIn.value) { notify('离店日期必须晚于入住日期'); return }
  await loadRooms(); notify(`已为你查找 ${checkIn.value} 至 ${checkOut.value} 的房源`)
}

function reserve(room) {
  if (!room.availableCount) { notify('这个房型当前没有可售房间'); return }
  if (!authToken.value) { pendingRoom.value = room; showAuth('login'); return }
  selectedRoom.value = room; bookingForm.value = { contactName: currentUser.value?.displayName || '', contactPhone: '' }; bookingError.value = ''; openDialog(bookingDialog)
}

async function submitBooking() {
  if (!selectedRoom.value) return
  bookingBusy.value = true; bookingError.value = ''
  const idempotencyKey = `web-${crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`}`
  try {
    const payload = await request('/reservations', { method: 'POST', headers: userHeaders({ 'Idempotency-Key': idempotencyKey }), body: JSON.stringify({ checkIn: checkIn.value, checkOut: checkOut.value, guests: guests.value, contactName: bookingForm.value.contactName, contactPhone: bookingForm.value.contactPhone, items: [{ roomTypeId: selectedRoom.value.id, quantity: 1 }] }) })
    const orderNo = payload.data.orderNo
    const payment = await request(`/reservations/${orderNo}/payments`, { method: 'POST', headers: userHeaders() })
    await request(`/payments/${payment.data.paymentNo}/simulate`, { method: 'POST', headers: userHeaders(), body: JSON.stringify({ result: 'SUCCESS' }) })
    closeDialog(bookingDialog); notify(`预订成功，订单号 ${orderNo}`); await loadOrders()
  } catch (error) { bookingError.value = error.message } finally { bookingBusy.value = false }
}

async function loadOrders() {
  if (!authToken.value) return
  try { orders.value = (await request('/reservations', { headers: userHeaders() })).data || [] } catch (error) { if (/登录|Unauthorized|401/.test(error.message)) logout(); else notify(error.message) }
}

async function openOrders() {
  if (!authToken.value) { showAuth('login'); return }
  await loadOrders(); openDialog(ordersDialog)
}

async function cancelOrder(order) {
  if (!window.confirm(`确定取消订单 ${order.orderNo} 吗？`)) return
  try { await request(`/reservations/${order.orderNo}/cancel`, { method: 'POST', headers: userHeaders() }); notify('订单已取消'); await loadOrders() } catch (error) { notify(error.message) }
}

function statusLabel(status) { return { PENDING_PAYMENT: '待支付', CONFIRMED: '已确认', CHECKED_IN: '入住中', COMPLETED: '已完成', CANCELLED: '已取消' }[status] || status }
onMounted(() => { loadRooms() })
</script>

<template>
  <div class="site-shell">
    <header class="topbar">
      <a class="brand" href="#top" aria-label="栖野民宿首页"><span class="brand-mark">栖</span><span><strong>栖野</strong><small>Q I Y E  H O M E S T A Y</small></span></a>
      <button class="menu-toggle" type="button" aria-label="打开导航" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">☰</button>
      <nav class="nav-links" :class="{ open: menuOpen }" aria-label="主导航">
        <a href="#rooms">精选房型</a><a href="#story">关于栖野</a><a href="#journal">山野手记</a><button class="nav-action" type="button" @click="openOrders">我的订单</button>
        <button v-if="!currentUser" class="login-link" type="button" @click="showAuth('login')">登录 / 注册</button><button v-else class="login-link" type="button" @click="logout">{{ currentUser.displayName || currentUser.username }} · 退出</button>
      </nav>
    </header>
    <main id="top">
      <section class="hero" aria-labelledby="hero-title"><div class="hero-copy"><p class="eyebrow">STAY CLOSE TO NATURE</p><h1 id="hero-title">把日子<br /><em>住进风景里。</em></h1><p class="hero-intro">在山与溪之间，留一晚给自己。推开窗是晨雾，晚风里有松木香。</p><a class="text-link" href="#story">探索栖野 <span>↗</span></a></div><div class="hero-note"><span class="note-line"></span><span>浙江 · 莫干山<br />距离城市 1.5 小时</span></div></section>
      <section class="search-panel" aria-label="查询房源"><form @submit.prevent="searchRooms"><div class="field"><label for="check-in">入住日期</label><input id="check-in" v-model="checkIn" name="checkIn" type="date" autocomplete="off" required /></div><div class="field"><label for="check-out">离店日期</label><input id="check-out" v-model="checkOut" name="checkOut" type="date" autocomplete="off" required /></div><div class="field guests-field"><label for="guests">入住人数</label><select id="guests" v-model="guests" name="guests"><option :value="1">1 位成人</option><option :value="2">2 位成人</option><option :value="3">3 位成人</option><option :value="4">4 位成人</option></select></div><button class="search-button" type="submit" :disabled="loadingRooms">{{ loadingRooms ? '查询中…' : '查询房源' }} <span>→</span></button></form></section>
      <section id="rooms" class="section room-section" aria-labelledby="rooms-title"><div class="section-heading"><div><p class="eyebrow">A PLACE TO EXHALE</p><h2 id="rooms-title">住一晚，<br /><em>慢下来。</em></h2></div><p class="section-description">每一间房都朝向风景，保留了木头、阳光和安静。<br />选一处喜欢的角落，剩下的交给时间。</p></div><div class="filter-row" role="tablist" aria-label="房型筛选"><button v-for="filter in filters" :key="filter" type="button" :class="{ active: activeFilter === filter }" role="tab" :aria-selected="activeFilter === filter" @click="activeFilter = filter">{{ filter }}</button><span class="result-count">{{ filteredRooms.length }} 间房型</span></div><div class="room-grid"><article v-for="room in filteredRooms" :key="room.id" class="room-card"><div class="room-image"><img :src="room.image" :alt="room.name" width="640" height="512" loading="lazy" /><span class="room-tag">{{ room.tag }}</span><button class="heart" type="button" :aria-label="`收藏${room.name}`">♡</button></div><div class="room-body"><div><h3>{{ room.name }}</h3><p>{{ room.type }}</p></div><div class="room-price"><strong>¥{{ room.price.toLocaleString() }}</strong><span>/ 晚起</span></div></div><button class="room-cta" type="button" :disabled="!room.availableCount" @click="reserve(room)">{{ room.availableCount ? '立即预订' : '暂时满房' }} <span>↗</span></button></article></div></section>
      <section id="story" class="story-section section"><div class="story-image"><img src="/assets/mountain-dawn.svg" alt="山间小屋与树林" width="800" height="752" loading="lazy" /><span class="vertical-caption">THE ART OF SLOW LIVING</span></div><div class="story-copy"><p class="eyebrow">OUR PHILOSOPHY</p><h2>不赶路，<br /><em>去感受路。</em></h2><p>栖野不是一个“打卡地”。我们把房子藏在山里，让每一次到来都像回到一个久违的朋友家。</p><p>早起听鸟鸣，午后在院子里晒太阳，夜里看一场没有剪辑的星空。</p><a class="outlined-link" href="#journal">读读我们的故事 <span>↗</span></a></div></section>
      <section id="journal" class="journal-section section" aria-labelledby="journal-title"><div class="section-heading compact"><div><p class="eyebrow">FROM THE MOUNTAIN</p><h2 id="journal-title">山野手记</h2></div><a class="text-link" href="#journal">查看全部 <span>↗</span></a></div><div class="journal-grid"><article><div class="journal-icon">☼</div><p class="journal-date">01 / 旅行灵感</p><h3>在莫干山，找到一段不被打扰的时间</h3><a href="#journal">阅读手记 →</a></article><article><div class="journal-icon">⌁</div><p class="journal-date">02 / 栖野日常</p><h3>一杯咖啡的时间，足够看完一场云</h3><a href="#journal">阅读手记 →</a></article><article><div class="journal-icon">✳</div><p class="journal-date">03 / 慢食光</p><h3>跟着当季食材，吃一顿山里的晚餐</h3><a href="#journal">阅读手记 →</a></article></div></section><section class="promise-strip"><p>“愿你每次离开栖野，<br /><em>都比来时更接近自己。”</em></p><span>— 栖野主理人</span></section>
    </main>
    <footer id="orders" class="footer"><div class="footer-brand"><span class="brand-mark">栖</span><p>把日子住进风景里。<br /><small>MADE FOR SLOW DAYS.</small></p></div><div class="footer-links"><div><strong>探索</strong><a href="#rooms">精选房型</a><a href="#story">关于栖野</a><a href="#journal">山野手记</a></div><div><strong>联系</strong><span>浙江省湖州市德清县</span><span>hello@qiye-stay.com</span><span>+86 572 806 8899</span></div></div><div class="footer-bottom"><span>© 2026 QIYE HOMESTAY</span><span>隐私政策 · 预订须知</span></div></footer>

    <dialog ref="authDialog" class="modal" aria-labelledby="auth-title"><div class="modal-card"><button class="modal-close" type="button" aria-label="关闭" @click="closeDialog(authDialog)">×</button><p class="eyebrow">QIYE ACCOUNT</p><h2 id="auth-title">{{ authMode === 'login' ? '欢迎回来' : '加入栖野' }}</h2><p class="modal-intro">{{ authMode === 'login' ? '登录后即可预订房型并查看订单。' : '注册一个账号，保存你的山野旅程。' }}</p><form class="modal-form" @submit.prevent="submitAuth"><label for="auth-username">账号<input id="auth-username" v-model="authForm.username" autocomplete="username" required /></label><label v-if="authMode === 'register'" for="auth-display-name">姓名<input id="auth-display-name" v-model="authForm.displayName" autocomplete="name" required /></label><label v-if="authMode === 'register'" for="auth-phone">手机号<input id="auth-phone" v-model="authForm.phone" type="tel" inputmode="tel" autocomplete="tel" required /></label><label for="auth-password">密码<input id="auth-password" v-model="authForm.password" type="password" :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'" minlength="8" required /></label><p v-if="authError" class="form-error" role="alert">{{ authError }}</p><button class="modal-submit" type="submit" :disabled="authBusy">{{ authBusy ? '处理中…' : authMode === 'login' ? '登录' : '注册' }} <span>→</span></button></form><button class="modal-switch" type="button" @click="authMode = authMode === 'login' ? 'register' : 'login'; authError = ''">{{ authMode === 'login' ? '还没有账号？立即注册' : '已有账号？返回登录' }}</button></div></dialog>
    <dialog ref="bookingDialog" class="modal" aria-labelledby="booking-title"><div class="modal-card"><button class="modal-close" type="button" aria-label="关闭" @click="closeDialog(bookingDialog)">×</button><p class="eyebrow">YOUR STAY</p><h2 id="booking-title">确认预订</h2><p class="modal-intro">{{ selectedRoom?.name }} · {{ checkIn }} 至 {{ checkOut }}</p><form class="modal-form" @submit.prevent="submitBooking"><label for="contact-name">联系人姓名<input id="contact-name" v-model="bookingForm.contactName" autocomplete="name" required /></label><label for="contact-phone">联系电话<input id="contact-phone" v-model="bookingForm.contactPhone" type="tel" inputmode="tel" autocomplete="tel" required /></label><div class="booking-summary"><span>{{ selectedRoom?.price?.toLocaleString() }} × {{ Math.max(1, Math.round((new Date(checkOut) - new Date(checkIn)) / 86400000)) }} 晚</span><strong>模拟支付后确认订单</strong></div><p v-if="bookingError" class="form-error" role="alert">{{ bookingError }}</p><button class="modal-submit" type="submit" :disabled="bookingBusy">{{ bookingBusy ? '正在提交…' : '提交并模拟支付' }} <span>→</span></button></form></div></dialog>
    <dialog ref="ordersDialog" class="modal orders-modal" aria-labelledby="orders-title"><div class="modal-card"><button class="modal-close" type="button" aria-label="关闭" @click="closeDialog(ordersDialog)">×</button><p class="eyebrow">YOUR JOURNEY</p><h2 id="orders-title">我的订单</h2><p v-if="!orders.length" class="empty-orders">还没有订单，去选一间喜欢的房型吧。</p><ul v-else class="orders-list"><li v-for="order in orders" :key="order.orderNo"><div><strong>{{ order.orderNo }}</strong><span>{{ order.checkIn }} → {{ order.checkOut }} · {{ order.contactName }}</span></div><div class="order-meta"><b>¥{{ Number(order.total).toLocaleString() }}</b><span>{{ statusLabel(order.status) }}</span><button v-if="['PENDING_PAYMENT','CONFIRMED'].includes(order.status)" type="button" @click="cancelOrder(order)">取消</button></div></li></ul></div></dialog>
    <Transition name="toast"><div v-if="toast" class="toast" role="status" aria-live="polite">{{ toast }}</div></Transition>
  </div>
</template>
