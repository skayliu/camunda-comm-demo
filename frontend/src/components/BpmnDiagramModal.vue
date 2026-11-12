<template>
  <Teleport to="body">
    <div v-if="open" class="bd-mask">
      <section
        ref="shellRef"
        class="bd-shell"
        :class="{ 'is-window': !fullscreen, 'is-dragging': dragging }"
        :style="shellStyle"
      >
        <!-- 标题栏：收缩后按住这里拖动窗口 -->
        <header class="bd-head" :class="{ 'can-drag': !fullscreen }" @pointerdown="startDrag">
          <div class="bd-head-left">
            <span class="bd-dot" aria-hidden="true"></span>
            <div class="bd-title">
              <span class="bd-name">{{ meta.processName || processName || processKey }}</span>
              <span class="bd-sub">
                <span class="mono">{{ processKey }}</span>
                <template v-if="meta.version">
                  <span class="sep">·</span>引擎 v{{ meta.version }}
                </template>
                <template v-if="meta.processDefinitionId">
                  <span class="sep">·</span><span class="mono">{{ meta.processDefinitionId }}</span>
                </template>
              </span>
            </div>
          </div>
          <div class="bd-head-actions no-drag">
            <span v-if="!fullscreen" class="drag-hint">拖动标题栏移动</span>
            <div class="remember-wrap">
              <button
                class="icon-btn remember-btn"
                :class="`mode-${rememberMode}`"
                type="button"
                :title="rememberTitle"
                aria-haspopup="menu"
                :aria-expanded="rememberMenuOpen ? 'true' : 'false'"
                @click.stop="toggleRememberMenu"
              >
                <PushpinFilled v-if="rememberMode !== 'none'" />
                <PushpinOutlined v-else />
                <span v-if="rememberMode === 'fullscreen'" class="remember-badge" aria-hidden="true"></span>
              </button>
              <transition name="bd-fade">
                <div v-if="rememberMenuOpen" class="remember-menu" role="menu">
                  <div class="remember-menu-tip">下次打开时</div>
                  <button
                    v-for="option in REMEMBER_OPTIONS"
                    :key="option.value"
                    class="remember-item"
                    :class="{ 'is-active': rememberMode === option.value }"
                    type="button"
                    role="menuitemradio"
                    :aria-checked="rememberMode === option.value ? 'true' : 'false'"
                    @click.stop="setRememberMode(option.value)"
                  >
                    <span class="item-check">
                      <CheckOutlined v-if="rememberMode === option.value" />
                    </span>
                    <span class="item-text">
                      <span class="item-label">{{ option.label }}</span>
                      <span class="item-desc">{{ option.desc }}</span>
                    </span>
                  </button>
                </div>
              </transition>
            </div>

            <!-- 画布工具栏：与全屏、关闭同处标题栏右上角 -->
            <div v-if="!error" class="bd-tools" role="toolbar" aria-label="画布工具">
              <button type="button" title="缩小" aria-label="缩小" @click="zoomBy(1 / 1.25)">
                <ZoomOutOutlined />
              </button>
              <span class="zoom-val">{{ zoomText }}</span>
              <button type="button" title="放大" aria-label="放大" @click="zoomBy(1.25)">
                <ZoomInOutlined />
              </button>
              <i class="tool-sep" aria-hidden="true"></i>
              <button type="button" title="适应窗口" aria-label="适应窗口" @click="fitViewport">
                <ArrowsAltOutlined />
              </button>
              <button
                type="button"
                class="tool-opt"
                title="恢复 100%"
                aria-label="恢复 100%"
                @click="resetZoom"
              >
                <ReloadOutlined />
              </button>
            </div>
            <i v-if="!error" class="head-sep" aria-hidden="true"></i>

            <button
              class="icon-btn"
              type="button"
              :title="fullscreen ? '收缩为窗口' : '铺满全屏'"
              @click="toggleFullscreen"
            >
              <FullscreenExitOutlined v-if="fullscreen" />
              <FullscreenOutlined v-else />
            </button>
            <button class="icon-btn" type="button" title="关闭" @click="close">
              <CloseOutlined />
            </button>
          </div>
        </header>

        <div class="bd-body">
          <div ref="canvasHost" class="bd-canvas"></div>

          <div v-if="!loading && !error" class="bd-tip">
            点击节点可查看属性 · 滚轮缩放 · 拖动画布平移
          </div>

          <div v-if="loading" class="bd-state">
            <span class="spinner" aria-hidden="true"></span>
            <p class="state-title">正在读取流程定义…</p>
            <p class="state-sub mono">{{ processKey }}</p>
          </div>

          <div v-else-if="error" class="bd-state">
            <p class="state-title">流程图打不开</p>
            <p class="state-error">{{ error }}</p>
            <button class="state-btn" type="button" @click="reload">重新加载</button>
          </div>

          <!-- 右侧属性抽屉 -->
          <transition name="bd-slide">
            <aside v-if="drawerOpen" class="bd-drawer">
              <div class="drawer-head">
                <div>
                  <div class="drawer-title">{{ selected.name || selected.id || '未命名元素' }}</div>
                  <div class="drawer-meta">
                    <span class="type-tag">{{ selected.typeLabel }}</span>
                    <span class="mono drawer-id">{{ selected.id }}</span>
                  </div>
                </div>
                <button class="icon-btn" type="button" title="收起属性" @click="clearSelection">
                  <CloseOutlined />
                </button>
              </div>
              <div class="drawer-body">
                <div v-for="group in selected.groups" :key="group.title" class="prop-group">
                  <div class="prop-group-title">{{ group.title }}</div>
                  <div v-for="(row, index) in group.rows" :key="`${row.label}-${index}`" class="prop-row">
                    <span class="prop-label">{{ row.label }}</span>
                    <span
                      class="prop-value"
                      :class="{ mono: row.mono, 'is-warn': row.tone === 'warn' }"
                    >{{ row.value }}</span>
                  </div>
                </div>
              </div>
            </aside>
          </transition>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  ArrowsAltOutlined,
  CheckOutlined,
  CloseOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  PushpinFilled,
  PushpinOutlined,
  ReloadOutlined,
  ZoomInOutlined,
  ZoomOutOutlined
} from '@ant-design/icons-vue'
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer'
import camundaModdleDescriptor from 'camunda-bpmn-moddle/resources/camunda.json'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css'
import { adminSubProcessApi, errorText } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  processKey: { type: String, default: '' },
  processName: { type: String, default: '' },
  /** 可选：指定引擎版本，不传取最新版本 */
  version: { type: Number, default: null }
})
const emit = defineEmits(['update:open'])

const shellRef = ref(null)
const canvasHost = ref(null)

const loading = ref(false)
const error = ref('')
const meta = reactive({ processName: '', version: null, processDefinitionId: '' })
const zoom = ref(1)
const zoomText = computed(() => `${Math.round(zoom.value * 100)}%`)

