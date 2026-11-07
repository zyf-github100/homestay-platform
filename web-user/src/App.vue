<script setup>
import { computed, onMounted, ref } from 'vue'

const menuOpen = ref(false)
const activeFilter = ref('全部')
const checkIn = ref('2026-11-14')
const checkOut = ref('2026-11-16')
const guests = ref(2)
const toast = ref('')

const fallbackRooms = [
  { name: '松风大床房', type: '山景 · 1 张大床', price: 688, tag: '最受欢迎', image: '/assets/room-warm.svg' },
  { name: '云岚双床房', type: '溪景 · 2 张单人床', price: 758, tag: '适合朋友', image: '/assets/room-stream.svg' },
  { name: '栖迟整栋小院', type: '庭院 · 3 室 1 厅', price: 2280, tag: '整栋包院', image: '/assets/courtyard.svg' }
]
const rooms = ref(fallbackRooms)
const filters = ['全部', '山景房', '溪景房', '整栋包院']
const filteredRooms = computed(() => activeFilter.value === '全部' ? rooms.value : rooms.value.filter((room) => room.tag.includes(activeFilter.value.replace('房', '')) || room.name.includes(activeFilter.value.replace('房', '')) || room.type.includes(activeFilter.value.replace('房', ''))))
onMounted(async () => {
  try {
    const response = await fetch(`${import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'}/room-types`)
    if (!response.ok) return
    const payload = await response.json()
    if (Array.isArray(payload.data) && payload.data.length) {
      rooms.value = payload.data.map((room) => ({
        name: room.name,
        type: room.description || room.bedType,
        price: Number(room.price),
        tag: room.availableCount > 0 ? '可立即预订' : '即将满房',
        image: room.coverImage || '/assets/room-warm.svg'
      }))
    }
  } catch { /* 本地后端未启动时保留演示数据 */ }
})
function searchRooms() {
  toast.value = `已为你查找 ${checkIn.value} 至 ${checkOut.value} 的房源`
  window.setTimeout(() => { toast.value = '' }, 3000)
}
function reserve(room) {
  toast.value = `已选择「${room.name}」，接下来填写入住信息`
  window.setTimeout(() => { toast.value = '' }, 3000)
}
</script>

