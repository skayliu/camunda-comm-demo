// 部门沟通全链路真机冒烟：manager 发起 → alice 分发 → bob 经办 → alice 汇总 → manager 解锁。
//
// 与 e2e-smoke.mjs 的区别：那个脚本只「打开并校验渲染」，不提交；这个脚本会真实提交，
// 因此每次运行都会产生一条完整的申请流程 + 一条部门沟通子流程（演示库可丢弃）。
//
// 运行前要求：
//   1. 应用已在 8080 启动（IDEA 或 mvn spring-boot:run）；
//   2. 前端已构建（npm run build），否则页面里没有「部门沟通」按钮；
//   3. 演示账号可登录（manager / alice / bob，密码 demo）。
//
// 用法：
//   node scripts/dept-comm-smoke.mjs
//   SMOKE_BASE=http://localhost:8080/app SMOKE_HEADED=1 node scripts/dept-comm-smoke.mjs
//   SMOKE_KEEP_DATA=1 保留数据（默认也保留，只是不再额外启动流程）
//
// 失败时的定位手段：每个提交动作都会把 /api/** 的请求、状态码、请求体、响应体打在
// [net] 前缀下，界面弹出的 antd message 文案也会原样打印。所以「没发请求」= 前端
// 校验拦下，「HTTP 4xx/5xx」= 后端拒绝，「200 但 res.error=true」= 业务失败，三者不会混淆。
//
// 两个已经踩过的坑，改这个脚本时别踩回去：
//   1. 不能用 Escape 关 antd 多选下拉：Drawer 默认 keyboard=true，Escape 关的是抽屉本身；
//      要收下拉只能点面板里的空白区域。
//   2. 同一账号二次登录会顶掉旧会话（单点登录），因此 UI 校验期间不能用 API 会话查状态，
//      状态交叉验证必须放在 browser.close() 之后重新登录再做。
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.SMOKE_BASE || 'http://localhost:8080/app'
const ORIGIN = new URL(BASE).origin
const PASS = process.env.SMOKE_PASS || 'demo'
const HEADLESS = process.env.SMOKE_HEADED !== '1'
const DEPT_NAME = process.env.SMOKE_DEPT || 'IT 部'
const HANDLER = process.env.SMOKE_HANDLER || 'bob'
const EDGE =
  process.env.SMOKE_BROWSER ||
  String.raw`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = path.join(HERE, 'e2e-shots', 'dept-comm')
fs.mkdirSync(SHOT_DIR, { recursive: true })
// 每次运行清掉上一次的截图，避免新旧混在一起看不出哪张是本次的
for (const f of fs.readdirSync(SHOT_DIR)) {
  if (f.endsWith('.png')) fs.rmSync(path.join(SHOT_DIR, f))
}

const results = []
const consoleErrors = []
const IGNORED = [/\/api\/auth\/me/, /favicon/i, /401 \(Unauthorized\)/]

// ── 网络日志 ───────────────────────────────────────────────────────────
// 只记 /api/**。写请求和失败响应连报文一起抓，读请求只在失败时抓，
// 这样一次失败的提交能直接看到「发了没发、状态码多少、后端说了什么」。
const netlog = []
const netIndex = new Map()

function attachNet(p) {
  p.on('request', (req) => {
    const url = req.url()
    if (!url.includes('/api/')) return
    const entry = {
      method: req.method(),
      path: url.slice(url.indexOf('/api/')),
      post: req.postData() || '',
      status: null,
      body: '',
      failed: ''
    }
    netIndex.set(req, entry)
    netlog.push(entry)
  })
  p.on('response', async (res) => {
    const entry = netIndex.get(res.request())
    if (!entry) return
    entry.status = res.status()
    if (entry.method === 'GET' && entry.status < 400) return
    try {
      entry.body = (await res.text()).replace(/\s+/g, ' ').slice(0, 600)
    } catch (e) {
      entry.body = `<报文读取失败：${e.message}>`
    }
  })
  p.on('requestfailed', (req) => {
    const entry = netIndex.get(req)
    if (!entry) return
    entry.failed = (req.failure() && req.failure().errorText) || 'requestfailed'
  })
}

/** 打印 from 之后产生的请求；返回值就是这批记录，供调用方判定 */
function dumpNet(from = 0) {
  const list = netlog.slice(from)
  if (!list.length) {
    log('    [net] 这段操作没有发出任何 /api 请求')
    return list
  }
  for (const e of list) {
    const status = e.status === null ? '无响应' : e.status
    const flag = e.failed ? `  !! ${e.failed}` : ''
    log(`    [net] ${e.method} ${e.path} → ${status}${flag}`)
    if (e.post) log(`          req: ${e.post.slice(0, 300)}`)
    if (e.body) log(`          res: ${e.body.slice(0, 300)}`)
  }
  return list
}

/** 当前所有可见的 antd message 文案（不等某条特定文案，避免文案不符就静默超时） */
async function noticesOf(p) {
  return p
    .locator('.ant-message-notice-content')
    .allInnerTexts()
    .then((list) => list.map((t) => t.replace(/\s+/g, ' ').trim()).filter(Boolean))
    .catch(() => [])
}

function log(line = '') {
  console.log(line)
}

async function shot(page, name) {
  const file = path.join(SHOT_DIR, `${name}.png`)
  await page.screenshot({ path: file, fullPage: false })
  return file
}

async function step(name, fn) {
  const started = Date.now()
  try {
    const detail = await fn()
    results.push({ name, ok: true, ms: Date.now() - started, detail: detail || '' })
    log(`  ✓ ${name}  ${detail || ''}`)
    return true
  } catch (e) {
    const raw = e && e.message ? e.message : String(e)
    const msg = raw.split('\n').filter(Boolean).slice(0, 2).join(' | ')
    results.push({ name, ok: false, ms: Date.now() - started, detail: msg })
    log(`  ✗ ${name}  ${msg}`)
    throw e
  }
}

// ── API 侧：造一条 manager 待办，避免脚本依赖库里已有数据 ─────────────────
function makeApiSession() {
  // jar 必须在每次 login 内部新建：复用同一个 jar 会把上一段的 JSESSIONID 带进来，
  // 而该会话很可能已被 UI 登录顶掉。ConcurrentSessionFilter 看到过期会话会直接返回
  // 401（"该账号已在其它设备登录"），请求到不了登录逻辑，表现为「密码正确却 401」。
  return async function login(userId) {
    const jar = new Map()
    const absorb = (res) => {
      const list =
        typeof res.headers.getSetCookie === 'function'
          ? res.headers.getSetCookie()
          : [res.headers.get('set-cookie')].filter(Boolean)
      for (const raw of list) {
        const kv = raw.split(';', 1)[0]
        const i = kv.indexOf('=')
        if (i > 0) jar.set(kv.slice(0, i), kv.slice(i + 1))
      }
    }
    const cookie = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ')

    let res = await fetch(`${ORIGIN}/api/auth/csrf`, { headers: { Accept: 'application/json' } })
    absorb(res)
    const { token } = await res.json()
    res = await fetch(`${ORIGIN}/api/auth/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        Cookie: cookie(),
        'X-XSRF-TOKEN': token
      },
      body: JSON.stringify({ userId, password: PASS })
    })
    absorb(res)
    if (!res.ok) throw new Error(`API 登录失败 ${userId}: ${res.status}`)

    const call = async (method, p, body) => {
      const headers = { Accept: 'application/json', Cookie: cookie() }
      if (method !== 'GET') {
        headers['Content-Type'] = 'application/json'
        headers['X-XSRF-TOKEN'] = token
      }
      const r = await fetch(`${ORIGIN}${p}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body)
      })
      absorb(r)
      const text = await r.text()
      if (!r.ok) throw new Error(`${method} ${p} → ${r.status} ${text.slice(0, 200)}`)
      return text ? JSON.parse(text) : null
    }
    return {
      get: (p) => call('GET', p),
      post: (p, b) => call('POST', p, b)
    }
  }
}

const apiLogin = makeApiSession()

function rowsOf(res) {
  if (!res) return []
  if (Array.isArray(res)) return res
  return res.rows || res.records || res.list || res.data || []
}

/** 启动一条申请流程并完成「申请」节点，让 manager 收到 manager_review 待办 */
async function prepareManagerTodo() {
  const applicant = await apiLogin('applicant')
  const started = await applicant.post('/api/comm/demo/start', {})
  const pid = started.processInstanceId
  if (!pid) throw new Error(`启动流程无 processInstanceId: ${JSON.stringify(started)}`)

  const todo = rowsOf(await applicant.get('/api/wb/todo?type=approval&page=1&size=50'))
  const applyTask = todo.find((t) => t.processInstanceId === pid)
  if (!applyTask) throw new Error(`申请人没有拿到自己的待办，实例 ${pid}`)
  await applicant.post(`/api/comm/demo/complete/${applyTask.taskId}`, { comment: '冒烟脚本自动提交' })

  const manager = await apiLogin('manager')
  const list = rowsOf(await manager.get('/api/wb/todo?type=approval&page=1&size=50'))
  const target = list.find((t) => t.processInstanceId === pid && !t.isCommTask)
  if (!target) throw new Error(`manager 没有收到审批待办，实例 ${pid}`)
  return { pid, taskId: target.taskId, manager }
}

// ── UI 辅助 ────────────────────────────────────────────────────────────
const rows = (page) => page.locator('.ant-table-tbody tr.ant-table-row')
const drawerOf = (page) => page.locator('.ant-drawer-content').last()

async function login(page, userId) {
  await page.goto(`${BASE}/#/login`, { waitUntil: 'domcontentloaded' })
  const account = page.getByPlaceholder('请输入账号')
  const needLogin = await account
    .waitFor({ state: 'visible', timeout: 6000 })
    .then(() => true)
    .catch(() => false)
  if (needLogin) {
    await account.fill(userId)
    await page.getByPlaceholder('请输入密码').fill(PASS)
    const resp = page.waitForResponse((r) => r.url().includes('/api/auth/login'), { timeout: 20000 })
    await page.getByRole('button', { name: /登\s*录/ }).click()
    const r = await resp
    if (r.status() !== 200) throw new Error(`登录接口 HTTP ${r.status()}`)
  }
  await page.waitForURL(/#\/(todo|workbench)/, { timeout: 20000 }).catch(() => {})
  if (!/#\/todo/.test(page.url())) {
    await page.goto(`${BASE}/#/todo`, { waitUntil: 'domcontentloaded' })
  }
  await rows(page).first().waitFor({ state: 'visible', timeout: 25000 })
  return userId
}

async function closeDrawer(page, drawer) {
  const btn = drawer.locator('.ant-drawer-close').first()
  if (await btn.count()) await btn.click({ force: true }).catch(() => {})
  await drawer.waitFor({ state: 'hidden', timeout: 10000 }).catch(() => {})
  // 抽屉关闭动画结束后遮罩仍在，确保没挡住下一行
  await page.locator('.ant-drawer-mask:visible').first().waitFor({ state: 'hidden', timeout: 3000 }).catch(() => {})
}

/**
 * 从当前页第一条开始逐行打开，直到 match(drawer) 返回 true。
 * 部门子流程待办的按钮文案是「去反馈」，普通审批是「去处理」，两种都试。
 */
async function openDrawerWhere(page, match, limit = 8) {
  const all = rows(page)
  const n = Math.min(await all.count(), limit)
  for (let i = 0; i < n; i++) {
    const row = all.nth(i)
    const btn = row.getByRole('button', { name: /去处理|去反馈/ }).first()
    if (!(await btn.count()) || !(await btn.isVisible().catch(() => false))) continue
    await btn.click()
    const drawer = drawerOf(page)
    await drawer.waitFor({ state: 'visible', timeout: 20000 })
    await drawer
      .locator('.drawer-section, .dept-sub-panel')
      .first()
      .waitFor({ state: 'visible', timeout: 20000 })
      .catch(() => {})
    await page.waitForTimeout(250)
    const ok = await match(drawer).catch(() => false)
    if (ok) return { drawer, rowIndex: i }
    await closeDrawer(page, drawer)
  }
  throw new Error(`遍历前 ${n} 条待办，没有找到符合条件的详情`)
}

/** antd v4 下拉：点开 → 在可见 dropdown 里按文本选一项 */
async function pickSelect(page, selectLocator, textRe) {
  await selectLocator.locator('.ant-select-selector').click()
  const dd = page.locator('.ant-select-dropdown:visible').last()
  await dd.waitFor({ state: 'visible', timeout: 10000 })
  const option = dd.locator('.ant-select-item-option').filter({ hasText: textRe }).first()
  await option.waitFor({ state: 'visible', timeout: 10000 })
  const label = (await option.innerText()).replace(/\s+/g, ' ').trim()
  await option.click()
  await dd.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {})
  return label
}

async function waitMessage(page, re, timeout = 15000) {
  const notice = page.locator('.ant-message-notice-content').filter({ hasText: re }).last()
  await notice.waitFor({ state: 'visible', timeout })
  return (await notice.innerText()).replace(/\s+/g, ' ').trim()
}

let page
let browser
// 本次运行的唯一标记：多次运行会留下历史部门沟通待办，
// 部门侧三个阶段都按它打开「本次」那条，而不是列表里的任意一条。
let mark = ''

/**
 * 切换账号必须换浏览器上下文：cookie 是 context 级的，
 * 同一个 context 里访问 /#/login 会被路由守卫直接带回上一个账号的待办，
 * 结果是「登录 alice，实际仍以 manager 身份操作」。
 */
async function loginAs(userId) {
  if (page) {
    await page.context().close().catch(() => {})
  }
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  page = await context.newPage()
  page.setDefaultTimeout(20000)
  attachNet(page)
  page.on('console', (m) => {
    if (m.type() !== 'error') return
    const text = m.text()
    if (IGNORED.some((r) => r.test(text))) return
    consoleErrors.push(text)
  })
  page.on('pageerror', (e) => consoleErrors.push(`PAGEERROR: ${e.message}`))
  return login(page, userId)
}

async function main() {
  log(`\n部门沟通全链路冒烟  ${BASE}`)
  log('─'.repeat(78))

  // 0. 准备数据
  log('\n[0] 准备一条 manager 待办（API，非 UI）')
  let prepared
  await step('启动申请流程并制造经理待办', async () => {
    prepared = await prepareManagerTodo()
    mark = prepared.pid.slice(0, 8)
    return `实例 ${prepared.pid} / 任务 ${prepared.taskId} / 标记 ${mark}`
  })

  browser = await chromium.launch({
    executablePath: EDGE,
    headless: HEADLESS,
    args: ['--no-sandbox', '--disable-dev-shm-usage']
  })

  // 1. manager 发起部门沟通
  log('\n[1] manager：打开待办 → 部门沟通')
  await step('登录 manager 并打开可发起沟通的待办', async () => {
    await loginAs('manager')
    const { drawer } = await openDrawerWhere(page, async (d) => {
      const b = d.getByRole('button', { name: /部门沟通/ })
      return (await b.count()) > 0 && !(await b.isDisabled())
    })
    await shot(page, '01-manager-待办详情')
    const title = (await drawer.locator('.ant-drawer-title').innerText().catch(() => '')).trim()
    return `抽屉「${title}」`
  })

  let deptRecordHint = ''
  await step('选中 IT 部 + 通用流程并发起', async () => {
    const drawer = drawerOf(page)
    await drawer.getByRole('button', { name: /部门沟通/ }).click()
    const modal = page.locator('.ant-modal:visible').filter({ hasText: '部门沟通' }).last()
    await modal.waitFor({ state: 'visible', timeout: 15000 })

    const deptLabel = await pickSelect(page, modal.locator('.ant-select').nth(0), DEPT_NAME)
    // 该部门只有一个可选流程时前端会自动选中；没自动选就手动选第一条
    await page.waitForTimeout(800)
    let spText = (await modal.locator('.ant-select').nth(1).innerText().catch(() => '')).trim()
    if (!spText) {
      const first = page.locator('.ant-select-dropdown:visible .ant-select-item-option').first()
      await modal.locator('.ant-select').nth(1).locator('.ant-select-selector').click()
      await first.waitFor({ state: 'visible', timeout: 10000 })
      await first.click()
      spText = (await modal.locator('.ant-select').nth(1).innerText()).trim()
    }
    await modal
      .locator('textarea')
      .first()
      .fill(`冒烟脚本：请 IT 部确认服务器扩容方案与执行窗口。（${mark}）`)
    await shot(page, '02-部门沟通弹窗')

    await modal.locator('.ant-modal-footer').getByRole('button', { name: /发\s*起/ }).click()
    const note = await waitMessage(page, /部门沟通已发起|无法|失败/)
    await modal.waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {})
    if (/无法|失败/.test(note)) throw new Error(note)
    deptRecordHint = note
    return `${deptLabel} / ${spText} / ${note}`
  })

  await step('主待办进入锁定态（通过审批不可点）', async () => {
    const drawer = drawerOf(page)
    // 发起成功后抽屉会重新拉详情，等「通过审批」出现再判定
    const approve = drawer.getByRole('button', { name: /通过审批/ }).first()
    await approve.waitFor({ state: 'visible', timeout: 15000 })
    if (await approve.isDisabled()) return '通过审批已禁用（沟通锁定生效）'
    // 锁定后按钮文案/位置可能变，二次确认沟通状态接口
    const status = await prepared.manager.get(`/api/comm/status/${prepared.taskId}`)
    if (status.bizStatus === 'COMMUNICATING') return '接口 bizStatus=COMMUNICATING（按钮仍可点，需排查前端）'
    throw new Error(`部门沟通发起后主待办未锁定：bizStatus=${status.bizStatus}`)
  })
  await shot(page, '03-manager-已锁定')
  await closeDrawer(page, drawerOf(page))

  // 2. alice 分发
  log('\n[2] alice（IT 部文书）：分发经办人')
  await step('登录 alice，找到部门分发待办', async () => {
    await loginAs('alice')
    const { drawer } = await openDrawerWhere(page, async (d) => {
      if (!(await d.locator('.dept-sub-panel').count())) return false
      if (!(await d.locator('.dept-sub-panel').innerText()).includes(mark)) return false
      return (await d.locator('.panel-title').innerText().catch(() => '')).includes('选择经办人')
    })
    await shot(page, '04-alice-分发表单')
    return '面板标题「选择经办人」'
  })

  await step('选择 bob 并要求全部反馈，提交分发', async () => {
    const drawer = drawerOf(page)
    const panel = drawer.locator('.dept-sub-panel')
    const picked = await pickSelect(page, panel.locator('.ant-select').first(), new RegExp(`(${HANDLER})`))
    // 多选下拉选完不会自动收起。注意不能用 Escape 收：antd Drawer 默认 keyboard=true，
    // Escape 会把整个抽屉关掉，后面 fill/click 全部落空（上一轮就是这么失败的）。
    // 点面板标题这类空白区域才是「点外面收起下拉」。
    await panel.locator('.panel-title').click({ force: true }).catch(() => {})
    await page
      .locator('.ant-select-dropdown:visible')
      .first()
      .waitFor({ state: 'hidden', timeout: 3000 })
      .catch(() => {})
    const chips = await panel.locator('.ant-select-selection-item').allInnerTexts().catch(() => [])
    log(`    [ui] 下拉点中「${picked}」，已选标签 ${JSON.stringify(chips)}`)
    const drawerStillOpen = await drawer.isVisible().catch(() => false)
    if (!drawerStillOpen) throw new Error('抽屉已被意外关闭（检查是否误发 Escape）')
    if (!chips.length) throw new Error('多选框内没有已选标签：经办人没有写入 v-model，提交必被前端拦下')

    await panel.locator('textarea').first().fill('冒烟脚本：请核对扩容清单后反馈')
    await shot(page, '05-alice-已选经办人')

    const before = netlog.length
    const respP = page
      .waitForResponse((r) => /\/comm\/dept-sub\/[^/]+\/dispatch/.test(r.url()), { timeout: 15000 })
      .catch(() => null)
    const clickErr = await drawer
      .getByRole('button', { name: /^提\s*交$/ })
      .click({ timeout: 8000 })
      .then(() => '')
      .catch((e) => String(e.message || e).split('\n')[0])
    if (clickErr) log(`    [ui] 点击「提交」失败：${clickErr}`)

    const hitResp = await respP
    if (hitResp) log(`    [ui] 捕获到分发响应 HTTP ${hitResp.status()}`)
    const entries = dumpNet(before)
    const notices = await noticesOf(page)
    if (notices.length) log(`    [ui] 界面提示：${notices.join(' / ')}`)

    const dispatch = entries.find((e) => /\/dispatch/.test(e.path))
    if (!dispatch) {
      // 最常见：前端必填校验直接 return，请求根本没出去
      throw new Error(
        `未发出分发请求${clickErr ? `（点击失败：${clickErr}）` : ''}；界面提示：${notices.join(' / ') || '（无）'}`
      )
    }
    if (dispatch.status === null) {
      throw new Error(`分发请求无响应：${dispatch.failed || '等待响应超时'}`)
    }
    if (dispatch.status >= 400) {
      throw new Error(`分发接口 HTTP ${dispatch.status}：${dispatch.body.slice(0, 300) || '（无报文）'}`)
    }
    if (/"error"\s*:\s*true/.test(dispatch.body)) {
      throw new Error(`分发接口返回业务失败：${dispatch.body.slice(0, 300)}`)
    }
    await shot(page, '05b-alice-已分发')
    await drawer.waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {})
    return `HTTP ${dispatch.status}，${notices[0] || '无提示'}`
  })

  // 3. bob 经办
  log('\n[3] bob（IT 部成员）：提交经办意见')
  await step('登录 bob，打开经办意见面板', async () => {
    await loginAs('bob')
    const { drawer } = await openDrawerWhere(page, async (d) => {
      if (!(await d.locator('.dept-sub-panel').count())) return false
      if (!(await d.locator('.dept-sub-panel').innerText()).includes(mark)) return false
      return (await d.locator('.panel-title').innerText().catch(() => '')).includes('经办意见')
    })
    await shot(page, '06-bob-经办表单')
    return '面板标题「经办意见」'
  })

  await step('填写意见并提交', async () => {
    const drawer = drawerOf(page)
    await drawer.locator('.dept-sub-panel textarea').first().fill('扩容清单无误，建议下周一 02:00 执行。')
    await shot(page, '07-bob-已填写')
    const before = netlog.length
    await drawer.getByRole('button', { name: /^提\s*交$/ }).click()
    await page.waitForTimeout(800)
    const notes = await noticesOf(page)
    dumpNet(before)
    const note = notes[0] || '（未出现提示）'
    if (/失败|无法|请填写|请至少/.test(note)) throw new Error(note)
    await drawer.waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {})
    return note
  })

  // 4. alice 汇总
  log('\n[4] alice（IT 部文书）：汇总并提交部门意见')
  await step('alice 刷新待办，打开汇总面板并确认 bob 的意见', async () => {
    await loginAs('alice')
    const { drawer } = await openDrawerWhere(page, async (d) => {
      if (!(await d.locator('.dept-sub-panel').count())) return false
      if (!(await d.locator('.dept-sub-panel').innerText()).includes(mark)) return false
      const t = await d.locator('.panel-title').innerText().catch(() => '')
      return t.includes('汇总')
    })
    const body = await drawer.locator('.dept-sub-panel').innerText()
    if (!body.includes('扩容清单无误')) {
      throw new Error('汇总面板没有显示 bob 的经办意见')
    }
    await shot(page, '08-alice-汇总')
    return '已显示 bob 的意见'
  })

  await step('填写部门最终意见并提交', async () => {
    const drawer = drawerOf(page)
    const panel = drawer.locator('.dept-sub-panel')
    await panel.locator('textarea').first().fill('IT 部意见：扩容方案可行，同意下周一 02:00 执行。')
    await shot(page, '09-alice-汇总填写')
    const before = netlog.length
    await drawer.getByRole('button', { name: /^提\s*交$/ }).click()
    await page.waitForTimeout(800)
    const notes = await noticesOf(page)
    dumpNet(before)
    const note = notes[0] || '（未出现提示）'
    if (/失败|无法|请填写|请至少/.test(note)) throw new Error(note)
    await drawer.waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {})
    return note
  })

  // 5. manager 解锁
  log('\n[5] manager：确认主待办解锁')
  await step('刷新待办，确认「通过审批」恢复可点且意见已回填', async () => {
    await loginAs('manager')

    // 找「通过审批可点 + 详情里含 IT 部沟通记录」的那条：这就是本次实例被解锁后的样子。
    // 不按行内短 ID 匹配，因为表格对实例 ID 做了截断，和 API 返回的完整 ID 不一定同形。
    const { drawer: openedDrawer } = await openDrawerWhere(page, async (d) => {
      const b = d.getByRole('button', { name: /通过审批/ }).first()
      if (!(await b.count()) || (await b.isDisabled())) return false
      const text = await d.innerText().catch(() => '')
      return text.includes('IT 部') || text.includes('扩容')
    })

    const approve = openedDrawer.getByRole('button', { name: /通过审批/ }).first()
    await approve.waitFor({ state: 'visible', timeout: 15000 })
    const disabled = await approve.isDisabled()
    const body = await openedDrawer.innerText()
    await shot(page, '10-manager-已解锁')
    if (disabled) throw new Error('通过审批仍不可点：主待办没有解锁')
    const filled = body.includes('IT 部意见') || body.includes('扩容方案可行')
    if (!filled) throw new Error('通过审批可点，但详情里看不到 IT 部回填的部门意见')

    // 状态接口只做交叉验证，放在最后：本系统同账号二次登录会顶掉旧会话，
    // manager 在 step 1/5 都做过 UI 登录，step 0 建的 API 会话 401 属预期，不算失败。
    let biz = '未校验（API 会话已被 UI 登录顶掉）'
    try {
      const status = await prepared.manager.get(`/api/comm/status/${prepared.taskId}`)
      biz = status.bizStatus
      if (biz !== 'NORMAL' && biz !== 'REPLIED') throw new Error(`沟通状态未回到可审批：${biz}`)
    } catch (e) {
      const msg = String(e.message || e)
      if (!/该账号已在其它设备登录/.test(msg)) throw e
      log('    [net] 状态接口 401：API 会话被同账号 UI 登录顶掉，属单点登录预期')
    }
    return `通过审批可点，部门意见已回填，bizStatus=${biz}`
  })

  // 6. 交叉验证：浏览器关掉后用新登录的 API 会话查一次状态。
  // 必须在关浏览器之后做——同账号二次登录会顶掉旧会话，先查会把界面顶下线。
  log('\n[6] 交叉验证：沟通状态回到可审批')
  await step('关浏览器后重新登录 API，查 bizStatus', async () => {
    await browser.close().catch(() => {})
    const manager = await apiLogin('manager')
    const status = await manager.get(`/api/comm/status/${prepared.taskId}`)
    if (status.bizStatus !== 'NORMAL' && status.bizStatus !== 'REPLIED') {
      throw new Error(`沟通状态未回到可审批：bizStatus=${status.bizStatus}`)
    }
    return `实例 ${prepared.pid} / bizStatus=${status.bizStatus}`
  })

  await browser.close().catch(() => {})
}