// ---- 窗口形态：默认以窗口打开并居中，收缩 / 铺满可随时切换 ----
const fullscreen = ref(false)
const dragging = ref(false)
const rect = reactive({ x: 0, y: 0, w: 0, h: 0 })
const shellStyle = computed(() =>
  fullscreen.value
    ? {}
    : {
        left: `${rect.x}px`,
        top: `${rect.y}px`,
        width: `${rect.w}px`,
        height: `${rect.h}px`
      }
)

const clamp = (value, min, max) => Math.min(Math.max(value, min), Math.max(min, max))

// ---- 视图记忆：三档，默认全部记住 ----
// all        位置、大小、缩放平移、是否全屏都记
// fullscreen 只记是否全屏；窗口每次居中、按内容取景（换机 / 换分辨率更稳）
// none       什么都不记，每次打开都居中
const WINDOW_KEY = 'comm.bpmnWindow'
const REMEMBER_KEY = 'comm.bpmnRemember'
const MIN_W = 360
const MIN_H = 280
const MIN_SCALE = 0.15
const MAX_SCALE = 4

const REMEMBER_OPTIONS = [
  { value: 'all', label: '记住全部', desc: '位置、大小、缩放与全屏' },
  { value: 'fullscreen', label: '只记全屏', desc: '窗口每次居中，不记位置大小' },
  { value: 'none', label: '不记住', desc: '每次打开都回到居中' }
]

const REMEMBER_MODES = REMEMBER_OPTIONS.map((option) => option.value)

function readRemember() {
  try {
    const raw = localStorage.getItem(REMEMBER_KEY)
    if (raw === null) return 'all'
    // 兼容旧版本存的 '1' / '0'：1 是全记，0 是不记
    if (raw === '1') return 'all'
    if (raw === '0') return 'none'
    return REMEMBER_MODES.includes(raw) ? raw : 'all'
  } catch (e) {
    // localStorage 不可用（隐私模式）时退化为不记
    return 'none'
  }
}

const rememberMode = ref(readRemember())
const rememberMenuOpen = ref(false)
const rememberTitle = computed(() => {
  if (rememberMode.value === 'all') return '已记住位置、大小、缩放与全屏，点击切换'
  if (rememberMode.value === 'fullscreen') return '只记住全屏状态，窗口每次居中，点击切换'
  return '不记住窗口状态，每次打开居中，点击切换'
})

function toggleRememberMenu() {
  rememberMenuOpen.value = !rememberMenuOpen.value
}

function closeRememberMenu() {
  rememberMenuOpen.value = false
}

function setRememberMode(mode) {
  if (!REMEMBER_MODES.includes(mode)) return
  rememberMode.value = mode
  closeRememberMenu()
  try {
    localStorage.setItem(REMEMBER_KEY, mode)
    if (mode === 'none') localStorage.removeItem(WINDOW_KEY)
  } catch (e) {
    // 写不进去就只在本次会话生效
  }
  if (mode === 'none') return
  if (mode === 'fullscreen') {
    // 立刻覆盖掉旧记录：位置与大小不再保留，切换后关掉再开就是居中
    writeFullscreenOnly()
    return
  }
  persistWindow()
}

/** 点菜单外部收起 */
function onDocPointerDown(event) {
  if (!rememberMenuOpen.value) return
  const target = event.target
  if (target && typeof target.closest === 'function' && target.closest('.remember-wrap')) return
  closeRememberMenu()
}

/** viewbox 只认四个有限的数值，宽度高度必须为正 */
function normalizeViewbox(box) {
  if (!box || typeof box !== 'object') return null
  const x = Number(box.x)
  const y = Number(box.y)
  const width = Number(box.width)
  const height = Number(box.height)
  if (![x, y, width, height].every((n) => Number.isFinite(n))) return null
  if (width <= 0 || height <= 0) return null
  return { x, y, width, height }
}

function readSavedState() {
  try {
    const raw = localStorage.getItem(WINDOW_KEY)
    if (!raw) return null
    const value = JSON.parse(raw)
    if (!value || typeof value !== 'object') return null
    const state = {
      fullscreen: value.fullscreen === true,
      key: typeof value.key === 'string' ? value.key : '',
      rect: null,
      viewbox: normalizeViewbox(value.viewbox)
    }
    const w = Number(value.w)
    const h = Number(value.h)
    const x = Number(value.x)
    const y = Number(value.y)
    // 尺寸过小或完全落在视口外（换过显示器、分辨率变小）都视为无效
    if ([w, h, x, y].every((n) => Number.isFinite(n)) && w >= MIN_W && h >= MIN_H) {
      const vw = window.innerWidth
      const vh = window.innerHeight
      const visibleX = Math.min(x + w, vw) - Math.max(x, 0)
      const visibleY = Math.min(y + h, vh) - Math.max(y, 0)
      if (visibleX >= 120 && visibleY >= 80) state.rect = { x, y, w, h }
    }
    return state
  } catch (e) {
    return null
  }
}

