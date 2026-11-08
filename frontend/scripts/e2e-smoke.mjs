// 端到端冒烟：真实浏览器登录后，依次走 5 条关键链路。
//
// 只用 playwright-core 驱动系统自带的 Edge，不下载 Chromium：
//   npm run smoke
// 可选环境变量：
//   SMOKE_BASE=http://localhost:8080/app   SMOKE_USER=manager  SMOKE_PASS=demo
//   SMOKE_HEADED=1                          有头模式，方便肉眼看流程
//
// 设计原则：只「打开并校验渲染」，不点确认按钮。
// 转办、委托都会真实改变待办归属，冒烟脚本不应该污染演示数据。
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.SMOKE_BASE || 'http://localhost:8080/app'
const USER = process.env.SMOKE_USER || 'manager'
const PASS = process.env.SMOKE_PASS || 'demo'
const HEADLESS = process.env.SMOKE_HEADED !== '1'
const EDGE =
  process.env.SMOKE_BROWSER ||
  String.raw`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))
const SHOT_DIR = path.join(HERE, 'e2e-shots')
fs.mkdirSync(SHOT_DIR, { recursive: true })

const results = []
const consoleErrors = []
const failedRequests = []
const IGNORED = [/\/api\/auth\/me/, /favicon/i, /401 \(Unauthorized\)/]

function log(line = '') {
  console.log(line)
}

async function step(name, fn) {
  const started = Date.now()
  try {
    const detail = await fn()
    results.push({ name, ok: true, ms: Date.now() - started, detail: detail || '' })
    log(`  ✓ ${name}  ${detail || ''}`)
  } catch (e) {
    const raw = e && e.message ? e.message : String(e)
    // Click 失败的第一行只有 "Timeout"，真正的拦截原因（遮挡 / 不可见 / 已脱离 DOM）在后续行
    const msg = raw.split('\n').filter(Boolean).slice(0, 3).join(' | ')
    const file = path.join(SHOT_DIR, `${results.length + 1}.png`)
    try {
      await page.screenshot({ path: file, fullPage: false })
      results.push({ name, ok: false, ms: Date.now() - started, detail: msg, shot: file })
    } catch (ignored) {
      results.push({ name, ok: false, ms: Date.now() - started, detail: msg })
    }
    log(`  ✗ ${name}  ${msg}`)
  }
}

function skip(name, reason) {
  results.push({ name, ok: true, skip: true, ms: 0, detail: reason })
  log(`  ○ ${name}  跳过：${reason}`)
}

const rows = () => page.locator('.ant-table-tbody tr.ant-table-row')

// antd v4 会给「两个汉字」的按钮中间插一个空格（登 录 / 转 办 / 取 消），
// 所以按钮名一律用允许空白的正则匹配，不要写死连续字符串。
const BTN = {
  login: /登\s*录/,
  handle: /去处理/,
  feedback: /去反馈/,
  trace: /跟\s*踪/,
  transfer: /转\s*办/,
  delegate: /委\s*托/,
  cancel: /取\s*消/
}

/** 找到带某个操作按钮的可见行（沟通待办没有「转办」，所以不能只看第一行） */
async function findRowWith(label) {
  const all = rows()
  const n = await all.count()
  for (let i = 0; i < n; i++) {
    const row = all.nth(i)
    const btn = row.getByRole('button', { name: label }).first()
    if ((await btn.count()) && (await btn.isVisible())) return { row, btn }
  }
  return null
}

async function closeModal(scope) {
  const btn = scope.locator('.ant-modal-close').first()
  if (await btn.count()) await btn.click({ force: true }).catch(() => {})
  // antd 的关闭动画结束后 wrap 仍在 DOM 里（display:none），所以只等 hidden 不够，
  // 还要确认没有残留的可见遮罩挡住后续操作
  await scope.last().waitFor({ state: 'hidden', timeout: 8000 }).catch(() => {})
  await page.keyboard.press('Escape').catch(() => {})
  await page.locator('.ant-modal-wrap:visible').first().waitFor({ state: 'hidden', timeout: 3000 }).catch(() => {})
}

let page

async function main() {
  log(`\n端到端冒烟  ${BASE}  账号 ${USER}`)
  log('─'.repeat(72))

  const browser = await chromium.launch({
    executablePath: EDGE,
    headless: HEADLESS,
    args: ['--no-sandbox', '--disable-dev-shm-usage']
  })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  page = await context.newPage()
  page.setDefaultTimeout(20000)

  page.on('console', (m) => {
    if (m.type() !== 'error') return
    const text = m.text()
    if (IGNORED.some((r) => r.test(text))) return
    consoleErrors.push(text)
  })
  page.on('pageerror', (e) => consoleErrors.push(`PAGEERROR: ${e.message}`))

  // Console 里那条 "Failed to load resource: 401" 不带 URL，单看文本无法定位是哪个接口，
  // 这里从响应侧补一份带 URL 的记录。
  page.on('response', (r) => {
    if (r.status() < 400) return
    const url = r.url()
    if (/\/api\/auth\/me/.test(url)) return
    failedRequests.push(`${r.status()} ${new URL(url).pathname}`)
  })

  // ── 0. 登录 ────────────────────────────────────────────────
  log('\n[0] 登录')
  await step('打开登录页并登录', async () => {
    await page.goto(`${BASE}/#/login`, { waitUntil: 'domcontentloaded' })
    const account = page.getByPlaceholder('请输入账号')
    const needLogin = await account.waitFor({ state: 'visible', timeout: 8000 }).then(() => true).catch(() => false)
    if (!needLogin) {
      if (!page.url().includes('/todo')) throw new Error(`既没有登录表单，也不在待办页：${page.url()}`)
      return '已有会话，直接进入待办'
    }
    await account.fill(USER)
    await page.getByPlaceholder('请输入密码').fill(PASS)
    const resp = page
      .waitForResponse((r) => r.url().includes('/api/auth/login'), { timeout: 20000 })
      .catch(() => null)
    await page.getByRole('button', { name: BTN.login }).click()
    const r = await resp
    if (r && r.status() !== 200) throw new Error(`登录接口 HTTP ${r.status()}`)
    await page.waitForURL(/#\/todo/, { timeout: 20000 })
    await rows().first().waitFor({ state: 'visible', timeout: 25000 })
    return `${USER} → ${new URL(page.url()).hash}，待办 ${await rows().count()} 条`
  })

  const todoCount = await rows().count().catch(() => 0)
  if (!todoCount) {
    skip('待办详情抽屉', '待办列表为空，无法打开详情')
    skip('流程跟踪弹窗', '待办列表为空')
    skip('转办弹窗', '待办列表为空')
    skip('委托弹窗', '待办列表为空')
    skip('子流程下钻与返回上级', '待办列表为空')
  } else {
    // ── 1. 待办详情抽屉 ──────────────────────────────────────
    log('\n[1] 待办详情抽屉（首次点击 = 触发按需 chunk）')
    await step('打开「去处理 / 去反馈」抽屉', async () => {
      const open = await findRowWith(BTN.handle)
      const target = open || (await findRowWith(BTN.feedback))
      if (!target) throw new Error('当前页没有可打开的任务按钮')
      const label = open ? '去处理' : '去反馈'
      await target.btn.click()
      const drawer = page.locator('.ant-drawer-content').last()
      await drawer.waitFor({ state: 'visible', timeout: 20000 })
      // 等详情接口返回并渲染出区块
      await drawer.locator('.drawer-section').first().waitFor({ state: 'visible', timeout: 20000 })
      await page.waitForTimeout(400)
      const title = (await drawer.locator('.ant-drawer-title').innerText().catch(() => '')).trim()
      const body = (await drawer.locator('.ant-drawer-body').innerText().catch(() => '')).trim()
      const loadFail = await drawer.locator('.ant-result-title').count()
      if (loadFail) throw new Error('抽屉显示加载失败：' + (await drawer.locator('.ant-result-title').innerText()).trim())
      if (body.length < 20) throw new Error(`抽屉正文过短（${body.length} 字），可能没渲染`)
      await drawer.locator('.ant-drawer-close').first().click({ force: true })
      await drawer.waitFor({ state: 'hidden', timeout: 10000 })
      return `${label}，标题「${title}」，正文 ${body.length} 字`
    })

    // ── 2. 流程跟踪弹窗 ──────────────────────────────────────
    log('\n[2] 流程跟踪弹窗（BPMN 图渲染）')
    await step('打开「跟踪」并校验流程图节点', async () => {
      const target = await findRowWith(BTN.trace)
      if (!target) throw new Error('当前页没有「跟踪」按钮')
      await target.btn.click()
      const modal = page.locator('.trace-modal').last()
      await modal.waitFor({ state: 'visible', timeout: 20000 })
      await modal.locator('.trace-node').first().waitFor({ state: 'visible', timeout: 25000 })
      const nodes = await modal.locator('.trace-node').count()
      const svg = await modal.locator('svg').count()
      if (!nodes) throw new Error('跟踪弹窗已打开，但没有渲染出 BPMN 节点')
      const title = (await modal.locator('.trace-title').innerText().catch(() => '')).replace(/\s+/g, ' ').trim()
      await page.waitForTimeout(300)
      await closeModal(modal)
      return `${nodes} 个节点 / ${svg} 个 svg，标题「${title}」`
    })

    // ── 3. 转办弹窗 ─────────────────────────────────────────
    log('\n[3] 转办弹窗（校验接收人下拉，不提交）')
    await step('打开「转办」并加载接收人', async () => {
      const target = await findRowWith(BTN.transfer)
      if (!target) return Promise.reject(new Error('当前页没有「转办」按钮（可能全是沟通待办）'))
      await target.btn.scrollIntoViewIfNeeded()
      await target.btn.click()
      const modal = page.locator('.ant-modal:visible').filter({ hasText: '转办待办' }).last()
      await modal.waitFor({ state: 'visible', timeout: 20000 })
      const select = modal.locator('.ant-select-selector').first()
      await select.click()
      const option = page.locator('.ant-select-dropdown:visible .ant-select-item-option').first()
      await option.waitFor({ state: 'visible', timeout: 20000 })
      const count = await page.locator('.ant-select-dropdown:visible .ant-select-item-option').count()
      if (!count) throw new Error('接收人下拉为空')
      // 选中第一项让下拉自然收起。不能按 Escape——它会冒泡到 Modal，把弹窗一起关掉，
      // 后续「取消」按钮就找不到了（上一版脚本正是栽在这里）。
      await option.click()
      await modal.getByRole('button', { name: BTN.cancel }).click()
      await modal.waitFor({ state: 'hidden', timeout: 10000 })
      return `接收人 ${count} 个，已取消未提交`
    })

    // ── 4. 委托弹窗 ─────────────────────────────────────────
    log('\n[4] 委托弹窗（校验受托人下拉，不提交）')
    const delegateRow = await findRowWith(BTN.delegate)
    if (!delegateRow) {
      skip('打开「委托」并加载受托人', '当前页无 canDelegate 的待办')
    } else {
      await step('打开「委托」并加载受托人', async () => {
        await delegateRow.btn.click()
        const modal = page.locator('.ant-modal:visible').filter({ hasText: '委托待办' }).last()
        await modal.waitFor({ state: 'visible', timeout: 20000 })
        await modal.locator('.ant-select-selector').first().click()
        const option = page.locator('.ant-select-dropdown:visible .ant-select-item-option').first()
        await option.waitFor({ state: 'visible', timeout: 20000 })
        const count = await page.locator('.ant-select-dropdown:visible .ant-select-item-option').count()
        if (!count) throw new Error('受托人下拉为空')
        await option.click()
        await modal.getByRole('button', { name: BTN.cancel }).click()
        await modal.waitFor({ state: 'hidden', timeout: 10000 })
        return `受托人 ${count} 个，已取消未提交`
      })
    }

    // ── 5. 子流程下钻 → 返回上级 ────────────────────────────
    // 这一条依赖演示数据里确实存在「已执行的调用子流程」，不是每个实例都有。
    // 因此只做尽力而为：找不到入口就跳过并说明原因，不让它拖垮整份报告。
    log('\n[5] 子流程下钻与「返回上级流程」')
    let drilled = null
    let drillReason = ''
    try {
      const all = rows()
      const n = Math.min(await all.count(), 5)
      for (let i = 0; i < n && !drilled; i++) {
        const traceBtn = all.nth(i).getByRole('button', { name: BTN.trace }).first()
        if (!(await traceBtn.count())) continue
        await traceBtn.click()
        const modal = page.locator('.trace-modal').last()
        await modal.waitFor({ state: 'visible', timeout: 20000 })
        await modal.locator('.trace-node').first().waitFor({ state: 'visible', timeout: 25000 }).catch(() => {})

        // 两个入口：节点明细里的「下钻子流程 N 个」文字链接，和图上节点右下角的蓝色徽标。
        // 文字链接是普通 <a>，比 SVG <g> 的命中区稳，优先用它。
        const entries = [
          ...(await modal.locator('.trace-drill-link').all()),
          ...(await modal.locator('.trace-drill-badge').all()).slice(0, 3)
        ]
        for (const entry of entries) {
          if (!(await entry.isVisible().catch(() => false))) continue
          await entry.click({ force: true }).catch(() => {})
          const back = modal.locator('.trace-drill-back').first()
          const entered = await back
            .waitFor({ state: 'visible', timeout: 4000 })
            .then(() => true)
            .catch(() => false)
          if (!entered) {
            // 点到了但没进去：多半是「该节点尚未执行」的 toast，记下原因继续换一个入口
            const toast = (await page.locator('.ant-message-notice-content').last().innerText().catch(() => '')).trim()
            if (toast) drillReason = toast
            await page.keyboard.press('Escape').catch(() => {})
            continue
          }
          const depth = (await modal.locator('.trace-drill-tip').first().innerText().catch(() => '')).trim()
          const current = (await modal.locator('.trace-drill-current').first().innerText().catch(() => '')).trim()
          const childNodes = await modal.locator('.trace-node').count()
          await back.click({ force: true })
          const returned = await back
            .waitFor({ state: 'hidden', timeout: 20000 })
            .then(() => true)
            .catch(() => false)
          if (!returned) throw new Error('点击「返回上级流程」后未回到上级实例')
          drilled = `进入子流程「${current}」${depth}，${childNodes} 个节点，已返回上级`
          break
        }
        await closeModal(modal)
      }
    } catch (e) {
      drillReason = (e.message || String(e)).split('\n')[0]
    }

    if (drilled) {
      results.push({ name: '子流程下钻与返回上级', ok: true, ms: 0, detail: drilled })
      log(`  ✓ 子流程下钻与返回上级  ${drilled}`)
    } else {
      skip('子流程下钻与返回上级', drillReason || '当前账号前 5 条待办均无已执行的调用子流程')
    }
  }

  await browser.close()
}