function report() {
  const pad = (s, n) => {
    let w = 0
    for (const ch of String(s)) w += /[\u4e00-\u9fa5（）：]/.test(ch) ? 2 : 1
    return String(s) + ' '.repeat(Math.max(0, n - w))
  }
  log('\n' + '═'.repeat(78))
  log(`${pad('步骤', 34)}${pad('结果', 8)}${pad('耗时', 9)}说明`)
  log('─'.repeat(78))
  for (const r of results) {
    log(`${pad(r.name, 34)}${pad(r.ok ? '通过' : '失败', 8)}${pad(r.ms ? `${(r.ms / 1000).toFixed(1)}s` : '-', 9)}${r.detail}`)
  }
  log('─'.repeat(78))
  const bad = results.filter((r) => !r.ok)
  log(`合计 ${results.length} 步：通过 ${results.length - bad.length}，失败 ${bad.length}`)
  if (consoleErrors.length) {
    log(`\nConsole 报错 ${consoleErrors.length} 条（已过滤 auth/me 与 favicon）：`)
    for (const e of consoleErrors.slice(0, 8)) log(`  - ${e}`)
  }
  log(`\n截图目录：${SHOT_DIR}`)
  return bad.length
}

try {
  await main()
} catch (e) {
  log(`\n冒烟中断：${(e.message || e).split('\n')[0]}`)
  if (page) {
    await shot(page, '99-failure').catch(() => {})
  }
}
const failures = report()
process.exit(failures ? 1 : 0)