function readPrevState() {
  try {
    const raw = localStorage.getItem(WINDOW_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

function currentViewbox() {
  if (!viewer) return null
  try {
    return normalizeViewbox(viewer.get('canvas').viewbox())
  } catch (e) {
    return null
  }
}

/** 「只记全屏」模式下的记录：只有全屏与流程 key，不写位置尺寸和视口 */
function writeFullscreenOnly() {
  try {
    localStorage.setItem(
      WINDOW_KEY,
      JSON.stringify({ mode: 'fullscreen', fullscreen: fullscreen.value, key: props.processKey || '' })
    )
  } catch (e) {
    // 隐私模式下不可写，忽略
  }
}

/** 全屏状态也要记：否则上次全屏关掉，下次打开又缩回窗口 */
function persistWindow() {
  const mode = rememberMode.value
  if (mode === 'none') return
  flushPersist()
  if (mode === 'fullscreen') {
    writeFullscreenOnly()
    return
  }
  const state = {
    mode,
    fullscreen: fullscreen.value,
    key: props.processKey || '',
    x: Math.round(rect.x),
    y: Math.round(rect.y),
    w: Math.round(rect.w),
    h: Math.round(rect.h)
  }
  const viewbox = currentViewbox()
  if (viewbox) {
    state.viewbox = viewbox
  } else {
    // 图还没导入完成时别把已存的缩放抹掉
    const prev = readPrevState()
    if (prev && prev.viewbox) state.viewbox = prev.viewbox
  }
  try {
    localStorage.setItem(WINDOW_KEY, JSON.stringify(state))
  } catch (e) {
    // 隐私模式下 localStorage 不可写，忽略即可
  }
}

let persistTimer = null

function flushPersist() {
  if (persistTimer) {
    clearTimeout(persistTimer)
    persistTimer = null
  }
}

/** 缩放 / 平移会高频触发，攒 300ms 再落盘。只记全屏的档位不需要记视口 */
function schedulePersist() {
  if (rememberMode.value !== 'all') return
  flushPersist()
  persistTimer = setTimeout(() => {
    persistTimer = null
    persistWindow()
  }, 300)
}

function computeWindowSize() {
  const vw = window.innerWidth
  const vh = window.innerHeight
  // 窄屏不再留太多边距，否则画布被压得看不清
  if (vw < 720) {
    return { w: Math.max(280, vw - 16), h: Math.max(240, vh - 16) }
  }
  return {
    w: Math.min(1180, Math.round(vw * 0.9)),
    h: Math.min(760, Math.round(vh * 0.86))
  }
}

function shrinkToCenter() {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const size = computeWindowSize()
  rect.w = size.w
  rect.h = size.h
  rect.x = Math.round((vw - size.w) / 2)
  rect.y = Math.round((vh - size.h) / 2)
  fullscreen.value = false
}

// 本次打开要还原的画布视口；等 XML 导入完成后再应用
let pendingViewbox = null

/** 打开时回到上次关掉时的样子：位置、大小、全屏、缩放与平移 */
function restoreShell() {
  pendingViewbox = null
  const mode = rememberMode.value
  const saved = mode === 'none' ? null : readSavedState()

  // 只记全屏：位置与尺寸一律回默认居中，避免在演示机 / 大屏之间切换后被旧坐标带偏
  if (mode === 'fullscreen') {
    shrinkToCenter()
    fullscreen.value = !!(saved && saved.fullscreen)
    return
  }

  if (!saved) {
    shrinkToCenter()
    return
  }
  if (saved.rect) {
    const vw = window.innerWidth
    const vh = window.innerHeight
    rect.w = clamp(saved.rect.w, MIN_W, Math.max(MIN_W, vw - 16))
    rect.h = clamp(saved.rect.h, MIN_H, Math.max(MIN_H, vh - 16))
    rect.x = clamp(saved.rect.x, 8, vw - rect.w - 8)
    rect.y = clamp(saved.rect.y, 8, vh - rect.h - 8)
  } else {
    const size = computeWindowSize()
    rect.w = size.w
    rect.h = size.h
    rect.x = Math.round((window.innerWidth - size.w) / 2)
    rect.y = Math.round((window.innerHeight - size.h) / 2)
  }
  // 换了流程就别套用上一个流程的视口，交给 fit-viewport 重新取景
  if (saved.viewbox && saved.key === (props.processKey || '')) {
    pendingViewbox = saved.viewbox
  }
  fullscreen.value = saved.fullscreen
}

/** 退出全屏时回到上一次的窗口形态，没有有效尺寸才居中 */
function leaveFullscreen() {
  const vw = window.innerWidth
  const vh = window.innerHeight
  if (rect.w >= MIN_W && rect.h >= MIN_H) {
    rect.w = clamp(rect.w, MIN_W, Math.max(MIN_W, vw - 16))
    rect.h = clamp(rect.h, MIN_H, Math.max(MIN_H, vh - 16))
    rect.x = clamp(rect.x, 8, vw - rect.w - 8)
    rect.y = clamp(rect.y, 8, vh - rect.h - 8)
    fullscreen.value = false
    return
  }
  shrinkToCenter()
}

function toggleFullscreen() {
  if (fullscreen.value) leaveFullscreen()
  else fullscreen.value = true
  persistWindow()
}

let drag = null

function startDrag(event) {
  if (event.button !== 0) return
  if (event.target.closest('.no-drag')) return
  if (fullscreen.value) {
    // 全屏时按住标题栏拖动：先落成窗口，再跟随指针，避免"拖不动"的错觉
    // 优先沿用上次的窗口尺寸，没有才现算，避免每次都重置成默认大小
    const size =
      rect.w >= MIN_W && rect.h >= MIN_H ? { w: rect.w, h: rect.h } : computeWindowSize()
    rect.w = Math.min(size.w, window.innerWidth - 16)
    rect.h = Math.min(size.h, window.innerHeight - 16)
    rect.x = clamp(event.clientX - rect.w / 2, 8, window.innerWidth - rect.w - 8)
    rect.y = clamp(event.clientY - 34, 8, window.innerHeight - rect.h - 8)
    fullscreen.value = false
  }
  drag = { sx: event.clientX, sy: event.clientY, ox: rect.x, oy: rect.y }
  dragging.value = true
  window.addEventListener('pointermove', onDragMove)
  window.addEventListener('pointerup', endDrag, { once: true })
  event.preventDefault()
}

function onDragMove(event) {
  if (!drag) return
  const maxX = window.innerWidth - rect.w - 8
  const maxY = window.innerHeight - rect.h - 8
  rect.x = clamp(drag.ox + event.clientX - drag.sx, 8, maxX)
  rect.y = clamp(drag.oy + event.clientY - drag.sy, 8, maxY)
}

function endDrag() {
  drag = null
  dragging.value = false
  window.removeEventListener('pointermove', onDragMove)
  persistWindow()
}

function onWindowResize() {
  if (fullscreen.value) return
  rect.w = Math.min(rect.w, window.innerWidth - 16)
  rect.h = Math.min(rect.h, window.innerHeight - 16)
  rect.x = clamp(rect.x, 8, window.innerWidth - rect.w - 8)
  rect.y = clamp(rect.y, 8, window.innerHeight - rect.h - 8)
  persistWindow()
}

// ---- bpmn-js ----
let viewer = null
let resizeObserver = null
let selectedElement = null

const drawerOpen = ref(false)
const selected = reactive({ id: '', name: '', typeLabel: '', groups: [] })

function createViewer() {
  if (viewer || !canvasHost.value) return
  viewer = new NavigatedViewer({
    container: canvasHost.value,
    moddleExtensions: { camunda: camundaModdleDescriptor }
  })

  const eventBus = viewer.get('eventBus')
  const canvas = viewer.get('canvas')

  eventBus.on('element.click', (event) => {
    // 标签是独立元素：点文字时要把高亮和属性都落到宿主节点上。
    // 否则描边会加在 <text> 上，中文小字被描一圈，看起来就是重影。
    let element = event.element
    if (element && element.labelTarget) element = element.labelTarget
    if (!element || !element.businessObject) return
    selectElement(element)
  })
  eventBus.on('canvas.click', () => clearSelection())
  eventBus.on('canvas.viewbox.changed', () => {
    // 直接问画布要真实比例：事件里的 viewbox 在 fit 阶段可能还没算好，
    // 用它会一直显示 100%，与实际缩放对不上
    zoom.value = readScale(canvas)
    schedulePersist()
  })

  if (typeof ResizeObserver !== 'undefined' && canvasHost.value) {
    resizeObserver = new ResizeObserver(() => {
      try {
        canvas.resized()
      } catch (e) {
        // 容器尚未挂载 / 已销毁时忽略
      }
    })
    resizeObserver.observe(canvasHost.value)
  }
}

function mathSafeScale(scale) {
  const value = Number(scale)
  return Number.isFinite(value) && value > 0 ? value : 1
}

/** 最小可读比例：低于它中文标签只剩几个像素，看起来就是"重影／糊成一片" */
const MIN_READABLE_SCALE = 0.8

function readScale(canvas) {
  if (!canvas) return 1
  try {
    const box = canvas.viewbox()
    const value = Number(box && box.scale)
    if (Number.isFinite(value) && value > 0) return value
  } catch (e) {
    // viewbox 在导入过程中可能抛错，退回 zoom()
  }
  try {
    return mathSafeScale(canvas.zoom())
  } catch (e) {
    return 1
  }
}

function syncZoom() {
  if (!viewer) return
  try {
    zoom.value = readScale(viewer.get('canvas'))
  } catch (e) {
    // 画布已销毁
  }
}

function destroyViewer() {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (viewer) {
    viewer.destroy()
    viewer = null
  }
  selectedElement = null
  // DOM 随 viewer 一起没了，旧标记引用必须清掉，否则下次选中会去移除已销毁的元素
  markedLinks = []
  markedNeighbors = []
}

async function loadDiagram() {
  if (!props.processKey) {
    error.value = '缺少流程 key，无法定位流程定义'
    return
  }
  loading.value = true
  error.value = ''
  clearSelection()
  try {
    const data = await adminSubProcessApi.bpmnXml(props.processKey, props.version)
    if (!data || !data.xml) {
      throw new Error('接口没有返回 BPMN 内容')
    }
    meta.processName = data.processName || ''
    meta.version = data.version
    meta.processDefinitionId = data.processDefinitionId || ''
    if (!viewer) createViewer()
    await viewer.importXML(data.xml)
    applyInitialViewport()
  } catch (e) {
    error.value = e && e.message ? e.message : errorText(e)
  } finally {
    loading.value = false
  }
}

function reload() {
  loadDiagram()
}

function zoomBy(factor) {
  if (!viewer || error.value) return
  const canvas = viewer.get('canvas')
  const current = mathSafeScale(canvas.zoom())
  const next = clamp(current * factor, 0.15, 4)
  canvas.zoom(next)
}

/** 导入完成后取景：有上次的视口就还原（缩放 + 平移），否则按窗口适应 */
function applyInitialViewport() {
  if (!viewer || error.value) return
  const canvas = viewer.get('canvas')
  const saved = pendingViewbox
  pendingViewbox = null
  if (saved) {
    try {
      canvas.viewbox(saved)
      const scale = readScale(canvas)
      // 换了窗口尺寸后旧视口可能把图放大得离谱，超出范围就退回适应窗口
      if (scale >= MIN_READABLE_SCALE && scale <= MAX_SCALE) {
        syncZoom()
        requestAnimationFrame(syncZoom)
        return
      }
    } catch (e) {
      // 视口非法（元素已被移除等）时走适应窗口
    }
  }
  fitViewport()
}

function fitViewport() {
  if (!viewer || error.value) return
  const canvas = viewer.get('canvas')
  let fitted = 0
  try {
    // 'auto' 让内容包围盒的中心对齐可视区中心，避免窄窗口里图形偏在左下角
    canvas.zoom('fit-viewport', 'auto')
    fitted = readScale(canvas)
  } catch (e) {
    try {
      canvas.zoom('fit-viewport')
      fitted = readScale(canvas)
    } catch (e2) {
      // 空图或尚未布局完成时忽略
    }
  }
  // 内容太宽时 fit 会把比例压到 0.4 以下，中文标签缩成几个像素就会看着"重影"。
  // 这里保一个可读下限，超出部分交给拖动画布看。
  if (fitted > 0 && fitted < MIN_READABLE_SCALE) {
    try {
      canvas.zoom(MIN_READABLE_SCALE)
    } catch (e) {
      // ignore
    }
  }
  syncZoom()
  // fit 完成后布局还会再走一帧，补一次同步，避免百分比停在旧值
  requestAnimationFrame(syncZoom)
}

function resetZoom() {
  if (!viewer || error.value) return
  viewer.get('canvas').zoom(1)
}

// ---- 属性读取 ----
const TYPE_LABELS = {
  'bpmn:Process': '流程',
  'bpmn:StartEvent': '开始事件',
  'bpmn:EndEvent': '结束事件',
  'bpmn:IntermediateCatchEvent': '中间捕获事件',
  'bpmn:IntermediateThrowEvent': '中间抛出事件',
  'bpmn:BoundaryEvent': '边界事件',
  'bpmn:UserTask': '用户任务',
  'bpmn:ServiceTask': '服务任务',
  'bpmn:ScriptTask': '脚本任务',
  'bpmn:ManualTask': '手工任务',
  'bpmn:BusinessRuleTask': '业务规则任务',
  'bpmn:SendTask': '发送任务',
  'bpmn:ReceiveTask': '接收任务',
  'bpmn:CallActivity': '调用活动（子流程）',
  'bpmn:SubProcess': '子流程',
  'bpmn:ExclusiveGateway': '排他网关',
  'bpmn:ParallelGateway': '并行网关',
  'bpmn:InclusiveGateway': '包容网关',
  'bpmn:EventBasedGateway': '事件网关',
  'bpmn:ComplexGateway': '复杂网关',
  'bpmn:SequenceFlow': '顺序流',
  'bpmn:Participant': '泳池',
  'bpmn:Lane': '泳道',
  'bpmn:TextAnnotation': '文本注释',
  'bpmn:DataObjectReference': '数据对象',
  'bpmn:Group': '分组'
}

function typeLabel(type) {
  return TYPE_LABELS[type] || String(type || '').replace('bpmn:', '')
}

function pushRow(rows, label, value, options = {}) {
  if (value === undefined || value === null || value === '') return
  rows.push({ label, value: String(value), mono: !!options.mono, tone: options.tone || '' })
}

function documentationText(bo) {
  if (!bo || !Array.isArray(bo.documentation)) return ''
  return bo.documentation
    .map((d) => (d && d.text ? String(d.text).trim() : ''))
    .filter(Boolean)
    .join('\n')
}

function timerText(def) {
  const pick = (node) => (node && node.body ? node.body : '')
  return pick(def.timeDate) || pick(def.timeDuration) || pick(def.timeCycle) || ''
}

function eventDefinitionText(def) {
  if (!def || !def.$type) return ''
  switch (def.$type) {
    case 'bpmn:TimerEventDefinition':
      return `定时 · ${timerText(def) || '未设置表达式'}`
    case 'bpmn:MessageEventDefinition':
      return `消息 · ${(def.messageRef && (def.messageRef.name || def.messageRef.id)) || '未绑定消息'}`
    case 'bpmn:SignalEventDefinition':
      return `信号 · ${(def.signalRef && (def.signalRef.name || def.signalRef.id)) || '未绑定信号'}`
    case 'bpmn:ErrorEventDefinition':
      return `错误 · ${(def.errorRef && (def.errorRef.name || def.errorRef.id)) || (def.errorCode || '未绑定错误')}`
    case 'bpmn:ConditionalEventDefinition':
      return `条件 · ${(def.condition && def.condition.body) || '未设置条件'}`
    case 'bpmn:EscalationEventDefinition':
      return `升级 · ${(def.escalationRef && (def.escalationRef.name || def.escalationRef.id)) || '未绑定升级'}`
    default:
      return String(def.$type).replace('bpmn:', '').replace('EventDefinition', '')
  }
}

function extensionRows(bo) {
  const ext = bo && bo.extensionElements
  if (!ext || !Array.isArray(ext.values) || ext.values.length === 0) return null

  const properties = []
  const listeners = []
  const io = []
  const others = []

  for (const value of ext.values) {
    if (!value || !value.$type) continue
    if (value.$type === 'camunda:Properties') {
      for (const property of value.values || []) {
        properties.push([property.name || '(未命名)', property.value])
      }
    } else if (value.$type === 'camunda:Property') {
      properties.push([value.name || '(未命名)', value.value])
    } else if (value.$type === 'camunda:ExecutionListener' || value.$type === 'camunda:TaskListener') {
      const impl = value.class || value.expression || value.delegateExpression || '未指定实现'
      const kind = value.$type === 'camunda:TaskListener' ? '任务监听' : '执行监听'
      listeners.push([`${kind} · ${value.event || '未指定事件'}`, impl])
    } else if (value.$type === 'camunda:InputOutput') {
      for (const param of value.inputParameters || []) {
        const text = param.value !== undefined ? param.value : (param.definition && param.definition.$type) || ''
        io.push([`输入 ${param.name || ''}`, text])
      }
      for (const param of value.outputParameters || []) {
        const text = param.value !== undefined ? param.value : (param.definition && param.definition.$type) || ''
        io.push([`输出 ${param.name || ''}`, text])
      }
    } else if (value.$type === 'camunda:FailedJobRetryTimeCycle') {
      others.push(['重试周期', value.body])
    } else if (value.$type === 'camunda:FormData') {
      for (const field of value.fields || []) {
        others.push([`表单字段 ${field.id || ''}`, `${field.label || ''} / ${field.type || ''}`.trim()])
      }
    }
  }

  const rows = []
  for (const [name, value] of properties) {
    const isDept = String(name).toLowerCase() === 'deptid' || String(name).toLowerCase() === 'dept_id'
    rows.push({
      label: isDept ? '扩展属性 · 部门归属' : `扩展属性 · ${name}`,
      value: value === undefined || value === null || value === '' ? '(空)' : String(value),
      mono: true,
      tone: isDept ? 'warn' : ''
    })
  }
  for (const [name, value] of listeners) rows.push({ label: name, value: String(value), mono: true })
  for (const [name, value] of io) rows.push({ label: name, value: String(value), mono: true })
  for (const [name, value] of others) rows.push({ label: name, value: String(value) })

  return rows.length ? { title: '扩展配置', rows } : null
}

function buildGroups(element) {
  const bo = element.businessObject || {}
  const groups = []

  const basics = []
  pushRow(basics, '元素类型', typeLabel(element.type))
  pushRow(basics, '元素 ID', bo.id || element.id, { mono: true })
  pushRow(basics, '名称', bo.name)
  const doc = documentationText(bo)
  pushRow(basics, '说明', doc)
  if (element.parent && element.parent.businessObject && element.parent.businessObject.name) {
    pushRow(basics, '所在泳道 / 容器', element.parent.businessObject.name)
  }
  groups.push({ title: '基础信息', rows: basics })

  if (bo.$type === 'bpmn:Process') {
    const rows = []
    pushRow(rows, '流程 key', bo.id, { mono: true })
    pushRow(rows, '可执行', bo.isExecutable ? '是' : '否')
    pushRow(rows, '版本标签', bo.versionTag, { mono: true })
    pushRow(rows, '流程类别', bo.processCategory, { mono: true })
    pushRow(rows, '候选启动组', bo.candidateStarterGroups, { mono: true })
    pushRow(rows, '候选启动用户', bo.candidateStarterUsers, { mono: true })
    if (rows.length) groups.push({ title: '流程配置', rows })
  }

  if (element.type === 'bpmn:UserTask') {
    const rows = []
    pushRow(rows, '办理人 assignee', bo.assignee, { mono: true })
    pushRow(rows, '候选用户', bo.candidateUsers, { mono: true })
    pushRow(rows, '候选组 candidateGroups', bo.candidateGroups, {
      mono: true,
      tone: !bo.candidateGroups && !bo.assignee ? 'warn' : ''
    })
    pushRow(rows, '表单 key', bo.formKey, { mono: true })
    pushRow(rows, '到期日', bo.dueDate)
    pushRow(rows, '优先级', bo.priority)
    if (!bo.assignee && !bo.candidateUsers && !bo.candidateGroups) {
      rows.push({
        label: '提示',
        value: '该节点未指定办理人或候选组，运行到这里将无人可办',
        tone: 'warn'
      })
    }
    groups.push({ title: '办理配置', rows })
  }

  if (['bpmn:ServiceTask', 'bpmn:SendTask', 'bpmn:BusinessRuleTask', 'bpmn:ScriptTask'].includes(element.type)) {
    const rows = []
    pushRow(rows, '实现方式', bo.type || 'Java 类')
    pushRow(rows, 'Java 类', bo.class, { mono: true })
    pushRow(rows, '表达式', bo.expression, { mono: true })
    pushRow(rows, '委托表达式', bo.delegateExpression, { mono: true })
    pushRow(rows, '结果变量', bo.resultVariable, { mono: true })
    pushRow(rows, '外部任务主题', bo.topic, { mono: true })
    pushRow(rows, '脚本格式', bo.scriptFormat, { mono: true })
    if (bo.script) pushRow(rows, '脚本', String(bo.script).slice(0, 400), { mono: true })
    if (rows.length) groups.push({ title: '实现配置', rows })
  }

  if (element.type === 'bpmn:CallActivity') {
    const rows = []
    pushRow(rows, '被调用流程 calledElement', bo.calledElement, { mono: true })
    pushRow(rows, '绑定方式', bo.calledElementBinding, { mono: true })
    pushRow(rows, '指定版本', bo.calledElementVersion, { mono: true })
    pushRow(rows, '版本标签', bo.calledElementVersionTag, { mono: true })
    pushRow(rows, '是否继承变量', bo.inheritVariables === false ? '否' : '是')
    if (rows.length) groups.push({ title: '调用配置', rows })
  }

  const eventDefs = Array.isArray(bo.eventDefinitions) ? bo.eventDefinitions : []
  if (eventDefs.length) {
    const rows = []
    eventDefs.forEach((def, index) => {
      pushRow(rows, eventDefs.length > 1 ? `事件定义 ${index + 1}` : '事件定义', eventDefinitionText(def))
    })
    groups.push({ title: '事件定义', rows })
  }

  if (element.type === 'bpmn:SequenceFlow') {
    const rows = []
    pushRow(rows, '源节点', bo.sourceRef && (bo.sourceRef.name || bo.sourceRef.id), { mono: true })
    pushRow(rows, '目标节点', bo.targetRef && (bo.targetRef.name || bo.targetRef.id), { mono: true })
    pushRow(rows, '条件表达式', bo.conditionExpression && bo.conditionExpression.body, { mono: true })
    if (rows.length) groups.push({ title: '流转配置', rows })
  }

  const loop = bo.loopCharacteristics
  if (loop) {
    const rows = []
    if (loop.$type === 'bpmn:MultiInstanceLoopCharacteristics') {
      pushRow(rows, '多实例', loop.isSequential ? '串行' : '并行')
      pushRow(rows, '集合 collection', loop.collection, { mono: true })
      pushRow(rows, '元素变量', loop.elementVariable, { mono: true })
      pushRow(rows, '实例数', loop.loopCardinality && loop.loopCardinality.body, { mono: true })
      pushRow(rows, '完成条件', loop.completionCondition && loop.completionCondition.body, { mono: true })
    } else {
      pushRow(rows, '循环类型', typeLabel(loop.$type))
    }
    if (rows.length) groups.push({ title: '循环 / 多实例', rows })
  }

  const ext = extensionRows(bo)
  if (ext) groups.push(ext)

  return groups
}

/** 标签是独立元素，宿主被选中时只改文字颜色（不动字号字重，避免小字被挤压） */
function markHostLabel(element, on) {
  const label = element && element.label
  if (!label || !viewer) return
  try {
    const canvas = viewer.get('canvas')
    if (on) canvas.addMarker(label, 'bd-label-on')
    else canvas.removeMarker(label, 'bd-label-on')
  } catch (e) {
    // 元素已被重绘时忽略
  }
}

// ---- 上下游链路联动 ----
// 选中节点时把 incoming / outgoing 的连接线描成淡蓝，箭头用自注入的 marker，
// 否则 CSS 只能改线身颜色，箭头仍然是引擎默认的黑色，看着像断开。
const SVG_NS = 'http://www.w3.org/2000/svg'
const LINK_MARKERS = {
  in: { id: 'bd-link-arrow-in', color: '#8fbcf7' },
  out: { id: 'bd-link-arrow-out', color: '#3d8bfd' }
}
let markedLinks = []
let markedNeighbors = []

function ensureLinkMarkers() {
  const svg = canvasHost.value && canvasHost.value.querySelector('svg')
  if (!svg) return
  let defs = svg.querySelector('defs')
  if (!defs) {
    defs = document.createElementNS(SVG_NS, 'defs')
    svg.insertBefore(defs, svg.firstChild)
  }
  Object.keys(LINK_MARKERS).forEach((key) => {
    const conf = LINK_MARKERS[key]
    if (svg.querySelector('#' + conf.id)) return
    const marker = document.createElementNS(SVG_NS, 'marker')
    marker.setAttribute('id', conf.id)
    marker.setAttribute('viewBox', '0 0 20 20')
    marker.setAttribute('refX', '11')
    marker.setAttribute('refY', '10')
    marker.setAttribute('markerWidth', '10')
    marker.setAttribute('markerHeight', '10')
    marker.setAttribute('orient', 'auto')
    const path = document.createElementNS(SVG_NS, 'path')
    path.setAttribute('d', 'M 1 5 L 11 10 L 1 15 Z')
    path.setAttribute('fill', conf.color)
    path.setAttribute('stroke', conf.color)
    marker.appendChild(path)
    defs.appendChild(marker)
  })
}

function clearFlowHighlight() {
  if (viewer) {
    const canvas = viewer.get('canvas')
    markedLinks.forEach((item) => {
      try {
        canvas.removeMarker(item.el, item.marker)
      } catch (e) {
        // 元素已被重绘时忽略
      }
    })
    markedNeighbors.forEach((el) => {
      try {
        canvas.removeMarker(el, 'bd-link-node')
      } catch (e) {
        // 元素已被重绘时忽略
      }
    })
  }
  markedLinks = []
  markedNeighbors = []
}

/** 没选中任何节点时不画链路；连线本身被选中时改为点亮它的两端 */
function applyFlowHighlight(element) {
  clearFlowHighlight()
  if (!viewer || !element) return
  const canvas = viewer.get('canvas')
  ensureLinkMarkers()

  const isConnection = element.waypoints || (element.type && element.type.indexOf('bpmn:SequenceFlow') === 0)
  const neighbors = new Set()

  const paint = (conn, kind) => {
    if (!conn) return
    const marker = kind === 'in' ? 'bd-link-in' : 'bd-link-out'
    try {
      canvas.addMarker(conn, marker)
    } catch (e) {
      return
    }
    markedLinks.push({ el: conn, marker })
    if (conn.source) neighbors.add(conn.source)
    if (conn.target) neighbors.add(conn.target)
  }

  if (isConnection) {
    if (element.source) neighbors.add(element.source)
    if (element.target) neighbors.add(element.target)
  } else {
    const incoming = Array.isArray(element.incoming) ? element.incoming : []
    const outgoing = Array.isArray(element.outgoing) ? element.outgoing : []
    incoming.forEach((conn) => paint(conn, 'in'))
    outgoing.forEach((conn) => paint(conn, 'out'))
  }

  neighbors.delete(element)
  neighbors.forEach((node) => {
    if (!node || node.labelTarget) return
    try {
      canvas.addMarker(node, 'bd-link-node')
      markedNeighbors.push(node)
    } catch (e) {
      // ignore
    }
  })
}

function selectElement(element) {
  if (!viewer) return
  const canvas = viewer.get('canvas')
  if (selectedElement && selectedElement !== element) {
    try {
      canvas.removeMarker(selectedElement, 'bd-selected')
    } catch (e) {
      // 元素已被重绘时忽略
    }
    markHostLabel(selectedElement, false)
  }
  selectedElement = element
  try {
    canvas.addMarker(element, 'bd-selected')
  } catch (e) {
    // ignore
  }
  markHostLabel(element, true)
  applyFlowHighlight(element)
  const bo = element.businessObject || {}
  selected.id = bo.id || element.id || ''
  selected.name = bo.name || ''
  selected.typeLabel = typeLabel(element.type)
  selected.groups = buildGroups(element)
  drawerOpen.value = true
}

function clearSelection() {
  if (viewer && selectedElement) {
    try {
      viewer.get('canvas').removeMarker(selectedElement, 'bd-selected')
    } catch (e) {
      // ignore
    }
    markHostLabel(selectedElement, false)
  }
  clearFlowHighlight()
  selectedElement = null
  selected.id = ''
  selected.name = ''
  selected.typeLabel = ''
  selected.groups = []
  drawerOpen.value = false
}

// ---- 打开 / 关闭 ----
let previousOverflow = ''

function onKeydown(event) {
  if (event.key !== 'Escape') return
  if (rememberMenuOpen.value) {
    closeRememberMenu()
  } else if (drawerOpen.value) {
    clearSelection()
  } else {
    close()
  }
}

function close() {
  // 先把当前形态落盘，再销毁画布，否则缩放信息会丢
  persistWindow()
  closeRememberMenu()
  emit('update:open', false)
}

watch(
  () => props.open,
  async (isOpen) => {
    if (isOpen) {
      dragging.value = false
      drag = null
      closeRememberMenu()
      restoreShell()
      zoom.value = 1
      error.value = ''
      meta.processName = ''
      meta.version = null
      meta.processDefinitionId = ''
      previousOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
      window.addEventListener('keydown', onKeydown)
      window.addEventListener('resize', onWindowResize)
      document.addEventListener('pointerdown', onDocPointerDown, true)
      await nextTick()
      createViewer()
      loadDiagram()
    } else {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', onKeydown)
      window.removeEventListener('resize', onWindowResize)
      document.removeEventListener('pointerdown', onDocPointerDown, true)
      closeRememberMenu()
      endDrag()
      destroyViewer()
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  document.body.style.overflow = previousOverflow
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onWindowResize)
  document.removeEventListener('pointerdown', onDocPointerDown, true)
  endDrag()
  destroyViewer()
})
</script>

<style scoped>
.bd-mask {
  position: fixed;
  inset: 0;
  z-index: 1200;
  /* 不用 backdrop-filter：它会把弹窗整体提升为独立合成层，
     SVG 文字随后按灰度抗锯齿渲染，小字号中文看着就是发虚、有重影 */
  background: rgba(15, 23, 32, 0.45);
}

.bd-shell {
  position: fixed;
  inset: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-elevated);
  overflow: hidden;
  box-shadow: var(--shadow-lg);
}

.bd-shell.is-window {
  inset: auto;
  border-radius: 10px;
  border: 1px solid var(--border);
}

.bd-shell.is-dragging {
  user-select: none;
  cursor: grabbing;
}

.bd-head {
  position: relative;
  /* 高于下方画布，否则图钉菜单会被 .bd-body 的定位层盖住 */
  z-index: 12;
  flex: 0 0 auto;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 10px 0 16px;
  border-bottom: 1px solid #eef0f3;
  background: linear-gradient(180deg, var(--bg-elevated) 0%, var(--bg-subtle) 100%);
}

.bd-head.can-drag {
  cursor: grab;
}

.bd-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.bd-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #1677ff;
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.12);
  flex: 0 0 auto;
}

