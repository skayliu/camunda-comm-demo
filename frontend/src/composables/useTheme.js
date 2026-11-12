import { computed, ref, watch } from 'vue'

/**
 * 主题状态（单例）
 *
 * 三档：light / dark / system。
 * - 选择结果写进 localStorage，刷新后保留；
 * - system 档跟随 prefers-color-scheme，系统切换时实时生效；
 * - 真正生效的主题挂在 <html data-theme="...">，所有颜色由 tokens.css 提供，
 *   antd 组件由 App.vue 的 ConfigProvider algorithm 同步。
 *
 * index.html 里有一段同 key 的内联脚本先做一次预判，避免首屏白闪。
 */

const STORAGE_KEY = 'wb-theme-mode'
const MODES = ['light', 'dark', 'system']

const mql =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-color-scheme: dark)')
    : null

function readMode() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (MODES.includes(saved)) return saved
  } catch (e) {
    /* 隐私模式下 localStorage 可能不可用，退回跟随系统 */
  }
  return 'system'
}

const mode = ref(readMode())
const systemDark = ref(mql ? mql.matches : false)

const resolved = computed(() => {
  if (mode.value === 'system') return systemDark.value ? 'dark' : 'light'
  return mode.value
})
const isDark = computed(() => resolved.value === 'dark')

/** 把结果写到 DOM：data-theme 供 CSS 使用，color-scheme 让原生控件/滚动条跟随 */
function applyTheme(theme) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.dataset.theme = theme
  root.style.colorScheme = theme
  root.classList.add('theme-switching')
  window.clearTimeout(applyTheme._timer)
  applyTheme._timer = window.setTimeout(() => {
    root.classList.remove('theme-switching')
  }, 220)

  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', theme === 'dark' ? '#0b0f15' : '#f1f4f9')
}

watch(resolved, applyTheme, { immediate: true })

watch(mode, (next) => {
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch (e) {
    /* 存不下就算了，本次会话仍然有效 */
  }
})

if (mql) {
  const onSystemChange = (event) => {
    systemDark.value = event.matches
  }
  // Safari 14 之前只有 addListener，保留一条兼容路径
  if (typeof mql.addEventListener === 'function') {
    mql.addEventListener('change', onSystemChange)
  } else if (typeof mql.addListener === 'function') {
    mql.addListener(onSystemChange)
  }
}

const OPTIONS = [
  { key: 'light', label: '浅色', hint: '始终使用浅色界面' },
  { key: 'dark', label: '深色', hint: '始终使用深色界面' },
  { key: 'system', label: '跟随系统', hint: '随操作系统的外观自动切换' }
]

export function useTheme() {
  function setMode(next) {
    if (MODES.includes(next)) mode.value = next
  }

  /** 点击图标时的快速切换：在浅/深之间来回，system 档下先落到当前实际值的反向 */
  function toggle() {
    mode.value = isDark.value ? 'light' : 'dark'
  }

  return {
    mode,
    resolved,
    isDark,
    options: OPTIONS,
    setMode,
    toggle
  }
}

export { MODES, STORAGE_KEY }
