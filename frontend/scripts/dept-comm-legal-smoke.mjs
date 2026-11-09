// 部门专属子流程（法务部合同审查）真机冒烟：manager 发起 → grace 合规初审 → frank 业务线审查
// → frank 经理复核 → frank 出具正式法律意见 → manager 主待办解锁。
//
// 与 dept-comm-smoke.mjs 的分工：
//   dept-comm-smoke.mjs       走通用三节点流程（文书分发 / 经办人 / 汇总），覆盖 DISPATCH、HANDLER、SUMMARY；
//   dept-comm-legal-smoke.mjs 走法务专属流程，覆盖 NODE 分支（中间节点）与「专属流程的 SUMMARY 节点」
//                             （Task_LegalSummary 的 taskDefKey 以 Summary 结尾，nodeType 判为 SUMMARY）。
//
// 运行前要求与应用启动方式同 dept-comm-smoke.mjs。
//
// 用法：
//   node scripts/dept-comm-legal-smoke.mjs
//   SMOKE_HEADED=1 node scripts/dept-comm-legal-smoke.mjs
//
// 演示数据里的岗位分配（见 DemoUserInitializer）：
//   grace → dept_legal, legal_compliance          合规初审
//   frank → dept_legal, dept_legal_clerk,         业务线专项审查 / 法务经理复核 / 文书汇总
//           legal_business, legal_manager
// 所以 frank 要连续办三个节点：这不是脚本取巧，是演示数据把三个岗位压在一个人身上。
//
// 两个继承自上一个脚本的坑，别踩回去：
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
const DEPT_NAME = process.env.SMOKE_LEGAL_DEPT || '法务部'
const SP_NAME = process.env.SMOKE_LEGAL_SP || '法务部合同审查流程'
const COMPLIANCE_USER = process.env.SMOKE_COMPLIANCE_USER || 'grace'
const LEGAL_USER = process.env.SMOKE_LEGAL_USER || 'frank'
const EDGE =
  process.env.SMOKE_BROWSER ||
  String.raw`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SHOT_DIR = path.join(HERE, 'e2e-shots', 'dept-legal')
fs.mkdirSync(SHOT_DIR, { recursive: true })
for (const f of fs.readdirSync(SHOT_DIR)) {
  if (f.endsWith('.png')) fs.rmSync(path.join(SHOT_DIR, f))
}

// 三个节点的意见文本；最后在汇总面板里逐个断言，确保 opinionMap 真的串起来了
const OPINIONS = {
  compliance: '合规初审：合同主体资质齐全，付款条款需补充逾期违约金约定。',
  business: '业务线审查：交付节点与验收标准一致，建议保留原定分批交付安排。',
  manager: '经理复核：同意签署，按合规初审意见补充违约金条款后用印。',
  final: '法务部最终意见：合同可签，须在下单前补签违约金补充条款。'
}

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

/** 造一条 manager 待办（与通用流程脚本同一套，保证两条链路起点一致） */
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
 * 专属流程的面板标题就是 taskName（合规初审 / 业务线专项审查 / 法务经理复核），
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
  log(`\n法务部专属子流程冒烟  ${BASE}`)
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

  // 1. manager 发起法务部专属沟通
  log('\n[1] manager：打开待办 → 部门沟通（法务部 / 专属流程）')
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
    // 法务部可选「通用 + 专属」两条，不会自动选中，必须显式选专属流程
    await page.waitForTimeout(900)
    const spLabel = await pickSelect(page, modal.locator('.ant-select').nth(1), SP_NAME)
    await modal
      .locator('textarea')
      .first()
      .fill(`冒烟脚本：法务部审查《服务器采购合同》，请按流程出具意见。（${mark}）`)
    await shot(page, '02-部门沟通弹窗')

    await modal.locator('.ant-modal-footer').getByRole('button', { name: /发\s*起/ }).click()
    await page.waitForTimeout(900)
    const notes = await noticesOf(page)
    const note = notes[0] || ''
    await modal.waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {})
    if (!note.includes('部门沟通已发起')) {
      throw new Error(`发起未成功，界面提示：${notes.join(' / ') || '（无）'}`)
    }
    if (!/法务/.test(spLabel)) throw new Error(`子流程选中的不是法务专属流程：${spLabel}`)
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

  // 2. grace 合规初审（NODE 分支）
  log('\n[2] grace（legal_compliance）：合规初审')
  await step('登录 grace，打开「合规初审」面板', async () => {
    await loginAs(COMPLIANCE_USER)
    await openDeptSubTask('合规初审', '04-grace-合规初审')
    return '面板标题「合规初审」'
  })
  await step('提交合规初审意见', async () => {
    const drawer = drawerOf(page)
    const body = await drawer.locator('.dept-sub-panel').innerText()
    if (!body.includes(mark)) throw new Error('面板里的沟通内容不含本次标记，可能打开的是历史待办')
    const detail = await fillAndSubmit(drawer, OPINIONS.compliance, /\/dept-sub\/[^/]+\/node-opinion/, '05-grace-已填写')
    return detail
  })

  // 3. frank 业务线专项审查（NODE 分支，第二岗）
  log('\n[3] frank（legal_business）：业务线专项审查')
  await step('登录 frank，打开「业务线专项审查」面板', async () => {
    await loginAs(LEGAL_USER)
    await openDeptSubTask('业务线专项审查', '06-frank-业务线审查')
    return '面板标题「业务线专项审查」'
  })
  await step('提交业务线审查意见', async () => {
    const drawer = drawerOf(page)
    const detail = await fillAndSubmit(drawer, OPINIONS.business, /\/dept-sub\/[^/]+\/node-opinion/, '07-frank-业务线已填写')
    return detail
  })

  // 4. frank 法务经理复核（NODE 分支，第三岗，同账号需重新登录）
  log('\n[4] frank（legal_manager）：法务经理复核')
  await step('重新登录 frank，打开「法务经理复核」面板', async () => {
    await loginAs(LEGAL_USER)
    await openDeptSubTask('法务经理复核', '08-frank-经理复核')
    return '面板标题「法务经理复核」'
  })
  await step('提交复核结论', async () => {
    const drawer = drawerOf(page)
    const detail = await fillAndSubmit(drawer, OPINIONS.manager, /\/dept-sub\/[^/]+\/node-opinion/, '09-frank-复核已填写')
    return detail
  })

  // 5. frank 出具正式法律意见（专属流程的汇总节点 → SUMMARY 分支）
  log('\n[5] frank（法务部文书）：出具正式法律意见（汇总节点）')
  await step('打开汇总面板，确认三个节点意见已串起来', async () => {
    await loginAs(LEGAL_USER)
    const drawer = await openDeptSubTask('经办意见汇总', '10-frank-汇总')
    const body = await drawer.locator('.dept-sub-panel').innerText()
    const missing = [
      ['合规初审', OPINIONS.compliance],
      ['业务线专项审查', OPINIONS.business],
      ['法务经理复核', OPINIONS.manager]
    ].filter(([, text]) => !body.includes(text)).map(([name]) => name)
    if (missing.length) {
      throw new Error(`汇总面板缺少节点意见：${missing.join('、')}（opinionMap 未按节点回写？）`)
    }
    return '三个节点意见均显示在汇总面板'
  })
  await step('填写部门最终意见并提交', async () => {
    const drawer = drawerOf(page)
    const detail = await fillAndSubmit(drawer, OPINIONS.final, /\/dept-sub\/[^/]+\/summary/, '11-frank-最终意见')
    return detail
  })

  // 6. manager 解锁
  log('\n[6] manager：确认主待办解锁且法务意见已回填')
  await step('刷新待办，确认「通过审批」恢复可点', async () => {
    await loginAs('manager')
    const { drawer: openedDrawer } = await openDrawerWhere(page, async (d) => {
      const b = d.getByRole('button', { name: /通过审批/ }).first()
      if (!(await b.count()) || (await b.isDisabled())) return false
      const text = await d.innerText().catch(() => '')
      return text.includes('法务部') || text.includes('合同')
    })
    const approve = openedDrawer.getByRole('button', { name: /通过审批/ }).first()
    await approve.waitFor({ state: 'visible', timeout: 15000 })
    if (await approve.isDisabled()) throw new Error('通过审批仍不可点：主待办没有解锁')
    const body = await openedDrawer.innerText()
    await shot(page, '12-manager-已解锁')
    if (!body.includes('法务部最终意见') && !body.includes('违约金补充条款')) {
      throw new Error('通过审批可点，但详情里看不到法务部回填的最终意见')
    }
    return '通过审批可点，法务部最终意见已回填'
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