.bd-title {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.bd-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-strong);
  line-height: 20px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bd-sub {
  font-size: 11px;
  color: var(--text-tertiary);
  line-height: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bd-sub .sep {
  margin: 0 5px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.bd-head-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  min-width: 0;
}

.head-sep {
  width: 1px;
  height: 18px;
  margin: 0 2px;
  background: var(--bg-active);
  flex: 0 0 auto;
}

.drag-hint {
  margin-right: 6px;
  font-size: 11px;
  color: var(--text-tertiary);
  white-space: nowrap;
}

.icon-btn {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text);
  font-size: 15px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.icon-btn:hover {
  background: var(--bg-active);
  color: var(--text-strong);
}

.remember-wrap {
  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
}

.remember-btn {
  position: relative;
}

/* 三档用颜色区分：蓝=全记，赭=只记全屏，灰=不记 */
.remember-btn.mode-all {
  color: #1677ff;
  background: rgba(22, 119, 255, 0.08);
}

.remember-btn.mode-all:hover {
  color: #1677ff;
  background: rgba(22, 119, 255, 0.14);
}

.remember-btn.mode-fullscreen {
  color: #d46b08;
  background: rgba(212, 107, 8, 0.1);
}

.remember-btn.mode-fullscreen:hover {
  color: #d46b08;
  background: rgba(212, 107, 8, 0.16);
}

/* 只记全屏时在图钉右下角加一个小方框，避免和"全记"混淆 */
.remember-badge {
  position: absolute;
  right: 5px;
  bottom: 5px;
  width: 6px;
  height: 6px;
  border: 1.5px solid currentColor;
  border-radius: 1px;
  opacity: 0.8;
}

.remember-menu {
  position: absolute;
  top: calc(100% + 6px);
  right: -4px;
  z-index: 30;
  width: 232px;
  padding: 6px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.14);
}

