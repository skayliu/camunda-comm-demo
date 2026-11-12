<template>
  <a-drawer
    :open="open"
    :width="drawerWidth"
    :body-style="{ paddingBottom: '16px' }"
    destroy-on-close
    @close="close"
  >
    <template #title>
      <slot name="title">
        <span>{{ title }}</span>
      </slot>
    </template>

    <a-spin :spinning="loading">
      <slot />
    </a-spin>

    <!-- 弹窗等与加载遮罩无关的内容：挂在 spin 之外，避免无谓地包进遮罩层 -->
    <slot name="extra" />

    <template v-if="$slots.footer" #footer>
      <div class="drawer-footer">
        <slot name="footer" />
      </div>
    </template>
  </a-drawer>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 详情抽屉外壳：三处详情（待办 / 已办 / 我的发起）共用。
 *
 * 统一了宽度、移动端全屏、关闭即销毁、加载态与底部操作区，
 * 业务内容通过默认插槽传入，标题与操作通过具名插槽覆盖。
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  width: { type: Number, default: 560 },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open', 'close'])

const winWidth = ref(window.innerWidth)
const isMobile = ref(window.innerWidth <= 768)

// 原实现只在组件创建时判一次宽度，旋转屏幕或拖动窗口后不再更新，
// 这里补上监听，退出时移除。
function syncMobile() {
  winWidth.value = window.innerWidth
  isMobile.value = window.innerWidth <= 768
}

onMounted(() => window.addEventListener('resize', syncMobile))
onBeforeUnmount(() => window.removeEventListener('resize', syncMobile))

// 宽抽屉（如待办详情 780）在 769–779 的窄视口里会超出屏幕右侧，
// 这里用视口宽兜底；宽度小于视口时仍取传入值，不影响其他抽屉。
const drawerWidth = computed(() =>
  isMobile.value ? '100%' : Math.min(props.width, winWidth.value)
)

function close() {
  emit('update:open', false)
  emit('close')
}
</script>
