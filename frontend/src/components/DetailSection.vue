<template>
  <div class="drawer-section">
    <div class="drawer-section-title">
      <slot name="title">{{ title }}</slot>
      <span v-if="extra" class="comm-session-count">{{ extra }}</span>
    </div>
    <slot />
  </div>
</template>

<script setup>
/**
 * 详情区块：统一「标题 + 计数 + 内容」的排版。
 * 标题右侧的 extra 用于「共 N 次」这类计数，样式与沟通会话计数保持一致。
 */
defineProps({
  title: { type: String, default: '' },
  extra: { type: String, default: '' }
})
</script>
