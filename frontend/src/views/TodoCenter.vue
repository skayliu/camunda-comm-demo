<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">待办中心</h2>
        <p class="page-desc">审批待办与沟通反馈统一入口，处理完自动流转</p>
      </div>
      <a-space>
        <a-button :loading="creating" @click="createProcess">
          <template #icon><PlusOutlined /></template>
          发起新流程
        </a-button>
        <a-button type="primary" :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </a-space>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-icon" style="background: #e6f4ff; color: #1677ff">
          <InboxOutlined />
        </div>
        <div>
          <div class="stat-value">{{ stats.total }}</div>
          <div class="stat-label">待处理总数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: #e6fffb; color: #13c2c2">
          <AuditOutlined />
        </div>
        <div>
          <div class="stat-value">{{ stats.approval }}</div>
          <div class="stat-label">审批待办</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: #f9f0ff; color: #722ed1">
          <MessageOutlined />
        </div>
        <div>
          <div class="stat-value">{{ stats.comm }}</div>
          <div class="stat-label">沟通待反馈</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background: #fff7e6; color: #fa8c16">
          <ClockCircleOutlined />
        </div>
        <div>
          <div class="stat-value">{{ stats.today }}</div>
          <div class="stat-label">今日新增</div>
        </div>
      </div>
    </div>

    <a-card :bordered="false" style="border-radius: 10px">
      <div class="toolbar">
        <a-input-search
          v-model:value="keyword"
          placeholder="搜索流程、发起人、实例ID"
          allow-clear
          style="max-width: 320px"
          @search="search"
        />
        <a-segmented v-model:value="type" :options="typeOptions" @change="search" />
      </div>

      <a-table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="taskId"
        :pagination="pagination"
        :scroll="{ x: 820 }"
        size="middle"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'title'">
            <div class="table-title">
              <span class="table-title-main">{{ record.processTitle }}</span>
              <span class="table-title-sub">{{ shortId(record.processInstanceId) }}</span>
            </div>
          </template>

          <template v-else-if="column.key === 'node'">
            <a-space :size="6" wrap>
              <span>{{ record.taskName }}</span>
              <a-tag :color="!record.isCommTask ? 'blue' : (record.isFollowUp ? 'gold' : 'purple')">
                {{ !record.isCommTask ? '审批' : (record.isFollowUp ? '沟通' : '首轮沟通') }}
              </a-tag>
            </a-space>
            <div v-if="record.isCommTask && record.sourceTaskDefKey" class="muted" style="font-size: 12px">
              来自 {{ record.sourceTaskDefKey }} 节点
            </div>
            <div v-else-if="record.transferCount" class="muted" style="font-size: 12px">
              由 {{ record.lastTransfer.from }} 转办
            </div>
            <div v-if="record.delegatedToMe" class="muted" style="font-size: 12px">
              由 {{ record.lastDelegation.owner }} 委托，你受托处理
            </div>
          </template>

          <template v-else-if="column.key === 'applicant'">
            <a-space :size="6">
              <a-avatar :size="22" style="background: #1677ff; font-size: 11px">
                {{ (record.applicant || '?').slice(0, 1).toUpperCase() }}
              </a-avatar>
              <span>{{ record.applicant || '-' }}</span>
            </a-space>
          </template>

          <template v-else-if="column.key === 'createTime'">
            <div>{{ fmt(record.createTime) }}</div>
            <div class="muted" style="font-size: 12px">{{ fromNow(record.createTime) }}</div>
          </template>

          <template v-else-if="column.key === 'status'">
            <a-tag v-if="record.isCommTask" :color="record.myCommStatus === 'PENDING' ? 'orange' : 'green'">
              {{ record.myCommStatus === 'PENDING' ? '待我反馈' : '已反馈' }}
            </a-tag>
            <a-tag v-else color="blue">待审批</a-tag>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-button type="link" size="small" @click="openTask(record)">
                {{ record.isCommTask ? '去反馈' : '去处理' }}
              </a-button>
              <a-button
                v-if="!record.isCommTask"
                type="link"
                size="small"
                @click="openTransfer(record)"
              >
                转办
              </a-button>
              <a-button
                v-if="record.canDelegate"
                type="link"
                size="small"
                @click="openDelegate(record)"
              >
                委托
              </a-button>
              <a-button type="link" size="small" @click="openTrace(record)">跟踪</a-button>
            </a-space>
          </template>
        </template>

        <template #emptyText>
          <a-empty description="暂无待办，喝口水吧">
            <a-button size="small" @click="createProcess">发起一条流程试试</a-button>
          </a-empty>
        </template>
      </a-table>
    </a-card>

    <TaskDrawer
      v-model:open="drawerOpen"
      :task-id="currentTaskId"
      @refresh="load"
    />

    <ProcessTraceModal v-model:open="traceOpen" :process-instance-id="tracePid" />

    <TransferModal
      v-model:open="transferOpen"
      :task-id="transferTask.taskId"
      :task-name="transferTask.taskName"
      @done="load"
    />

    <DelegateModal
      v-model:open="delegateOpen"
      :task-id="delegateTask.taskId"
      :task-name="delegateTask.taskName"
      @done="load"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'
