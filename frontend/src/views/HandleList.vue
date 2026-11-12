<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的承办</h2>
        <p class="page-desc">阅后办理并反馈结果；承办不进审批待办，也不阻塞主流程</p>
      </div>
      <a-button type="primary" :loading="loading" @click="load">刷新</a-button>
    </div>

    <a-card :bordered="false">
      <div class="toolbar">
        <a-input-search v-model:value="keyword" placeholder="搜索标题、办理要求" allow-clear style="max-width: 300px" @search="search" />
        <a-segmented v-model:value="status" :options="statusOptions" @change="search" />
      </div>

      <a-table :columns="columns" :data-source="rows" :loading="loading" row-key="id" :pagination="pagination" size="middle" @change="onTableChange">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'title'">
            <div class="table-title">
              <span class="table-title-main">{{ record.title }}</span>
              <span class="table-title-sub">{{ record.taskDefinitionKey || '流程' }} · {{ shortId(record.processInstanceId) }}</span>
            </div>
            <div v-if="record.instruction" class="muted" style="font-size: 12px">要求：{{ record.instruction }}</div>
          </template>
          <template v-else-if="column.key === 'deadline'">
            <span :style="record.overdue ? { color: 'var(--danger)' } : null">{{ fmt(record.deadline) }}</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag v-if="record.revoked" color="default">已撤回</a-tag>
            <a-tag v-else :color="statusColor(record.handleStatus)">{{ statusText(record.handleStatus) }}</a-tag>
            <div class="muted" style="font-size: 12px">{{ record.processRunning ? '流程进行中' : '流程已结束' }}</div>
          </template>
          <template v-else-if="column.key === 'result'">
            <div v-if="record.handleResult">{{ record.handleResult }}</div>
            <span v-else class="muted">-</span>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-button v-if="record.canSubmit" type="link" size="small" @click="openSubmit(record)">办理 / 反馈</a-button>
              <a-button type="link" size="small" @click="openTrace(record)">查看流程</a-button>
              <a-popconfirm v-if="record.canRevoke" title="撤回这条阅办？" ok-text="撤回" cancel-text="取消" @confirm="revoke(record)">
                <a-button type="link" size="small" danger>撤回</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
        <template #emptyText>
          <a-empty description="暂无承办任务" />
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="submitOpen" title="提交办理结果" :confirm-loading="submitting" ok-text="提交" cancel-text="取消" @ok="submit">
      <a-alert v-if="current.instruction" type="info" show-icon :message="`办理要求：${current.instruction}`" style="margin-bottom: 10px" />
      <a-textarea v-model:value="resultText" :rows="5" :maxlength="2000" show-count placeholder="填写实际办理结果与结论" />
    </a-modal>

    <ProcessTraceModal v-if="traceMounted" v-model:open="traceOpen" :process-instance-id="tracePid" />
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, reactive, ref } from 'vue'
import message from 'ant-design-vue/es/message'
import dayjs from 'dayjs'
import { errorText, handleApi } from '../api'

const ProcessTraceModal = defineAsyncComponent(() => import('../components/ProcessTraceModal.vue'))

const loading = ref(false)
const rows = ref([])
const keyword = ref('')
const status = ref('TODO')
const page = ref(1)
const size = ref(10)
const total = ref(0)

const submitOpen = ref(false)
const submitting = ref(false)
const resultText = ref('')
const current = reactive({ id: '', instruction: '' })

const traceOpen = ref(false)
const tracePid = ref('')
const traceMounted = ref(false)

const statusOptions = [
  { label: '待办', value: 'TODO' },
  { label: '已逾期', value: 'OVERDUE' },
  { label: '已完成', value: 'DONE' },
  { label: '全部', value: 'ALL' }
]
const columns = [
  { title: '事项', key: 'title', width: 280 },
  { title: '办理时限', key: 'deadline', width: 150 },
  { title: '状态', key: 'status', width: 120 },
  { title: '办理结果', key: 'result', width: 220 },
  { title: '操作', key: 'action', width: 210, fixed: 'right' }
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
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '不限期'
}
function shortId(id) {
  return id && id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-6)}` : id || '-'
}
function statusText(value) {
  return { PENDING: '待阅', HANDLING: '办理中', DONE: '已完成', OVERDUE: '已逾期' }[value] || value
}
function statusColor(value) {
  return { PENDING: 'orange', HANDLING: 'blue', DONE: 'green', OVERDUE: 'red' }[value] || 'default'
}

async function load() {
  loading.value = true
  try {
    const res = await handleApi.mine({ status: status.value, keyword: keyword.value, page: page.value, size: size.value })
    rows.value = res.list || []
    total.value = res.total || 0
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
function openSubmit(record) {
  current.id = record.id
  current.instruction = record.instruction || ''
  resultText.value = record.handleResult || ''
  submitOpen.value = true
}
async function submit() {
  if (!resultText.value.trim()) {
    message.warning('请填写办理结果')
    return
  }
  submitting.value = true
  try {
    await handleApi.start(current.id)
    await handleApi.submit(current.id, resultText.value.trim())
    message.success('办理结果已提交')
    submitOpen.value = false
    load()
  } catch (e) {
    message.error(errorText(e))
  } finally {
    submitting.value = false
  }
}
async function revoke(record) {
  try {
    await handleApi.revoke(record.id)
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
