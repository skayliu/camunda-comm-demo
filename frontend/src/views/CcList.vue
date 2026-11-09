<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的待阅</h2>
        <p class="page-desc">传阅副本已送达，仅可查看；阅读不影响流程流转，也不会进入审批待办</p>
      </div>
      <a-space>
        <a-button :disabled="!selectedIds.length" @click="batchRead">批量标已读</a-button>
        <a-button type="primary" :loading="loading" @click="load">刷新</a-button>
      </a-space>
    </div>

    <a-card :bordered="false" style="border-radius: 10px">
      <div class="toolbar">
        <a-input-search v-model:value="keyword" placeholder="搜索标题、发送人" allow-clear style="max-width: 300px" @search="search" />
        <a-segmented v-model:value="status" :options="statusOptions" @change="search" />
      </div>

      <a-table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="id"
        :row-selection="{ selectedRowKeys: selectedIds, onChange: (keys) => (selectedIds = keys) }"
        :pagination="pagination"
        size="middle"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'title'">
            <div class="table-title">
              <span class="table-title-main">{{ record.title }}</span>
              <span class="table-title-sub">{{ record.processDefinitionKey || '-' }} · {{ shortId(record.processInstanceId) }}</span>
              <span v-if="record.note" class="cc-note">附言：{{ record.note }}</span>
            </div>
          </template>
          <template v-else-if="column.key === 'type'">
            <a-tag color="purple">{{ record.ccTypeName }}</a-tag>
            <div v-if="record.taskDefinitionKey" class="muted" style="font-size: 12px">节点 {{ record.taskDefinitionKey }}</div>
          </template>
          <template v-else-if="column.key === 'sender'">
            {{ record.senderName || record.sender || '-' }}
          </template>
          <template v-else-if="column.key === 'createTime'">
            {{ fmt(record.createTime) }}
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag v-if="record.revoked" color="default">{{ record.revokeReason === 'INVALID' ? '已失效' : '已撤回' }}</a-tag>
            <a-tag v-else-if="record.read" color="green">已读</a-tag>
            <a-tag v-else color="orange">未读</a-tag>
            <div class="muted" style="font-size: 12px">{{ record.processRunning ? '流程进行中' : '流程已结束' }}</div>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-button type="link" size="small" @click="openTrace(record)">查看流程</a-button>
              <a-button v-if="record.canRead" type="link" size="small" @click="markRead(record)">标已读</a-button>
              <a-popconfirm v-if="record.canRevoke" title="撤回这条传阅？" ok-text="撤回" cancel-text="取消" @confirm="revoke(record)">
                <a-button type="link" size="small" danger>撤回</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
        <template #emptyText>
          <a-empty description="暂无待阅的传阅" />
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
import { ccApi, errorText } from '../api'

const ProcessTraceModal = defineAsyncComponent(() => import('../components/ProcessTraceModal.vue'))

const loading = ref(false)
const rows = ref([])
const keyword = ref('')
const status = ref('UNREAD')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const selectedIds = ref([])
const traceOpen = ref(false)
const tracePid = ref('')
const traceMounted = ref(false)

const statusOptions = [
  { label: '未读', value: 'UNREAD' },
  { label: '已读', value: 'READ' },
  { label: '已撤回', value: 'REVOKED' },
  { label: '全部', value: 'ALL' }
]
const columns = [
  { title: '标题', key: 'title', width: 260 },
  { title: '传阅类型', key: 'type', width: 150 },
  { title: '发送人', key: 'sender', width: 120 },
  { title: '传阅时间', key: 'createTime', width: 150 },
  { title: '状态', key: 'status', width: 120 },
  { title: '操作', key: 'action', width: 200, fixed: 'right' }
]
const pagination = computed(() => ({
  current: page.value,
  pageSize: size.value,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50'],
  showTotal: (t) => `共 ${t} 条`
}))

function fmt(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'
}
function shortId(id) {
  return id && id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-6)}` : id || '-'
}

async function load() {
  loading.value = true
  try {
    const res = await ccApi.mine({ status: status.value, keyword: keyword.value, page: page.value, size: size.value })
    rows.value = res.list || []
    total.value = res.total || 0
    selectedIds.value = []
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}
function search() {
  page.value = 1
  load()
}
function onTableChange(pag) {
  page.value = pag.current
  size.value = pag.pageSize
  load()
}
async function markRead(record) {
  try {
    await ccApi.markRead(record.id)
    message.success('已标记为已读')
    load()
  } catch (e) {
    message.error(errorText(e))
  }
}
async function batchRead() {
  try {
    const res = await ccApi.batchMarkRead(selectedIds.value)
    message.success(`已标记 ${res.updated || 0} 条已读`)
    load()
  } catch (e) {
    message.error(errorText(e))
  }
}
async function revoke(record) {
  try {
    await ccApi.revoke(record.id)
    message.success('已撤回')
    load()
  } catch (e) {
    message.error(errorText(e))
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
.cc-note {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