.remember-menu-tip {
  padding: 3px 8px 6px;
  font-size: 11px;
  color: var(--text-tertiary);
}

.remember-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.12s;
}

.remember-item:hover {
  background: var(--bg-hover);
}

.remember-item.is-active {
  background: rgba(22, 119, 255, 0.07);
}

.item-check {
  flex: 0 0 14px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #1677ff;
  font-size: 11px;
}

.item-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.item-label {
  font-size: 12.5px;
  line-height: 18px;
  color: var(--text-strong);
}

.remember-item.is-active .item-label {
  color: #1677ff;
  font-weight: 600;
}

.item-desc {
  font-size: 11px;
  line-height: 15px;
  color: var(--text-tertiary);
}

.bd-fade-enter-active,
.bd-fade-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.bd-fade-enter-from,
.bd-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.bd-body {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  background: #f7f8fa;
}

.bd-canvas {
  position: absolute;
  inset: 0;
  background-color: #fbfcfd;
  background-image: radial-gradient(rgba(0, 0, 0, 0.05) 1px, transparent 1px);
  background-size: 20px 20px;
}

/* bpmn-js 的标签默认用 Arial，中文会回落到宋体，小字号下笔画黏连、看着像重影。
   显式指定中文黑体，并启用几何精度与灰度抗锯齿，文字边缘才干净 */
