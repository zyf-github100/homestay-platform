const playwrightModule = process.env.PLAYWRIGHT_MODULE || '/opt/codex/runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.js'
const playwright = await import(playwrightModule)
const { chromium } = playwright.default || playwright
const api = process.env.API_BASE_URL || 'http://localhost:8080/api/v1'
const userUrl = process.env.USER_WEB_URL || 'http://localhost:5173'
const adminUrl = process.env.ADMIN_WEB_URL || 'http://localhost:5174'
const username = process.env.E2E_USERNAME || `e2euser${Date.now()}`
const password = process.env.E2E_PASSWORD || 'Pass1234'
const isoDate = (offsetDays) => {
  const date = new Date()
  date.setUTCDate(date.getUTCDate() + offsetDays)
  return date.toISOString().slice(0, 10)
}

async function apiRequest(path, options = {}) {
  const response = await fetch(`${api}${path}`, { ...options, headers: { 'Content-Type': 'application/json', ...(options.headers || {}) } })
  if (!response.ok) throw new Error(`${path} returned ${response.status}: ${await response.text()}`)
  return response.json()
}

if (!process.env.E2E_USERNAME) {
  await apiRequest('/auth/register', { method: 'POST', body: JSON.stringify({ username, phone: `135${String(Date.now()).slice(-8)}`, password, displayName: '端到端用户' }) })
}

const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] })
try {
  const user = await browser.newPage()
  await user.goto(userUrl, { waitUntil: 'networkidle' })
  await user.getByRole('button', { name: '登录 / 注册' }).click()
  await user.locator('#auth-username').fill(username)
  await user.locator('#auth-password').fill(password)
  await user.locator('dialog[aria-labelledby="auth-title"] .modal-submit').click()
  await user.locator('dialog[aria-labelledby="auth-title"]').waitFor({ state: 'hidden' })

  await user.locator('#check-in').fill(isoDate(30))
  await user.locator('#check-out').fill(isoDate(32))
  await user.getByRole('button', { name: '查询房源' }).click()
  await user.getByRole('button', { name: /立即预订/ }).first().waitFor()
  await user.getByRole('button', { name: /立即预订/ }).first().click()
  await user.locator('#contact-name').fill('端到端用户')
  await user.locator('#contact-phone').fill('13500000000')
  await user.getByRole('button', { name: '进入支付' }).click()
  await user.getByRole('heading', { name: '支付订单' }).waitFor()
  await user.getByRole('button', { name: '模拟支付成功' }).click()
  await user.locator('.result-banner strong').filter({ hasText: '支付成功' }).waitFor()

  await user.getByRole('button', { name: '我的订单', exact: true }).click()
  await user.getByRole('heading', { name: '我的订单' }).waitFor()
  await user.getByRole('button', { name: '查看详情' }).first().click()
  await user.getByRole('heading', { name: '订单详情' }).waitFor()
  await user.getByRole('button', { name: '返回订单' }).click()

  await user.getByRole('button', { name: '房客资料' }).click()
  await user.locator('.account-hero h1').filter({ hasText: '房客资料' }).waitFor()
  await user.getByRole('button', { name: /新增房客/ }).first().click()
  await user.locator('#guest-name').fill('端到端房客')
  await user.locator('#guest-phone').fill('13800000000')
  await user.locator('#guest-id-no').fill('110101199001011234')
  await user.getByRole('button', { name: '保存资料' }).click()
  await user.locator('.guest-card').getByText(/1101\*{10}1234/).waitFor()
  await user.getByRole('button', { name: '编辑' }).first().click()
  await user.locator('#guest-phone').fill('13900000000')
  await user.getByRole('button', { name: '保存资料' }).click()
  await user.getByText('13900000000', { exact: true }).waitFor()

  await user.getByRole('button', { name: '我的评价' }).click()
  await user.getByRole('heading', { name: '完成你的评价' }).waitFor()

  await user.getByRole('button', { name: '我的订单', exact: true }).click()
  await user.once('dialog', dialog => dialog.accept())
  await user.getByRole('button', { name: '取消订单' }).first().click()
  await user.getByText('订单已取消', { exact: true }).waitFor()

  const result = { user: true, admin: false, flow: ['login', 'book', 'pay', 'order-detail', 'guest-crud', 'review-page', 'cancel'] }
  if (process.env.E2E_ADMIN_USERNAME && process.env.E2E_ADMIN_PASSWORD) {
    const admin = await browser.newPage()
    await admin.goto(adminUrl, { waitUntil: 'networkidle' })
    await admin.locator('input[autocomplete="username"]').fill(process.env.E2E_ADMIN_USERNAME)
    await admin.locator('input[autocomplete="current-password"]').fill(process.env.E2E_ADMIN_PASSWORD)
    await admin.getByRole('button', { name: /进入管理台/ }).click()
    await admin.getByRole('button', { name: '房型管理' }).click()
    await admin.locator('section').getByRole('heading', { name: '房型管理' }).waitFor()
    result.admin = true
  }
  console.log(JSON.stringify({ ok: true, ...result }))
} finally {
  await browser.close()
}