<template>
  <div class="site-shell">
    <header class="topbar">
      <a class="brand" href="#top" aria-label="栖野民宿首页"><span class="brand-mark">栖</span><span><strong>栖野</strong><small>Q I Y E  H O M E S T A Y</small></span></a>
      <button class="menu-toggle" type="button" aria-label="打开导航" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">☰</button>
      <nav class="nav-links" :class="{ open: menuOpen }" aria-label="主导航">
        <a href="#rooms">精选房型</a><a href="#story">关于栖野</a><a href="#journal">山野手记</a><a href="#orders">我的订单</a>
        <button class="login-link" type="button" @click="toast = '登录功能将在后端接入后开放'">登录 / 注册</button>
      </nav>
    </header>

    <main id="top">
      <section class="hero" aria-labelledby="hero-title">
        <div class="hero-copy">
          <p class="eyebrow">STAY CLOSE TO NATURE</p>
          <h1 id="hero-title">把日子<br /><em>住进风景里。</em></h1>
          <p class="hero-intro">在山与溪之间，留一晚给自己。推开窗是晨雾，晚风里有松木香。</p>
          <a class="text-link" href="#story">探索栖野 <span>↗</span></a>
        </div>
        <div class="hero-note"><span class="note-line"></span><span>浙江 · 莫干山<br />距离城市 1.5 小时</span></div>
      </section>

      <section class="search-panel" aria-label="查询房源">
        <form @submit.prevent="searchRooms">
          <div class="field"><label for="check-in">入住日期</label><input id="check-in" v-model="checkIn" name="checkIn" type="date" required /></div>
          <div class="field"><label for="check-out">离店日期</label><input id="check-out" v-model="checkOut" name="checkOut" type="date" required /></div>
          <div class="field guests-field"><label for="guests">入住人数</label><select id="guests" v-model="guests" name="guests"><option :value="1">1 位成人</option><option :value="2">2 位成人</option><option :value="3">3 位成人</option><option :value="4">4 位成人</option></select></div>
          <button class="search-button" type="submit">查询房源 <span>→</span></button>
        </form>
      </section>

      <section id="rooms" class="section room-section" aria-labelledby="rooms-title">
        <div class="section-heading"><div><p class="eyebrow">A PLACE TO EXHALE</p><h2 id="rooms-title">住一晚，<br /><em>慢下来。</em></h2></div><p class="section-description">每一间房都朝向风景，保留了木头、阳光和安静。<br />选一处喜欢的角落，剩下的交给时间。</p></div>
        <div class="filter-row" role="tablist" aria-label="房型筛选"><button v-for="filter in filters" :key="filter" type="button" :class="{ active: activeFilter === filter }" role="tab" :aria-selected="activeFilter === filter" @click="activeFilter = filter">{{ filter }}</button><span class="result-count">{{ filteredRooms.length }} 间可预订</span></div>
        <div class="room-grid">
          <article v-for="room in filteredRooms" :key="room.name" class="room-card">
            <div class="room-image"><img :src="room.image" :alt="room.name" loading="lazy" /><span class="room-tag">{{ room.tag }}</span><button class="heart" type="button" :aria-label="`收藏${room.name}`">♡</button></div>
            <div class="room-body"><div><h3>{{ room.name }}</h3><p>{{ room.type }}</p></div><div class="room-price"><strong>¥{{ room.price.toLocaleString() }}</strong><span>/ 晚起</span></div></div>
            <button class="room-cta" type="button" @click="reserve(room)">查看房型 <span>↗</span></button>
          </article>
        </div>
      </section>

      <section id="story" class="story-section section">
        <div class="story-image"><img src="/assets/mountain-dawn.svg" alt="山间小屋与树林" loading="lazy" /><span class="vertical-caption">THE ART OF SLOW LIVING</span></div>
        <div class="story-copy"><p class="eyebrow">OUR PHILOSOPHY</p><h2>不赶路，<br /><em>去感受路。</em></h2><p>栖野不是一个“打卡地”。我们把房子藏在山里，让每一次到来都像回到一个久违的朋友家。</p><p>早起听鸟鸣，午后在院子里晒太阳，夜里看一场没有剪辑的星空。</p><a class="outlined-link" href="#journal">读读我们的故事 <span>↗</span></a></div>
      </section>

      <section id="journal" class="journal-section section" aria-labelledby="journal-title"><div class="section-heading compact"><div><p class="eyebrow">FROM THE MOUNTAIN</p><h2 id="journal-title">山野手记</h2></div><a class="text-link" href="#journal">查看全部 <span>↗</span></a></div><div class="journal-grid"><article><div class="journal-icon">☼</div><p class="journal-date">01 / 旅行灵感</p><h3>在莫干山，找到一段不被打扰的时间</h3><a href="#journal">阅读手记 →</a></article><article><div class="journal-icon">⌁</div><p class="journal-date">02 / 栖野日常</p><h3>一杯咖啡的时间，足够看完一场云</h3><a href="#journal">阅读手记 →</a></article><article><div class="journal-icon">✳</div><p class="journal-date">03 / 慢食光</p><h3>跟着当季食材，吃一顿山里的晚餐</h3><a href="#journal">阅读手记 →</a></article></div></section>

      <section class="promise-strip"><p>“愿你每次离开栖野，<br /><em>都比来时更接近自己。”</em></p><span>— 栖野主理人</span></section>
    </main>

    <footer id="orders" class="footer"><div class="footer-brand"><span class="brand-mark">栖</span><p>把日子住进风景里。<br /><small>MADE FOR SLOW DAYS.</small></p></div><div class="footer-links"><div><strong>探索</strong><a href="#rooms">精选房型</a><a href="#story">关于栖野</a><a href="#journal">山野手记</a></div><div><strong>联系</strong><span>浙江省湖州市德清县</span><span>hello@qiye-stay.com</span><span>+86 572 806 8899</span></div></div><div class="footer-bottom"><span>© 2026 QIYE HOMESTAY</span><span>隐私政策 · 预订须知</span></div></footer>
    <Transition name="toast"><div v-if="toast" class="toast" role="status" aria-live="polite">{{ toast }}</div></Transition>
  </div>
</template>