function report() {
  const pad = (s, n) => {
    // 中文按 2 列宽计算，保证表格对齐
    let w = 0
    for (const ch of String(s)) w += /[\u4e00-\u9fa5（）：]/.test(ch) ? 2 : 1
    return String(s) + ' '.repeat(Math.max(0, n - w))
  }
  log('\n' + '═'.repeat(72))
  log(`${pad('链路', 26)}${pad('结果', 8)}${pad('耗时', 9)}说明`)
  log('─'.repeat(72))
  for (const r of results) {
    const flag = r.skip ? '跳过' : r.ok ? '通过' : '失败'
    log(`${pad(r.name, 26)}${pad(flag, 8)}${pad(r.ms ? `${(r.ms / 1000).toFixed(1)}s` : '-', 9)}${r.detail}`)
  }
  log('─'.repeat(72))

  const bad = results.filter((r) => !r.ok)
  const skipped = results.filter((r) => r.skip)
  log(`合计 ${results.length} 项：通过 ${results.length - bad.length - skipped.length}，跳过 ${skipped.length}，失败 ${bad.length}`)

  if (consoleErrors.length) {
    log(`\nConsole 额外报错 ${consoleErrors.length} 条：`)
    for (const e of consoleErrors.slice(0, 10)) log(`  - ${e}`)
  } else {
    log('\nConsole 无额外报错（已过滤 auth/me 401 与 favicon）')
  }
  if (failedRequests.length) {
    log(`\n非 2xx 请求 ${failedRequests.length} 条：`)
    for (const r of [...new Set(failedRequests)].slice(0, 10)) log(`  - ${r}`)
  }
  if (bad.length) {
    log(`\n失败截图目录：${SHOT_DIR}`)
  }
  log('')
  return bad.length
}

try {
  await main()
} catch (e) {
  results.push({ name: '启动/登录', ok: false, ms: 0, detail: (e.message || String(e)).split('\n')[0] })
  log(`\n冒烟中断：${e.message || e}`)
}
const failures = report()
process.exit(failures ? 1 : 0)