.bd-canvas :deep(.djs-container text),
.bd-canvas :deep(.djs-container tspan) {
  font-family: 'PingFang SC', 'Microsoft YaHei', 'Hiragino Sans GB', 'Helvetica Neue', Arial, sans-serif !important;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-rendering: geometricPrecision;
}

.bd-canvas :deep(.djs-container svg) {
  shape-rendering: geometricPrecision;
}

/* 画布工具栏：嵌在标题栏右侧，与全屏、关闭按钮同一组 */
.bd-tools {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0;
  flex: 0 0 auto;
}

.bd-tools button {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--text);
  font-size: 14px;
  cursor: pointer;
}

.bd-tools button:hover {
  background: rgba(22, 119, 255, 0.08);
  color: #1677ff;
}

.zoom-val {
  min-width: 40px;
  text-align: center;
  font-size: 12px;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

.tool-sep {
  width: 1px;
  height: 16px;
  margin: 0 3px;
  background: var(--bg-active);
}

.bd-tip {
  position: absolute;
  left: 14px;
  bottom: 12px;
  z-index: 5;
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid var(--border);
  font-size: 11px;
  color: var(--text-tertiary);
  pointer-events: none;
}

.bd-state {
  position: absolute;
  inset: 0;
  z-index: 8;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: rgba(250, 251, 252, 0.94);
  text-align: center;
  padding: 24px;
}

.state-title {
  margin: 4px 0 0;
  font-size: 14px;
  color: var(--text);
}

.state-sub {
  margin: 0;
  font-size: 12px;
  color: var(--text-tertiary);
}

.state-error {
  margin: 0;
  max-width: 520px;
  font-size: 13px;
  color: #cf1322;
  line-height: 1.6;
}

.state-btn {
  margin-top: 8px;
  padding: 5px 16px;
  border-radius: 6px;
  border: 1px solid #1677ff;
  background: var(--bg-elevated);
  color: #1677ff;
  font-size: 13px;
  cursor: pointer;
}

.spinner {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: 2px solid rgba(22, 119, 255, 0.2);
  border-top-color: #1677ff;
  animation: bd-spin 0.8s linear infinite;
}

@keyframes bd-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 属性抽屉 */
.bd-drawer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 10;
  width: 340px;
  max-width: 82vw;
  display: flex;
  flex-direction: column;
  background: var(--bg-elevated);
  border-left: 1px solid var(--border);
  box-shadow: -12px 0 32px rgba(0, 0, 0, 0.08);
}