import {
  AuditOutlined,
  ClockCircleOutlined,
  InboxOutlined,
  MessageOutlined,
  PlusOutlined,
  ReloadOutlined
} from '@ant-design/icons-vue'
import { commApi, errorText, wbApi } from '../api'
import TaskDrawer from '../components/TaskDrawer.vue'
import ProcessTraceModal from '../components/ProcessTraceModal.vue'
import TransferModal from '../components/TransferModal.vue'
import DelegateModal from '../components/DelegateModal.vue'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

const emit = defineEmits(['refresh-count'])

const loading = ref(false)
const creating = ref(false)
const rows = ref([])
const keyword = ref('')
const type = ref('all')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const stats = reactive({ total: 0, approval: 0, comm: 0, followup: 0, today: 0 })

const drawerOpen = ref(false)
const currentTaskId = ref('')

const traceOpen = ref(false)
const tracePid = ref('')

const transferOpen = ref(false)
const transferTask = reactive({ taskId: '', taskName: '' })

const delegateOpen = ref(false)
const delegateTask = reactive({ taskId: '', taskName: '' })

function openDelegate(record) {
  delegateTask.taskId = record.taskId
  delegateTask.taskName = record.taskName
  delegateOpen.value = true
}

const typeOptions = [
  { label: '全部', value: 'all' },
  { label: '审批待办', value: 'approval' },
  { label: '沟通待办', value: 'comm' },
  { label: '再发起沟通', value: 'followup' }
]

const columns = [
  { title: '流程', key: 'title', width: 240 },
  { title: '当前节点', key: 'node', width: 180 },
  { title: '发起人', key: 'applicant', width: 130 },
  { title: '到达时间', key: 'createTime', width: 150 },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'action', width: 190, fixed: 'right' }
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
  return value ? dayjs(value).format('MM-DD HH:mm') : '-'
}

function fromNow(value) {
  return value ? dayjs(value).fromNow() : ''
}

function shortId(id) {
  if (!id) return '-'
  return id.length > 18 ? `${id.slice(0, 10)}…${id.slice(-6)}` : id
}

async function load() {
  loading.value = true
  try {
    const res = await wbApi.todo({
      keyword: keyword.value,
      type: type.value,
      page: page.value,
      size: size.value
    })
    rows.value = res.list || []
    total.value = res.total || 0
    Object.assign(stats, res.stats || {})
    emit('refresh-count')
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

function openTask(record) {
  currentTaskId.value = record.taskId
  drawerOpen.value = true
}

function openTrace(record) {
  tracePid.value = record.processInstanceId
  traceOpen.value = true
}

function openTransfer(record) {
  transferTask.taskId = record.taskId
  transferTask.taskName = record.taskName
  transferOpen.value = true
}

async function createProcess() {
  creating.value = true
  try {
    const res = await commApi.startProcess()
    message.success(`流程已发起：${shortId(res.processInstanceId)}`)
    await load()
  } catch (e) {
    message.error(errorText(e))
  } finally {
    creating.value = false
  }
}

onMounted(load)
</script>
