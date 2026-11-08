<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">委托记录</h2>
        <p class="page-desc">
          委托不转移责任：受托人提交反馈后任务回到我这里，由我确认办结
        </p>
      </div>
      <a-button :loading="loading" @click="load">
        <template #icon><ReloadOutlined /></template>
        刷新
      </a-button>
    </div>

    <a-card :bordered="false" style="border-radius: 10px">
      <a-tabs v-model:activeKey="tab" @change="onTabChange">
        <a-tab-pane key="out" tab="我的委托" />
        <a-tab-pane key="in" tab="我的受托" />
      </a-tabs>

      <div class="toolbar">
        <a-segmented v-model:value="state" :options="stateOptions" />
        <a-input-search
          v-model:value="keyword"
          placeholder="搜索任务、流程实例、人员"
          allow-clear
          style="max-width: 280px"
        />
        <span class="muted" style="font-size: 13px">
          共 {{ filtered.length }} 条{{ tab === 'out' ? '委托记录' : '受托记录' }}
        </span>
      </div>

      <a-table
        :columns="columns"
        :data-source="filtered"
        :loading="loading"
        row-key="id"
        :pagination="{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }"
        :scroll="{ x: 980 }"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'task'">
            <div class="table-title">
              <span class="table-title-main">{{ record.taskName || record.taskDefKey || '任务' }}</span>
              <span class="table-title-sub">{{ shortId(record.processInstanceId) }}</span>
            </div>
            <a-tag v-if="!record.taskActive" color="default" style="margin-top: 4px">
              流程已流转
            </a-tag>
          </template>

          <template v-else-if="column.key === 'chain'">
            <a-space :size="6">
              <span :class="{ 'is-me': tab === 'out' }">{{ record.owner || '-' }}</span>
              <span class="arrow">⇢</span>
              <span :class="{ 'is-me': tab === 'in' }">{{ record.delegateUser || '-' }}</span>
            </a-space>
          </template>

          <template v-else-if="column.key === 'source'">
            <a-tag v-if="record.source === 'AGENT'" color="purple">代理规则</a-tag>
            <a-tag v-else>手动委托</a-tag>
          </template>

          <template v-else-if="column.key === 'state'">
            <a-tag :color="stateOf(record).color">{{ stateOf(record).text }}</a-tag>
          </template>

          <template v-else-if="column.key === 'time'">
            <div>{{ fmt(record.createTime) }}</div>
            <div v-if="record.resolveTime" class="muted" style="font-size: 12px">
              {{ fmt(record.resolveTime) }}
            </div>
          </template>

          <template v-else-if="column.key === 'feedback'">
            <span v-if="record.resolveContent">{{ record.resolveContent }}</span>
            <span v-else class="muted">
              {{ record.status === 'CANCELED' ? '委托人收回，未留反馈' : '—' }}
            </span>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-popconfirm
                v-if="tab === 'out' && record.taskActive && record.status === 'PENDING'"
                title="收回后任务回到你的待办，受托人将无法继续处理"
                ok-text="确认收回"
                cancel-text="取消"
                @confirm="cancel(record)"
              >
                <a-button type="link" size="small" danger :loading="canceling === record.taskId">
                  收回
                </a-button>
              </a-popconfirm>
              <a-button type="link" size="small" @click="openTrace(record)">流程跟踪</a-button>
            </a-space>
          </template>
        </template>

        <template #emptyText>
          <a-empty :description="emptyText" />
        </template>
      </a-table>
    </a-card>

    <ProcessTraceModal v-if="traceMounted" v-model:open="traceOpen" :process-instance-id="tracePid" />
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, ref } from 'vue'
import message from 'ant-design-vue/es/message'
import dayjs from 'dayjs'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { errorText, wbApi } from '../api'
// 跟踪弹窗按需加载：只在点开时下载
const ProcessTraceModal = defineAsyncComponent(() => import('../components/ProcessTraceModal.vue'))

const emit = defineEmits(['refresh-count'])

const tab = ref('out')
const state = ref('all')
const keyword = ref('')
const rows = ref([])
const loading = ref(false)
const canceling = ref('')
const traceOpen = ref(false)
const tracePid = ref('')
const traceMounted = ref(false)

const stateOptions = [
  { label: '全部', value: 'all' },
  { label: '委托中', value: 'PENDING' },
  { label: '已交回', value: 'RESOLVED' },
  { label: '已收回', value: 'CANCELED' },
  { label: '任务已办结', value: 'CLOSED' }
]

const columns = [
  { title: '委托任务', key: 'task', width: 230 },
  { title: '委托人 → 受托人', key: 'chain', width: 190 },
  { title: '来源', key: 'source', width: 100 },
  { title: '状态', key: 'state', width: 110 },
  { title: '委托时间', key: 'time', width: 150 },
  { title: '受托人反馈', key: 'feedback', width: 220, ellipsis: true },
  { title: '操作', key: 'action', width: 150, fixed: 'right' }
]

const emptyText = computed(() =>
  tab.value === 'out' ? '你还没有把待办委托给别人' : '还没有人把待办委托给你'
)

/**
 * 状态口径：
 * taskActive=false 说明任务已经办了（或流程被删除），记录只用于留痕，
 * 这时无论业务状态是 PENDING 还是 RESOLVED，都不该再显示“委托中”。
 */
function stateOf(record) {
  if (record.taskActive === false) {
    return { key: 'CLOSED', text: '任务已办结', color: 'default' }
  }
  const map = {
    PENDING: { key: 'PENDING', text: '委托中', color: 'orange' },
    RESOLVED: { key: 'RESOLVED', text: '已交回', color: 'green' },
    CANCELED: { key: 'CANCELED', text: '已收回', color: 'default' }
  }
  return map[record.status] || { key: '', text: record.status || '-', color: 'default' }
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter((r) => {
    if (state.value !== 'all' && stateOf(r).key !== state.value) return false
    if (!kw) return true
    return [r.taskName, r.taskDefKey, r.processInstanceId, r.owner, r.delegateUser]
      .filter(Boolean)
      .some((v) => String(v).toLowerCase().includes(kw))
  })
})

function fmt(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'
}

function shortId(id) {
  if (!id) return ''
  return id.length > 18 ? `${id.slice(0, 10)}…${id.slice(-6)}` : id
}

async function load() {
  loading.value = true
  try {
    const res = tab.value === 'out'
      ? await wbApi.delegationsOut()
      : await wbApi.delegationsIn()
    rows.value = Array.isArray(res) ? res : []
  } catch (e) {
    message.error(errorText(e))
    rows.value = []
  } finally {
    loading.value = false
  }
}

function onTabChange() {
  state.value = 'all'
  keyword.value = ''
  load()
}

async function cancel(record) {
  canceling.value = record.taskId
  try {
    await wbApi.cancelDelegation(record.taskId, '')
    message.success('已收回，任务回到你的待办')
    // 收回后任务回到自己名下：待办数 +1、委托中 -1，让菜单角标当场跟着变
    emit('refresh-count')
    await load()
  } catch (e) {
    message.error(errorText(e))
  } finally {
    canceling.value = ''
  }
}

function openTrace(record) {
  tracePid.value = record.processInstanceId
  traceMounted.value = true
  traceOpen.value = true
}

onMounted(load)
</script>

<style scoped>
.is-me {
  font-weight: 600;
  color: #1677ff;
}
.arrow {
  color: #9254de;
}
</style>