.drawer-head {
  flex: 0 0 auto;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 8px 12px 16px;
  border-bottom: 1px solid var(--border-subtle);
}

.drawer-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-strong);
  line-height: 20px;
  word-break: break-word;
}

.drawer-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.type-tag {
  display: inline-block;
  padding: 1px 7px;
  border-radius: 4px;
  background: rgba(22, 119, 255, 0.1);
  color: #1677ff;
  font-size: 11px;
  line-height: 18px;
}

.drawer-id {
  font-size: 11px;
  color: var(--text-tertiary);
  word-break: break-all;
}

.drawer-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 16px 24px;
}

.prop-group + .prop-group {
  margin-top: 16px;
}

.prop-group-title {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-tertiary);
  letter-spacing: 0.3px;
}

.prop-row {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr);
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed #f2f3f5;
}

.prop-row:last-child {
  border-bottom: 0;
}

.prop-label {
  font-size: 12px;
  color: var(--text-tertiary);
  line-height: 18px;
  word-break: break-word;
}

.prop-value {
  font-size: 12px;
  color: var(--text-strong);
  line-height: 18px;
  word-break: break-word;
  white-space: pre-wrap;
}

.prop-value.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
}

.prop-value.is-warn {
  color: #ad4e00;
  font-weight: 500;
}

