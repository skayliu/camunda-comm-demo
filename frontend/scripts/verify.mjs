// 一条命令跑完前端全部校验，按「静态 → 构建 → 产物 → 真机」四段顺序执行，任一失败即中断。
//
//   npm run verify             # 静态检查 + 构建 + 产物检查（不需要后端）
//   npm run verify -- --smoke  # 再加一遍真机冒烟（需要 8080 在跑）
//   npm run verify -- --no-build   # 只查现有产物，不重新构建（调产物问题时省时间）
//
// 为什么要有这个入口：
//   1. check-chunk-cycles / verify-async-chunks / verify-assets-refs 都只能在「构建之后」
//      发现问题，而它们一旦被人忘记手动执行，就退化成注释；
//   2. 循环依赖导致的白屏在编译期完全看不出来，必须在构建后立刻卡住，
//      而不是等浏览器打开、再截图来问；
//   3. 各脚本原先各自用相对路径（'..' / '../src'）找产物，换个 cwd 就跑偏，
//      这里统一传绝对路径，谁调用都不会错。
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FE = path.resolve(HERE, '..')
const ROOT = path.resolve(FE, '..')
const APP_DIR = path.join(ROOT, 'src/main/resources/static/app')
const ASSETS = path.join(APP_DIR, 'assets')

const argv = process.argv.slice(2)
const has = (f) => argv.includes(f)
const SKIP_BUILD = has('--no-build')
const WITH_SMOKE = has('--smoke')

const NODE = process.execPath
const NPM = process.platform === 'win32' ? 'npm.cmd' : 'npm'

