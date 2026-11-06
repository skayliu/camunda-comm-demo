<template>
  <div v-if="list.length" class="delegation-chain">
    <div v-for="(item, i) in list" :key="item.id || i" class="delegation-item">
      <div class="delegation-line">
        <span class="delegation-user">{{ item.owner || '-' }}</span>
        <span class="delegation-arrow">⇢</span>
        <span class="delegation-user is-agent">{{ item.delegateUser || '-' }}</span>
        <a-tag :color="statusColor(item.status)" style="margin-inline-end: 0">
          {{ statusText(item.status) }}
        </a-tag>
        <a-tag v-if="item.source === 'AGENT'" color="purple" style="margin-inline-end: 0">代理</a-tag>
      </div>
      <div class="delegation-meta">
        {{ fmt(item.createTime) }}
        <span v-if="item.taskName" class="muted"> · {{ item.taskName }}</span>
      </div>
      <div v-if="item.reason" class="delegation-reason">{{ item.reason }}</div>
      <div v-if="item.resolveContent" class="delegation-resolve">
        反馈：{{ item.resolveContent }}
        <span class="muted" style="font-size: 11px">{{ item.resolveTime ? fmt(item.resolveTime) : '' }}</span>
      </div>
    </div>
  </div>
  <div v-else-if="emptyText" class="empty-tip">{{ emptyText }}</div>
</template>

<script setup>
import { computed } from 'vue'
import dayjs from 'dayjs'

/**
 * 委托链：OWNER（委托人）→ 受托人。
 * 与转办不同，委托不改变责任归属，因此用 ⇢ 而不是 →，并展示反馈与状态。
 */
const props = defineProps({
  delegations: { type: Array, default: () => [] },
  emptyText: { type: String, default: '' }
})

const list = computed(() => (Array.isArray(props.delegations) ? props.delegations : []))

function fmt(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'
}

function statusText(status) {
  return { PENDING: '委托中', RESOLVED: '已交回', CANCELED: '已收回' }[status] || status || '-'
}

function statusColor(status) {
  return { PENDING: 'orange', RESOLVED: 'green', CANCELED: 'default' }[status] || 'default'
}
</script>

<style scoped>
.delegation-chain {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.delegation-item {
  border-left: 3px solid #d6e4ff;
  padding: 6px 0 6px 10px;
}
.delegation-line {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 13px;
}
.delegation-user {
  font-weight: 600;
}
.delegation-user.is-agent {
  color: #722ed1;
}
.delegation-arrow {
  color: #9254de;
}
.delegation-meta {
  margin-top: 2px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}
.delegation-reason {
  margin-top: 4px;
  font-size: 12.5px;
  color: rgba(0, 0, 0, 0.65);
}
.delegation-resolve {
  margin-top: 6px;
  padding: 6px 8px;
  background: #f6ffed;
  border-radius: 6px;
  font-size: 12.5px;
  color: #3f6600;
}
</style>
