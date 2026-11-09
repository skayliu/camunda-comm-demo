// 部门专属子流程（财务部预算审核）真机冒烟：manager 发起 → henry 预算专员初审 → mallory 资金岗确认
// → henry 财务经理审批 → henry 财务文书汇总出意见 → manager 主待办解锁。
//
// 三条流程的分工（同一套脚本外壳，只换流程常量与账号）：
//   dept-comm-smoke.mjs          通用三节点（DISPATCH / HANDLER / SUMMARY）
//   dept-comm-legal-smoke.mjs    法务专属（NODE ×3 + SUMMARY）
//   dept-comm-finance-smoke.mjs  财务专属（NODE ×3 + SUMMARY），本文件
//
// 与法务脚本的差异只有三处内容，其余骨架刻意保持一致，便于对照排查：
//   1. 部门 / 子流程名 / 三个岗位账号；
//   2. 四个节点的面板标题与意见文本；
//   3. 发起内容与最终意见的关键词（manager 侧回填校验用）。
//
// 运行前要求与应用启动方式同 dept-comm-smoke.mjs。
//
// 用法：
//   node scripts/dept-comm-finance-smoke.mjs
//   SMOKE_HEADED=1 node scripts/dept-comm-finance-smoke.mjs
//
// 演示数据里的岗位分配（见 DemoUserInitializer）：
//   henry   → dept_finance, dept_finance_clerk, finance_budget, finance_manager
//             预算专员初审 / 财务经理审批 / 财务文书汇总
//   mallory → dept_finance, finance_treasury
//             资金岗确认
// 所以 henry 要连续办三个节点（跨两次重新登录）：这不是脚本取巧，是演示数据把
// 三个岗位压在一个人身上。真实组织里换成三个账号即可，节点与账号的映射见 NODES。
//
// 两个继承自通用脚本的坑，别踩回去：
//   1. 不能用 Escape 收 antd 下拉：会关掉整个 Drawer；
//   2. 同账号二次登录会顶掉旧会话，状态查询必须放在 browser.close() 之后。
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const BASE = process.env.SMOKE_BASE || 'http://localhost:8080/app'
const ORIGIN = new URL(BASE).origin
const PASS = process.env.SMOKE_PASS || 'demo'
const HEADLESS = process.env.SMOKE_HEADED !== '1'
const DEPT_NAME = process.env.SMOKE_FINANCE_DEPT || '财务部'
const SP_NAME = process.env.SMOKE_FINANCE_SP || '财务部预算审核流程'
const BUDGET_USER = process.env.SMOKE_BUDGET_USER || 'henry'
const TREASURY_USER = process.env.SMOKE_TREASURY_USER || 'mallory'
const MANAGER_USER = process.env.SMOKE_FINANCE_MANAGER_USER || 'henry'
const CLERK_USER = process.env.SMOKE_FINANCE_CLERK_USER || 'henry'
const EDGE =
  process.env.SMOKE_BROWSER ||
  String.raw`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = path.join(HERE, 'e2e-shots', 'dept-finance')
fs.mkdirSync(SHOT_DIR, { recursive: true })
for (const f of fs.readdirSync(SHOT_DIR)) {
  if (f.endsWith('.png')) fs.rmSync(path.join(SHOT_DIR, f))
}

// 财务专属流程的节点清单：面板标题 = BPMN 里的 userTask name。
// taskKey 只用于注释与失败时定位，脚本本身按标题 + 本次标记匹配待办。
const NODES = [
  { taskKey: 'Task_FinanceBudget', title: '预算专员初审', group: 'finance_budget', user: BUDGET_USER, opinion: '预算初审：本年度服务器扩容预算 12.6 万元，科目与额度均可覆盖，同意进入资金确认。', shot: '04-henry-预算初审' },
  { taskKey: 'Task_FinanceTreasury', title: '资金岗确认', group: 'finance_treasury', user: TREASURY_USER, opinion: '资金岗确认：本季度资金余额充足，可一次性支付，建议避开月末结账窗口。', shot: '06-mallory-资金岗确认' },
  { taskKey: 'Task_FinanceManager', title: '财务经理审批', group: 'finance_manager', user: MANAGER_USER, opinion: '财务经理审批：同意支付，按资金岗建议安排在下月初执行，付款前补齐验收单。', shot: '08-henry-经理审批' }
]

const FINAL_OPINION = '财务部最终意见：预算与资金均无异议，同意支付 12.6 万元，下月初执行。'

const results = []
const consoleErrors = []
const IGNORED = [/\/api\/auth\/me/, /favicon/i, /401 \(Unauthorized\)/]

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

// ── API 侧 ─────────────────────────────────────────────────────────────
function makeApiSession() {
  // jar 必须在每次 login 内部新建：复用同一个 jar 会把上一段的 JSESSIONID 带进来，
  // 而该会话很可能已被 UI 登录顶掉。ConcurrentSessionFilter 看到过期会话会直接返回
  // 401（"该账号已在其它设备登录"），请求根本到不了登录逻辑，表现为「密码正确却 401」。
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
    const csrfBody = await res.text()
    if (!res.ok) throw new Error(`CSRF 获取失败 ${res.status}：${csrfBody.slice(0, 200)}`)
    const { token } = JSON.parse(csrfBody || '{}')
    if (!token) throw new Error(`CSRF 响应里没有 token：${csrfBody.slice(0, 200)}`)
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
    if (!res.ok) {
      const body = await res.text().catch(() => '')
      throw new Error(`API 登录失败 ${userId}: ${res.status} ${body.slice(0, 200)}`)
    }

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

/** 造一条 manager 待办（与另外两个脚本同一套，保证三条链路起点一致） */
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
  await page.locator('.ant-drawer-mask:visible').first().waitFor({ state: 'hidden', timeout: 3000 }).catch(() => {})
}

// limit 默认给 12（法务脚本是 8）：henry 同时挂三个岗位，历史待办比 frank 多。
async function openDrawerWhere(page, match, limit = 12) {
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
      .locator('.dept-sub-panel, .drawer-section')
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

let page
let browser
let mark = ''

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

/**
 * 打开「本次运行」的部门子流程待办，并校验面板标题。
 *
 * 专属流程的中间节点面板标题就是 taskName（预算专员初审 / 资金岗确认 / 财务经理审批），
 * 汇总节点是固定的「经办意见汇总」；用 mark 定位本次实例，避免历史待办干扰。
 */
async function openDeptSubTask(expectedTitle, shotName) {
  const { drawer } = await openDrawerWhere(page, async (d) => {
    if (!(await d.locator('.dept-sub-panel').count())) return false
    const text = await d.locator('.dept-sub-panel').innerText().catch(() => '')
    if (!text.includes(mark)) return false
    const title = await d.locator('.panel-title').first().innerText().catch(() => '')
    return title.includes(expectedTitle)
  })
  if (shotName) await shot(page, shotName)
  return drawer
}

/**
 * 填内容 → 点页脚提交 → 用网络报文判定成功与否。
 * 三种失败形态分开报：没发请求（前端校验）/ 4xx-5xx / 200 但 error=true。
 */
async function fillAndSubmit(drawer, text, apiRe, shotName) {
  const panel = drawer.locator('.dept-sub-panel')
  if (text !== undefined) {
    await panel.locator('textarea').first().fill(text)
  }
  if (shotName) await shot(page, shotName)

  const before = netlog.length
  const respP = page.waitForResponse((r) => apiRe.test(r.url()), { timeout: 15000 }).catch(() => null)
  const clickErr = await drawer
    .getByRole('button', { name: /^提\s*交$/ })
    .click({ timeout: 8000 })
    .then(() => '')
    .catch((e) => String(e.message || e).split('\n')[0])
  if (clickErr) log(`    [ui] 点击「提交」失败：${clickErr}`)

  const hitResp = await respP
  if (hitResp) log(`    [ui] 捕获到提交响应 HTTP ${hitResp.status()}`)
  await page.waitForTimeout(700)
  const entries = dumpNet(before)
  const notices = await noticesOf(page)
  if (notices.length) log(`    [ui] 界面提示：${notices.join(' / ')}`)

  const hit = entries.find((e) => apiRe.test(e.path))
  if (!hit) {
    throw new Error(
      `未发出提交请求${clickErr ? `（点击失败：${clickErr}）` : ''}；界面提示：${notices.join(' / ') || '（无）'}`
    )
  }
  if (hit.status === null) throw new Error(`提交请求无响应：${hit.failed || '等待响应超时'}`)
  if (hit.status >= 400) throw new Error(`提交接口 HTTP ${hit.status}：${hit.body.slice(0, 300) || '（无报文）'}`)
  if (/"error"\s*:\s*true/.test(hit.body)) throw new Error(`提交返回业务失败：${hit.body.slice(0, 300)}`)

  await drawer.waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {})
  return `HTTP ${hit.status}，${notices[0] || '无提示'}`
}

async function main() {
  log(`\n财务部专属子流程冒烟  ${BASE}`)
  log('─'.repeat(78))

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

  // 1. manager 发起财务部专属沟通
  log('\n[1] manager：打开待办 → 部门沟通（财务部 / 专属流程）')
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

  await step(`选中「${DEPT_NAME}」+「${SP_NAME}」并发起`, async () => {
    const drawer = drawerOf(page)
    await drawer.getByRole('button', { name: /部门沟通/ }).click()
    const modal = page.locator('.ant-modal:visible').filter({ hasText: '部门沟通' }).last()
    await modal.waitFor({ state: 'visible', timeout: 15000 })

    const deptLabel = await pickSelect(page, modal.locator('.ant-select').nth(0), DEPT_NAME)
    // 财务部同样可选「通用 + 专属」两条，不会自动选中，必须显式选专属流程
    await page.waitForTimeout(900)
    const spLabel = await pickSelect(page, modal.locator('.ant-select').nth(1), SP_NAME)
    await modal
      .locator('textarea')
      .first()
      .fill(`冒烟脚本：财务部审核服务器扩容预算 12.6 万元，请按流程出具意见。（${mark}）`)
    await shot(page, '02-部门沟通弹窗')

    await modal.locator('.ant-modal-footer').getByRole('button', { name: /发\s*起/ }).click()
    await page.waitForTimeout(900)
    const notes = await noticesOf(page)
    const note = notes[0] || ''
    await modal.waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {})
    if (!note.includes('部门沟通已发起')) {
      throw new Error(`发起未成功，界面提示：${notes.join(' / ') || '（无）'}`)
    }
    if (!/财务/.test(spLabel)) throw new Error(`子流程选中的不是财务专属流程：${spLabel}`)
    return `${deptLabel} / ${spLabel} / ${note}`
  })

  await step('主待办进入锁定态（通过审批不可点）', async () => {
    const drawer = drawerOf(page)
    const approve = drawer.getByRole('button', { name: /通过审批/ }).first()
    await approve.waitFor({ state: 'visible', timeout: 15000 })
    await page.waitForTimeout(400)
    if (await approve.isDisabled()) return '通过审批已禁用（专属流程锁定生效）'
    throw new Error('发起专属流程后主待办未锁定')
  })
  await shot(page, '03-manager-已锁定')
  await closeDrawer(page, drawerOf(page))

  // 2~4. 三个 NODE 节点，按 NODES 顺序逐个办理
  const nodeShots = ['05-henry-初审已填写', '07-mallory-确认已填写', '09-henry-审批已填写']
  for (let i = 0; i < NODES.length; i++) {
    const n = NODES[i]
    const seq = i + 2
    log(`\n[${seq}] ${n.user}（${n.group}）：${n.title}`)
    await step(`登录 ${n.user}，打开「${n.title}」面板`, async () => {
      await loginAs(n.user)
      await openDeptSubTask(n.title, n.shot)
      return `面板标题「${n.title}」`
    })
    await step(`提交${n.title}意见`, async () => {
      const drawer = drawerOf(page)
      const body = await drawer.locator('.dept-sub-panel').innerText()
      if (!body.includes(mark)) throw new Error('面板里的沟通内容不含本次标记，可能打开的是历史待办')
      return fillAndSubmit(drawer, n.opinion, /\/dept-sub\/[^/]+\/node-opinion/, nodeShots[i])
    })
  }

  // 5. 财务文书汇总（专属流程的 SUMMARY 节点 → Task_FinanceSummary）
  log(`\n[5] ${CLERK_USER}（dept_finance_clerk）：财务文书汇总出意见（汇总节点）`)
  await step('打开汇总面板，确认三个节点意见已串起来', async () => {
    await loginAs(CLERK_USER)
    const drawer = await openDeptSubTask('经办意见汇总', '10-henry-汇总')
    const body = await drawer.locator('.dept-sub-panel').innerText()
    const missing = NODES.filter((n) => !body.includes(n.opinion)).map((n) => n.title)
    if (missing.length) {
      throw new Error(`汇总面板缺少节点意见：${missing.join('、')}（opinionMap 未按节点回写？）`)
    }
    return '三个节点意见均显示在汇总面板'
  })
  await step('填写部门最终意见并提交', async () => {
    const drawer = drawerOf(page)
    return fillAndSubmit(drawer, FINAL_OPINION, /\/dept-sub\/[^/]+\/summary/, '11-henry-最终意见')
  })

  // 6. manager 解锁
  log('\n[6] manager：确认主待办解锁且财务意见已回填')
  await step('刷新待办，确认「通过审批」恢复可点', async () => {
    await loginAs('manager')
    const { drawer: openedDrawer } = await openDrawerWhere(page, async (d) => {
      const b = d.getByRole('button', { name: /通过审批/ }).first()
      if (!(await b.count()) || (await b.isDisabled())) return false
      const text = await d.innerText().catch(() => '')
      return text.includes('财务部') || text.includes('预算')
    })
    const approve = openedDrawer.getByRole('button', { name: /通过审批/ }).first()
    await approve.waitFor({ state: 'visible', timeout: 15000 })
    if (await approve.isDisabled()) throw new Error('通过审批仍不可点：主待办没有解锁')
    const body = await openedDrawer.innerText()
    await shot(page, '12-manager-已解锁')
    if (!body.includes('财务部最终意见') && !body.includes('同意支付 12.6 万元')) {
      throw new Error('通过审批可点，但详情里看不到财务部回填的最终意见')
    }
    return '通过审批可点，财务部最终意见已回填'
  })

  // 7. 交叉验证（必须在关浏览器之后，避免同账号登录互踢）
  log('\n[7] 交叉验证：沟通状态回到可审批')
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
  log(`${pad('步骤', 40)}${pad('结果', 8)}${pad('耗时', 9)}说明`)
  log('─'.repeat(78))
  for (const r of results) {
    log(`${pad(r.name, 40)}${pad(r.ok ? '通过' : '失败', 8)}${pad(r.ms ? `${(r.ms / 1000).toFixed(1)}s` : '-', 9)}${r.detail}`)
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