/* 抽屉滑入 */
.bd-slide-enter-active,
.bd-slide-leave-active {
  transition: transform 0.18s ease, opacity 0.18s ease;
}

.bd-slide-enter-from,
.bd-slide-leave-to {
  transform: translateX(100%);
  opacity: 0.6;
}

/* 选中节点高亮：bpmn-js 生成的 DOM 不在 scoped 作用域内，用 :deep 穿透 */

/* 引擎自带的选中框是贴包围盒的直角矩形，会把节点和标签一起框住，
   与我们自己的高亮叠成两三层，这里统一隐藏 */
.bd-canvas :deep(.djs-outline),
.bd-canvas :deep(.djs-selection-outline) {
  display: none !important;
}

/* 上下游联动：相邻节点只描一圈很淡的蓝，主选中仍是深蓝，层级一眼可分 */
.bd-canvas :deep(.bd-link-node .djs-visual > :first-child:not(text)) {
  stroke: #aecbfa !important;
  stroke-width: 1.5px !important;
}

/* 连接线的视觉层就是 path 本身，不套 .djs-visual > 的层级。
   marker-end 走自注入的 defs，否则线身变蓝、箭头还是黑的，看着像断线 */
.bd-canvas :deep(.bd-link-in .djs-visual) {
  stroke: #8fbcf7 !important;
  stroke-width: 2px !important;
  marker-end: url(#bd-link-arrow-in) !important;
}

.bd-canvas :deep(.bd-link-out .djs-visual) {
  stroke: #3d8bfd !important;
  stroke-width: 2px !important;
  marker-end: url(#bd-link-arrow-out) !important;
}

/* 只描图形本体的第一个子元素。排除 text 是关键：
   命中文字会让笔画被描边加粗，中文小字挤作一团，正是"重影"的来源 */
.bd-canvas :deep(.bd-selected .djs-visual > :first-child:not(text)) {
  stroke: #1677ff !important;
  stroke-width: 2px !important;
}

/* 直接点中连接线时，线身加深，箭头沿用出方向的标记 */
.bd-canvas :deep(.bd-selected.djs-connection .djs-visual) {
  stroke: #1677ff !important;
  stroke-width: 2.5px !important;
  marker-end: url(#bd-link-arrow-out) !important;
}

/* 宿主被选中时，关联标签文字同步变蓝，替代引擎那个难看的包围盒 */
.bd-canvas :deep(.bd-label-on .djs-visual text),
.bd-canvas :deep(.bd-label-on .djs-visual tspan) {
  fill: #1677ff !important;
}

.bd-canvas :deep(.djs-container) {
  outline: none;
}

@media (max-width: 640px) {
  .bd-head {
    height: 48px;
    padding-left: 12px;
    padding-right: 6px;
    gap: 8px;
  }

  .bd-sub {
    display: none;
  }

  .drag-hint {
    display: none;
  }

  .bd-head-actions {
    gap: 2px;
  }

  .bd-tools button {
    width: 26px;
    height: 26px;
  }

  .bd-tools .tool-opt {
    display: none;
  }

  .zoom-val {
    min-width: 34px;
    font-size: 11px;
  }

  .head-sep {
    margin: 0;
  }

  .bd-drawer {
    width: 100%;
    max-width: 100%;
  }

  .bd-tip {
    display: none;
  }
}

@media (max-width: 420px) {
  .zoom-val {
    display: none;
  }
}
</style>