const C = {
  reset: '\x1b[0m',
  dim: '\x1b[2m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  cyan: '\x1b[36m',
  bold: '\x1b[1m'
}
const paint = (c, s) => (process.stdout.isTTY ? `${c}${s}${C.reset}` : s)

const records = []
let failed = null

function banner(n, total, title) {
  console.log('')
  console.log(paint(C.bold + C.cyan, `[${n}/${total}] ${title}`))
  console.log(paint(C.dim, '─'.repeat(60)))
}

// stdio: inherit —— vite 的构建输出、冒烟脚本的逐条结果都要原样看见，
// 这里只负责把它们串起来并收口退出码。
function run(label, cmd, args, opts = {}) {
  const started = Date.now()
  const r = spawnSync(cmd, args, {
    cwd: FE,
    stdio: 'inherit',
    env: { ...process.env, FORCE_COLOR: process.env.FORCE_COLOR ?? '1' },
    ...opts
  })
  const ms = Date.now() - started
  const ok = r.status === 0
  records.push({ label, ok, ms, code: r.status })
  return ok
}

// ---- 预检：产物目录必须先存在，否则后面的检查全是 "0 个文件 OK" 这种假通过 ----
function preflight() {
  if (SKIP_BUILD) {
    if (!fs.existsSync(ASSETS)) {
      console.log(paint(C.red, `✗ 指定了 --no-build，但产物目录不存在：${ASSETS}`))
      console.log('  请去掉 --no-build 重新执行一次完整校验。')
      process.exit(1)
    }
  }
  const need = ['vite/bin/vite.js'].map((p) => path.join(FE, 'node_modules', p))
  for (const p of need) {
    if (!fs.existsSync(p)) {
      console.log(paint(C.red, `✗ 缺少依赖：${p}`))
      console.log('  请在 frontend 目录执行 npm ci（注意：先结束占用 node_modules 的 node 进程，否则 Windows 会 EPERM）')
      process.exit(1)
    }
  }
}

function portOpen(port, host = '127.0.0.1', timeout = 800) {
  return new Promise((resolve) => {
    const sock = new net.Socket()
    const done = (v) => {
      sock.destroy()
      resolve(v)
    }
    sock.setTimeout(timeout)
    sock.once('connect', () => done(true))
    sock.once('timeout', () => done(false))
    sock.once('error', () => done(false))
    sock.connect(port, host)
  })
}

async function main() {
  console.log(paint(C.bold, '前端校验 verify'))
  console.log(paint(C.dim, `project : ${ROOT}`))
  console.log(paint(C.dim, `output  : ${APP_DIR}`))
  console.log(
    paint(
      C.dim,
      `mode    : ${SKIP_BUILD ? '跳过构建（查现有产物）' : '完整构建'}${WITH_SMOKE ? ' + 真机冒烟' : ''}`
    )
  )

  preflight()

  // banner 编号口径：模板校验 + 3 个产物检查恒在，构建模式再加「构建」这一节（build 与 sync-target 合并计）。
  const total = 4 + (SKIP_BUILD ? 0 : 1) + (WITH_SMOKE ? 1 : 0)
  let i = 0

  // 1. 模板标签 → resolver 映射。必须在构建前跑：这能提前拦住「组件名写错」，
  //    而不是等运行时在控制台看 Failed to resolve component。
  i++
  banner(i, total, SKIP_BUILD ? '模板标签校验' : '模板标签校验（构建前）')
  if (!run('check-antd-tags', NODE, [path.join(HERE, 'check-antd-tags.mjs')])) {
    failed = 'check-antd-tags'
  }

  // 2. 构建。直接调 vite 的 bin，不经过 npm run，避免 Windows 上多套一层 shell。
  if (!failed && !SKIP_BUILD) {
    i++
    banner(i, total, '构建 vite build')
    if (!run('vite build', NODE, [path.join(FE, 'node_modules/vite/bin/vite.js'), 'build'])) {
      failed = 'vite build'
    }
    // sync-target 只在 target/classes 存在时才同步，缺失时自己会跳过并返回 0。
    if (!failed && !run('sync-target', NODE, [path.join(HERE, 'sync-target.mjs')])) {
      failed = 'sync-target'
    }
  }

  // 3~5. 产物检查，全部显式传 assets 绝对路径（原脚本默认值依赖 cwd，容易查错目录）。
  if (!failed) {
    i++
    banner(i, total, '引用完整性 verify-assets-refs')
    if (!run('verify-assets-refs', NODE, [path.join(HERE, 'verify-assets-refs.mjs'), ASSETS])) {
      failed = 'verify-assets-refs'
    }
  }

  if (!failed) {
    i++
    banner(i, total, '循环依赖 check-chunk-cycles')
    if (!run('check-chunk-cycles', NODE, [path.join(HERE, 'check-chunk-cycles.mjs'), ASSETS])) {
      failed = 'check-chunk-cycles'
    }
  }

  if (!failed) {
    i++
    banner(i, total, '弹层按需加载 verify-async-chunks')
    if (!run('verify-async-chunks', NODE, [path.join(HERE, 'verify-async-chunks.mjs'), ASSETS])) {
      failed = 'verify-async-chunks'
    }
  }

  // 6. 真机冒烟：要真的点开抽屉和弹窗，必须有后端在跑，所以默认不启用。
  if (!failed && WITH_SMOKE) {
    i++
    banner(i, total, '真机冒烟 e2e-smoke')
    const up = await portOpen(8080)
    if (!up) {
      records.push({ label: 'e2e-smoke', ok: false, ms: 0, code: null, note: '8080 未监听' })
      failed = 'e2e-smoke（8080 未监听）'
    } else if (!run('e2e-smoke', NODE, [path.join(HERE, 'e2e-smoke.mjs')])) {
      failed = 'e2e-smoke'
    }
  }

  // ---- 汇总 ----
  console.log('')
  console.log(paint(C.bold, '汇总'))
  console.log(paint(C.dim, '─'.repeat(60)))
  for (const r of records) {
    const mark = r.ok ? paint(C.green, '✓') : paint(C.red, '✗')
    const time = r.ms ? `${String(r.ms).padStart(5)} ms` : '    -  '
    console.log(`  ${mark} ${r.label.padEnd(24)} ${time}${r.note ? '  ' + r.note : ''}`)
  }

  if (failed) {
    console.log('')
    console.log(paint(C.red + C.bold, `校验未通过：${failed}`))
    console.log(paint(C.dim, '这一步的完整输出见上方；修复后重跑 npm run verify 即可。'))
    if (SKIP_BUILD) {
      console.log(
        paint(C.yellow, '  注意：产物目录已被上一步构建覆盖，这份产物不可信，不要直接用它启动 8080。')
      )
    }
    if (failed === 'check-chunk-cycles') {
      console.log('  循环依赖的典型症状是「构建成功、打开页面白屏」，处理方式：')
      console.log('  去掉 vite.config.js 里人为的 codeSplitting.groups，让打包器按模块图自动分块。')
    }
    if (failed === 'verify-async-chunks') {
      console.log('  说明某个弹层又被写回了静态 import，或丢失 default 导出；')
      console.log('  检查该页面是否把 defineAsyncComponent 改回了普通 import（且 v-if 门控是否还在）。')
    }
    if (failed === 'check-antd-tags') {
      console.log('  在 vite/antd-subpath-resolver.js 的 COMPONENTS 里补一行映射即可。')
    }
    process.exit(1)
  }

  console.log('')
  console.log(paint(C.green + C.bold, '全部通过'))
  if (!SKIP_BUILD) {
    const idx = fs.existsSync(APP_DIR)
      ? fs.readdirSync(path.join(APP_DIR, 'assets')).find((f) => /^index-.*\.js$/.test(f))
      : null
    if (idx) console.log(paint(C.dim, `当前入口：${idx}（浏览器需 Ctrl+F5 强刷才会加载新哈希）`))
  }
  if (!WITH_SMOKE) {
    console.log(paint(C.dim, '真机冒烟未执行；需要时加 --smoke（要求 8080 已启动）。'))
  }
}

main().catch((e) => {
  console.error(paint(C.red, `verify 自身异常：${e && e.stack ? e.stack : e}`))
  process.exit(1)
})
