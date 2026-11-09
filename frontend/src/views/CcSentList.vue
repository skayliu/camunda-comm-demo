<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的传阅</h2>
        <p class="page-desc">
          {{ dataMode === 'HANDLE'
            ? '我发出的阅办，按发起时间倒序；对方已提交结果或已撤回的不能再次撤回'
            : '我发起的传阅，按发送时间倒序；已读的不能撤回，撤回只对未读生效' }}
        </p>
      </div>
      <a-space>
        <a-segmented v-model:value="dataMode" :options="dataOptions" @change="changeDataMode" />
        <a-segmented v-if="dataMode === 'CC'" v-model:value="viewMode" :options="viewOptions" @change="changeView" />
        <a-button type="primary" :loading="loading" @click="load">刷新</a-button>
      </a-space>
    </div>

    <div v-if="dataMode === 'CC'" class="stat-row">
      <div class="stat-card">
        <span class="stat-label">发出</span>
        <span class="stat-value">{{ stats.total }}</span>
      </div>
      <div v-if="viewMode === 'PROCESS'" class="stat-card">
        <span class="stat-label">涉及流程</span>
        <span class="stat-value">{{ stats.processCount }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">未读</span>
        <span class="stat-value warn">{{ stats.unread }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">已读</span>
        <span class="stat-value ok">{{ stats.read }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">已撤回 / 失效</span>
        <span class="stat-value muted">{{ stats.revoked }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">已读率</span>
        <span class="stat-value">{{ stats.readRate }}%</span>
      </div>
    </div>

    <!-- 我发出的阅办 -->
    <div v-else class="stat-row">
      <div class="stat-card">
        <span class="stat-label">发出</span>
        <span class="stat-value">{{ handleStats.total }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">待办理</span>
        <span class="stat-value">{{ handleStats.pending }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">办理中</span>
        <span class="stat-value">{{ handleStats.handling }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">已逾期</span>
        <span class="stat-value warn">{{ handleStats.overdue }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">已完成</span>
        <span class="stat-value ok">{{ handleStats.done }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">已撤回 / 失效</span>
        <span class="stat-value muted">{{ handleStats.revoked }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">完成率</span>
        <span class="stat-value">{{ handleStats.doneRate }}%</span>
      </div>
    </div>

    <a-card :bordered="false" style="border-radius: 10px">
      <div class="toolbar">
        <a-input-search
          v-model:value="keyword"
          :placeholder="searchPlaceholder"
          allow-clear
          style="max-width: 320px"
          @search="search"
        />
        <a-segmented v-if="dataMode === 'CC'" v-model:value="status" :options="statusOptions" @change="search" />
        <a-segmented v-else v-model:value="handleStatus" :options="handleStatusOptions" @change="search" />
      </div>

      <!-- 逐条视图 -->
      <a-table
        v-if="dataMode === 'CC' && viewMode === 'LIST'"
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="id"
        :pagination="pagination"
        size="middle"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'title'">
            <div class="table-title">
              <span class="table-title-main">{{ record.title || '（无标题）' }}</span>
              <span class="table-title-sub">
                {{ record.processDefinitionKey || '-' }} · {{ shortId(record.processInstanceId) }}
              </span>
              <span v-if="record.note" class="cc-note">附言：{{ record.note }}</span>
            </div>
          </template>
          <template v-else-if="column.key === 'receiver'">
            <div>{{ record.ccUserName || record.ccUserId || '-' }}</div>
            <div v-if="record.ccUserName && record.ccUserName !== record.ccUserId" class="muted" style="font-size: 12px">
              {{ record.ccUserId }}
            </div>
          </template>
          <template v-else-if="column.key === 'type'">
            <a-tag color="purple">{{ record.ccTypeName }}</a-tag>
            <div v-if="record.taskDefinitionKey" class="muted" style="font-size: 12px">
              节点 {{ record.taskDefinitionKey }}
            </div>
          </template>
          <template v-else-if="column.key === 'createTime'">
            {{ fmt(record.createTime) }}
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag v-if="record.revoked" color="default">
              {{ record.revokeReason === 'INVALID' ? '已失效' : '已撤回' }}
            </a-tag>
            <a-tag v-else-if="record.read" color="green">已读</a-tag>
            <a-tag v-else color="orange">未读</a-tag>
            <div class="muted" style="font-size: 12px">
              <template v-if="record.revoked">{{ fmt(record.revokeTime) }} 撤回</template>
              <template v-else-if="record.read">读于 {{ fmt(record.readTime) }}</template>
              <template v-else>{{ record.processRunning ? '流程进行中' : '流程已结束' }}</template>
            </div>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-button type="link" size="small" @click="openTrace(record)">查看流程</a-button>
              <a-popconfirm
                v-if="record.canRevoke"
                title="撤回这条传阅？对方将不再看到它"
                ok-text="撤回"
                cancel-text="取消"
                @confirm="revoke(record)"
              >
                <a-button type="link" size="small" danger>撤回</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
        <template #emptyText>
          <a-empty description="还没有发出过传阅" />
        </template>
      </a-table>

      <!-- 按流程聚合视图 -->
      <a-table
        v-else-if="dataMode === 'CC'"
        :columns="groupColumns"
        :data-source="groups"
        :loading="loading"
        row-key="processInstanceId"
        :pagination="pagination"
        size="middle"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'process'">
            <div class="table-title">
              <span class="table-title-main">{{ record.title || '（无标题）' }}</span>
              <span class="table-title-sub">
                {{ record.processDefinitionKey || '-' }} · {{ shortId(record.processInstanceId) }}
              </span>
              <span v-if="record.note" class="cc-note">附言：{{ record.note }}</span>
            </div>
          </template>
          <template v-else-if="column.key === 'receivers'">
            <div class="receiver-tags">
              <a-tag
                v-for="member in record.members.slice(0, 6)"
                :key="member.id"
                :color="member.revoked ? 'default' : member.read ? 'green' : 'orange'"
              >
                {{ member.ccUserName || member.ccUserId }}
              </a-tag>
              <a-tag v-if="record.members.length > 6" color="default">+{{ record.members.length - 6 }}</a-tag>
            </div>
            <div v-if="record.unread" class="muted" style="font-size: 12px">
              还有 {{ record.unread }} 人未读
            </div>
          </template>
          <template v-else-if="column.key === 'progress'">
            <div class="progress-text">
              <span class="ok">{{ record.read }}</span> / {{ record.read + record.unread }}
              <span class="muted">（{{ record.readRate }}%）</span>
            </div>
            <a-progress
              :percent="record.readRate"
              size="small"
              :show-info="false"
              :stroke-color="record.readRate >= 100 ? '#52c41a' : '#fa8c16'"
            />
          </template>
          <template v-else-if="column.key === 'lastSendTime'">
            {{ fmt(record.lastSendTime) }}
          </template>
          <template v-else-if="column.key === 'processState'">
            <a-tag :color="record.processRunning ? 'processing' : 'default'">
              {{ record.processRunning ? '进行中' : '已结束' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-button type="link" size="small" @click="openTrace(record)">查看流程</a-button>
              <a-popconfirm
                v-if="record.revokeIds && record.revokeIds.length"
                :title="`撤回该流程下 ${record.revokeIds.length} 条未读传阅？`"
                ok-text="撤回"
                cancel-text="取消"
                @confirm="revokeGroup(record)"
              >
                <a-button type="link" size="small" danger>撤回未读</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>

        <!-- 展开：同一流程下每个人的送达明细 -->
        <template #expandedRowRender="{ record }">
          <div class="group-detail">
            <div class="group-detail-head">
              <span>传阅明细</span>
              <span class="muted">
                共 {{ record.total }} 条 · 已读 {{ record.read }} · 未读 {{ record.unread }}
                <template v-if="record.revoked"> · 已撤回/失效 {{ record.revoked }}</template>
              </span>
            </div>
            <a-table
              :columns="memberColumns"
              :data-source="record.members"
              row-key="id"
              size="small"
              :pagination="false"
            >
              <template #bodyCell="{ column, record: member }">
                <template v-if="column.key === 'member'">
                  <div>{{ member.ccUserName || member.ccUserId || '-' }}</div>
                  <div v-if="member.ccUserName && member.ccUserName !== member.ccUserId" class="muted" style="font-size: 12px">
                    {{ member.ccUserId }}
                  </div>
                </template>
                <template v-else-if="column.key === 'memberType'">
                  <a-tag color="purple">{{ member.ccTypeName }}</a-tag>
                  <span v-if="member.taskDefinitionKey" class="muted" style="font-size: 12px">
                    {{ member.taskDefinitionKey }}
                  </span>
                </template>
                <template v-else-if="column.key === 'memberTime'">
                  {{ fmt(member.createTime) }}
                </template>
                <template v-else-if="column.key === 'memberStatus'">
                  <a-tag v-if="member.revoked" color="default">
                    {{ member.revokeReason === 'INVALID' ? '已失效' : '已撤回' }}
                  </a-tag>
                  <a-tag v-else-if="member.read" color="green">已读</a-tag>
                  <a-tag v-else color="orange">未读</a-tag>
                  <span class="muted" style="font-size: 12px">
                    <template v-if="member.revoked"> {{ fmt(member.revokeTime) }}</template>
                    <template v-else-if="member.read"> {{ fmt(member.readTime) }}</template>
                  </span>
                </template>
                <template v-else-if="column.key === 'memberAction'">
                  <a-popconfirm
                    v-if="member.canRevoke"
                    title="撤回这条传阅？对方将不再看到它"
                    ok-text="撤回"
                    cancel-text="取消"
                    @confirm="revoke(member)"
                  >
                    <a-button type="link" size="small" danger>撤回</a-button>
                  </a-popconfirm>
                  <span v-else class="muted">—</span>
                </template>
              </template>
            </a-table>
          </div>
        </template>

        <template #emptyText>
          <a-empty description="还没有发出过传阅" />
        </template>
      </a-table>

      <!-- 我发出的阅办 -->
      <a-table
        v-else
        :columns="handleColumns"
        :data-source="handleRows"
        :loading="loading"
        row-key="id"
        :pagination="pagination"
        size="middle"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'item'">
            <div class="table-title">
              <span class="table-title-main">{{ record.title || '（无标题）' }}</span>
              <span class="table-title-sub">
                {{ record.processDefinitionKey || '-' }} · {{ shortId(record.processInstanceId) }}
              </span>
            </div>
            <div v-if="record.instruction" class="muted" style="font-size: 12px">
              要求：{{ record.instruction }}
            </div>
          </template>
          <template v-else-if="column.key === 'assignee'">
            <div>{{ record.assigneeName || record.assignee || '-' }}</div>
            <div
              v-if="record.assigneeName && record.assigneeName !== record.assignee"
              class="muted"
              style="font-size: 12px"
            >
              {{ record.assignee }}
            </div>
          </template>
          <template v-else-if="column.key === 'deadline'">
            <span :style="record.overdue ? { color: '#cf1322' } : null">
              {{ record.deadline ? fmt(record.deadline) : '不限期' }}
            </span>
          </template>
          <template v-else-if="column.key === 'handleState'">
            <a-tag v-if="record.revoked" color="default">
              {{ record.revokeReason === 'INVALID' ? '已失效' : '已撤回' }}
            </a-tag>
            <a-tag v-else :color="handleColor(record.handleStatus)">
              {{ handleText(record.handleStatus) }}
            </a-tag>
            <div class="muted" style="font-size: 12px">
              <template v-if="record.revoked">{{ fmt(record.revokeTime) }} 撤回</template>
              <template v-else-if="record.handleTime">完成于 {{ fmt(record.handleTime) }}</template>
              <template v-else>{{ record.processRunning ? '流程进行中' : '流程已结束' }}</template>
            </div>
          </template>
          <template v-else-if="column.key === 'handleResult'">
            <div v-if="record.handleResult">{{ record.handleResult }}</div>
            <span v-else class="muted">-</span>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-button type="link" size="small" @click="openTrace(record)">查看流程</a-button>
              <a-popconfirm
                v-if="record.canRevoke"
                title="撤回这条阅办？对方将不再需要办理"
                ok-text="撤回"
                cancel-text="取消"
                @confirm="revokeHandle(record)"
              >
                <a-button type="link" size="small" danger>撤回</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
        <template #emptyText>
          <a-empty description="还没有发出过阅办" />
        </template>
      </a-table>
    </a-card>

    <ProcessTraceModal v-if="traceMounted" v-model:open="traceOpen" :process-instance-id="tracePid" />
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, reactive, ref } from 'vue'
import message from 'ant-design-vue/es/message'
import dayjs from 'dayjs'
import { ccApi, errorText, handleApi } from '../api'

const ProcessTraceModal = defineAsyncComponent(() => import('../components/ProcessTraceModal.vue'))

const loading = ref(false)
const rows = ref([])
const groups = ref([])
const keyword = ref('')
const status = ref('ALL')
const viewMode = ref('LIST')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const traceOpen = ref(false)
const tracePid = ref('')
const traceMounted = ref(false)
const stats = reactive({ total: 0, unread: 0, read: 0, revoked: 0, readRate: 0, processCount: 0 })

// 同一页承载两种“我发出”的视角：传阅与阅办（承办）
const dataMode = ref('CC')
const handleRows = ref([])
const handleStatus = ref('ALL')
const handleStats = reactive({
  total: 0, pending: 0, handling: 0, overdue: 0, done: 0, revoked: 0, doneRate: 0
})

const dataOptions = [
  { label: '传阅', value: 'CC' },
  { label: '阅办', value: 'HANDLE' }
]
const viewOptions = [
  { label: '逐条', value: 'LIST' },
  { label: '按流程', value: 'PROCESS' }
]
const statusOptions = [
  { label: '全部', value: 'ALL' },
  { label: '未读', value: 'UNREAD' },
  { label: '已读', value: 'READ' },
  { label: '已撤回', value: 'REVOKED' }
]
const columns = [
  { title: '标题', key: 'title', width: 250 },
  { title: '待阅人', key: 'receiver', width: 130 },
  { title: '传阅类型', key: 'type', width: 140 },
  { title: '发送时间', key: 'createTime', width: 150 },
  { title: '状态', key: 'status', width: 140 },
  { title: '操作', key: 'action', width: 170, fixed: 'right' }
]
const groupColumns = [
  { title: '流程', key: 'process', width: 250 },
  { title: '待阅人', key: 'receivers', width: 260 },
  { title: '已读进度', key: 'progress', width: 160 },
  { title: '最近发送', key: 'lastSendTime', width: 150 },
  { title: '流程状态', key: 'processState', width: 110 },
  { title: '操作', key: 'action', width: 170, fixed: 'right' }
]
const memberColumns = [
  { title: '待阅人', key: 'member', width: 160 },
  { title: '传阅类型 / 节点', key: 'memberType', width: 190 },
  { title: '发送时间', key: 'memberTime', width: 150 },
  { title: '状态', key: 'memberStatus', width: 180 },
  { title: '操作', key: 'memberAction', width: 80 }
]
const handleStatusOptions = [
  { label: '全部', value: 'ALL' },
  { label: '待办理', value: 'PENDING' },
  { label: '办理中', value: 'HANDLING' },
  { label: '已逾期', value: 'OVERDUE' },
  { label: '已完成', value: 'DONE' },
  { label: '已撤回', value: 'REVOKED' }
]
const handleColumns = [
  { title: '事项', key: 'item', width: 250 },
  { title: '承办人', key: 'assignee', width: 130 },
  { title: '办理时限', key: 'deadline', width: 150 },
  { title: '状态', key: 'handleState', width: 150 },
  { title: '办理结果', key: 'handleResult', width: 220 },
  { title: '操作', key: 'action', width: 170, fixed: 'right' }
]
const searchPlaceholder = computed(() => {
  if (dataMode.value === 'HANDLE') {
    return '搜索事项、承办人、办理要求'
  }
  return viewMode.value === 'PROCESS'
    ? '搜索标题、流程 key、实例号、待阅人'
    : '搜索标题、待阅人、节点'
})
const pagination = computed(() => ({
  current: page.value,
  pageSize: size.value,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50'],
  showTotal: (t) => {
    if (dataMode.value === 'HANDLE') {
      return `共 ${t} 条`
    }
    return viewMode.value === 'PROCESS' ? `共 ${t} 个流程` : `共 ${t} 条`
  }
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
    if (dataMode.value === 'HANDLE') {
      await loadHandle()
      return
    }
    const params = {
      status: status.value,
      keyword: keyword.value,
      page: page.value,
      size: size.value
    }
    const res = viewMode.value === 'PROCESS'
      ? await ccApi.sentGrouped(params)
      : await ccApi.sent(params)
    if (viewMode.value === 'PROCESS') {
      groups.value = res.list || []
    } else {
      rows.value = res.list || []
    }
    total.value = res.total || 0
    const s = res.stats || {}
    stats.total = s.total || 0
    stats.unread = s.unread || 0
    stats.read = s.read || 0
    stats.revoked = s.revoked || 0
    stats.readRate = s.readRate || 0
    stats.processCount = s.processCount || 0
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}
async function loadHandle() {
  const res = await handleApi.sent({
    status: handleStatus.value,
    keyword: keyword.value,
    page: page.value,
    size: size.value
  })
  handleRows.value = res.list || []
  total.value = res.total || 0
  const s = res.stats || {}
  handleStats.total = s.total || 0
  handleStats.pending = s.pending || 0
  handleStats.handling = s.handling || 0
  handleStats.overdue = s.overdue || 0
  handleStats.done = s.done || 0
  handleStats.revoked = s.revoked || 0
  handleStats.doneRate = s.doneRate || 0
}
function search() {
  page.value = 1
  load()
}
function changeView() {
  page.value = 1
  load()
}
function changeDataMode() {
  page.value = 1
  load()
}
function handleText(status) {
  switch (status) {
    case 'PENDING':
      return '待办理'
    case 'HANDLING':
      return '办理中'
    case 'OVERDUE':
      return '已逾期'
    case 'DONE':
      return '已完成'
    default:
      return status || '-'
  }
}
function handleColor(status) {
  switch (status) {
    case 'OVERDUE':
      return 'red'
    case 'DONE':
      return 'green'
    case 'HANDLING':
      return 'processing'
    default:
      return 'orange'
  }
}
function onTableChange(pag) {
  page.value = pag.current
  size.value = pag.pageSize
  load()
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
async function revokeHandle(record) {
  try {
    await handleApi.revoke(record.id)
    message.success('已撤回')
    load()
  } catch (e) {
    message.error(errorText(e))
  }
}
async function revokeGroup(record) {
  try {
    const res = await ccApi.revokeByProcess(record.processInstanceId)
    message.success(`已撤回 ${res.revoked || 0} 条未读传阅`)
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

.stat-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  background: #fff;
  border-radius: 10px;
}

.stat-label {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
  color: rgba(0, 0, 0, 0.85);
}

.stat-value.warn {
  color: #fa8c16;
}

.stat-value.ok {
  color: #52c41a;
}

.stat-value.muted {
  color: rgba(0, 0, 0, 0.45);
}

.receiver-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.receiver-tags :deep(.ant-tag) {
  margin-inline-end: 0;
}

.progress-text {
  font-size: 13px;
  margin-bottom: 2px;
}

.group-detail {
  padding: 4px 8px 8px;
  background: #fafafa;
  border-radius: 8px;
}

.group-detail-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 4px 4px 8px;
  font-size: 13px;
  font-weight: 600;
}

@media (max-width: 768px) {
  .stat-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
