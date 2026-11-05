<template>
  <div v-if="list.length" class="transfer-chain">
    <div v-for="(item, index) in list" :key="item.id || index" class="transfer-item">
      <div class="transfer-item-line">
        <span class="transfer-user">{{ item.from || '-' }}</span>
        <span class="transfer-arrow">→</span>
        <span class="transfer-user">{{ item.to || '-' }}</span>
        <a-tag
          v-if="isCurrentAssignee(index, item)"
          color="blue"
          style="margin-inline-end: 0"
        >
          当前办理人
        </a-tag>
      </div>
      <div class="transfer-meta">
        {{ fmt(item.createTime) }}
        <span v-if="item.taskName" class="muted"> · {{ item.taskName }}</span>
      </div>
      <div v-if="item.reason" class="transfer-reason">{{ item.reason }}</div>
    </div>
  </div>
  <div v-else-if="emptyText" class="empty-tip">{{ emptyText }}</div>
</template>

<script setup>
import { computed } from 'vue'
import dayjs from 'dayjs'

/**
 * 转办链：一条待办一路由谁交给了谁（按时间升序）。
 *
 * 数据由后端保证升序，这里不再排序，避免与落库顺序不一致。
 * 「当前办理人」只在最后一条的接收人 == 传入的 currentAssignee 时标注：
 * 已办 / 我的发起里最后一条接收人往往只是历史办理人，无条件标注会误导。
 */
const props = defineProps({
  transfers: { type: Array, default: () => [] },
  currentAssignee: { type: String, default: '' },
  emptyText: { type: String, default: '' }
})

const list = computed(() => (Array.isArray(props.transfers) ? props.transfers : []))

function isCurrentAssignee(index, item) {
  return (
    !!props.currentAssignee &&
    index === list.value.length - 1 &&
    item.to === props.currentAssignee
  )
}

function fmt(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'
}
</script>
