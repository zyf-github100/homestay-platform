const playwrightModule = process.env.PLAYWRIGHT_MODULE || '/opt/codex/runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.js'
const playwright = await import(playwrightModule)
const { chromium } = playwright.default || playwright
const api = process.env.API_BASE_URL || 'http://localhost:8080/api/v1'
const userUrl = process.env.USER_WEB_URL || 'http://localhost:5173'
const adminUrl = process.env.ADMIN_WEB_URL || 'http://localhost:5174'
const username = process.env.E2E_USERNAME || `e2euser${Date.now()}`
const password = process.env.E2E_PASSWORD || 'Pass1234'

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
  const stayCheckIn = new Date(Date.now() + 180 * 86400000)
  const stayCheckOut = new Date(stayCheckIn.getTime() + 2 * 86400000)
  const dateValue = (value) => value.toISOString().slice(0, 10)
  await user.locator('#check-in').fill(dateValue(stayCheckIn))
  await user.locator('#check-out').fill(dateValue(stayCheckOut))
  await user.getByRole('button', { name: /查询房源/ }).click()
  await user.getByRole('button', { name: /立即预订/ }).first().click()
  await user.locator('#contact-name').fill('端到端用户')
  await user.locator('#contact-phone').fill('13500000000')
  await user.getByRole('button', { name: /提交并模拟支付/ }).click()
  await user.getByRole('button', { name: '我的订单' }).click()
  await user.getByRole('heading', { name: '我的订单' }).waitFor()

  const result = { user: true, admin: false }
  if (process.env.E2E_ADMIN_USERNAME && process.env.E2E_ADMIN_PASSWORD) {
    const admin = await browser.newPage()
    await admin.goto(adminUrl, { waitUntil: 'networkidle' })
    await admin.locator('input[autocomplete="username"]').fill(process.env.E2E_ADMIN_USERNAME)
    await admin.locator('input[autocomplete="current-password"]').fill(process.env.E2E_ADMIN_PASSWORD)
    await admin.getByRole('button', { name: /进入管理台/ }).click()
    await admin.getByRole('button', { name: '民宿设置' }).click()
    await admin.getByRole('heading', { name: '民宿资料设置' }).waitFor()
    await admin.getByRole('button', { name: '房型管理' }).click()
    await admin.locator('section').getByRole('heading', { name: '房型管理' }).waitFor()
    await admin.getByRole('button', { name: '图片/设施' }).first().click()
    await admin.locator('dialog[aria-labelledby="media-title"]').waitFor()
    await admin.locator('dialog[aria-labelledby="media-title"]').getByRole('button', { name: '关闭' }).click()
    await admin.getByRole('button', { name: '房态日历' }).click()
    await admin.getByRole('heading', { name: '房晚库存和房态日历' }).waitFor()
    await admin.getByRole('button', { name: '订单管理' }).click()
    await admin.getByRole('button', { name: '+ 人工创建订单' }).click()
    const manualDialog = admin.locator('dialog[aria-labelledby="manual-order-title"]')
    await manualDialog.waitFor()
    const manualCheckIn = new Date(Date.now() + 120 * 86400000)
    const manualCheckOut = new Date(manualCheckIn.getTime() + 86400000)
    const dateValue = (value) => value.toISOString().slice(0, 10)
    await manualDialog.getByLabel('入住日期').fill(dateValue(manualCheckIn))
    await manualDialog.getByLabel('退房日期').fill(dateValue(manualCheckOut))
    await manualDialog.getByLabel('联系人').fill('端到端前台订单')
    await manualDialog.getByLabel('联系电话').fill('13900000000')
    await manualDialog.getByRole('button', { name: /创建人工订单/ }).click()
    await manualDialog.waitFor({ state: 'hidden' })
    await admin.getByRole('alert').filter({ hasText: '人工订单' }).waitFor()
    result.admin = true
    result.adminOps = true
  }
  console.log(JSON.stringify({ ok: true, ...result }))
} finally {
  await browser.close()
}
