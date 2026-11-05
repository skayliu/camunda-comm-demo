// 前端构建产物同步到 target/classes。
//
// 背景：vite 只把产物写进 src/main/resources/static/app，而 mvn spring-boot:run
// 与 IDE 运行时读取的是 target/classes/static/app。若构建后不重新 process-resources，
// 服务端仍会返回旧包（旧 index.html + 旧 chunk），页面看起来“改了没生效”。
// 这里在 build 末尾直接覆盖 target 下的副本，避免依赖人工记得再跑一步 mvn。
import { access, cp, mkdir, rm } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const projectRoot = path.resolve(frontendRoot, '..')
const src = path.join(projectRoot, 'src/main/resources/static/app')
const targetClasses = path.join(projectRoot, 'target/classes/static')
const dest = path.join(targetClasses, 'app')

try {
  await access(targetClasses)
} catch {
  console.log('[sync-target] target/classes 尚未生成，跳过同步（下次 mvn 编译会从 resources 复制）')
  process.exit(0)
}

await rm(dest, { recursive: true, force: true })
await mkdir(path.dirname(dest), { recursive: true })
await cp(src, dest, { recursive: true })
console.log(`[sync-target] 已同步前端产物 -> ${path.relative(projectRoot, dest)}`)
