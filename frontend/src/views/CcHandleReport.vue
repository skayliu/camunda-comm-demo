<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">传阅与承办统计</h2>
        <p class="page-desc">
          看清「谁传阅给谁」与「谁逾期没办」；本页只读，不会改变传阅已读状态或承办进度
        </p>
      </div>
      <a-space wrap>
        <a-segmented v-model:value="days" :options="dayOptions" @change="load" />
        <a-button type="primary" :loading="loading" @click="load">刷新</a-button>
      </a-space>
    </div>

    <a-alert
      v-if="loaded && !view.summary.ccTotal && !view.summary.handleTotal"
      type="info"
      show-icon
      :message="`${view.range.label}内没有传阅或承办记录`"
      description="产生传阅、阅办后这里会自动出现统计；可切换到「全部」查看历史数据。"
      style="margin-bottom: 16px"
    />

    <div class="kpi-grid">
      <div class="stat-card">
        <div class="stat-icon" data-tone="brand">
          <MailOutlined />
        </div>
        <div>
          <div class="stat-value">{{ num(view.summary.ccActive) }}</div>
          <div class="stat-label">有效传阅 · 已读率 {{ pct(view.summary.ccReadRate) }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" data-tone="warning">
          <EyeOutlined />
        </div>
        <div>
          <div class="stat-value">{{ num(view.summary.ccUnread) }}</div>
          <div class="stat-label">未读传阅 · 已撤回/失效 {{ num(view.summary.ccRevoked) }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" data-tone="success">
          <SolutionOutlined />
        </div>
        <div>
          <div class="stat-value">{{ num(view.summary.handleTotal) }}</div>
          <div class="stat-label">有效阅办 · 已完成 {{ num(view.summary.handleDone) }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" data-tone="indigo">
          <CheckCircleOutlined />
        </div>
        <div>
          <div class="stat-value">{{ pct(view.summary.handleDoneRate) }}</div>
          <div class="stat-label">承办完成率 · 未完成 {{ num(view.summary.handleOpen) }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" data-tone="danger">
          <WarningOutlined />
        </div>
        <div>
          <div class="stat-value" :style="{ color: view.summary.handleOverdue ? 'var(--danger)' : null }">
            {{ num(view.summary.handleOverdue) }}
          </div>
          <div class="stat-label">逾期未办 · 占 {{ pct(view.summary.handleOverdueRate) }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" data-tone="purple">
          <FieldTimeOutlined />
        </div>
        <div>
          <div class="stat-value">{{ fmtHours(view.summary.handleAvgHours) }}</div>
          <div class="stat-label">平均办理时长（创建 → 反馈）</div>
        </div>
      </div>
    </div>

    <a-card :bordered="false" class="panel" title="收发趋势" :loading="loading">
      <template #extra>
        <a-space size="middle">
          <span class="legend"><i class="dot dot-cc" />传阅</span>
          <span class="legend"><i class="dot dot-done" />承办完成</span>
        </a-space>
      </template>
      <div v-if="view.trend.length" class="trend">
        <div class="trend-bars">
          <div v-for="point in view.trend" :key="point.date" class="trend-col">
            <div class="trend-bar-group">
              <div
                class="bar bar-cc"
                :style="{ height: barHeight(point.cc) }"
                :title="`${point.date} 传阅 ${point.cc} 条`"
              />
              <div
                class="bar bar-done"
                :style="{ height: barHeight(point.done) }"
                :title="`${point.date} 承办完成 ${point.done} 条`"
              />
            </div>
            <div class="trend-label">{{ shortDate(point.date) }}</div>
          </div>
        </div>
      </div>
      <a-empty v-else description="暂无趋势数据" />
    </a-card>

    <a-tabs v-model:activeKey="tab" class="tabs">
      <a-tab-pane key="cc" tab="传阅分析">
        <a-card :bordered="false" class="panel" title="谁传阅给谁" :loading="loading">
          <template #extra>
            <span class="muted">按传阅条数排序，最多 {{ top }} 组</span>
          </template>
          <a-table
            :columns="pairColumns"
            :data-source="view.cc.pairs"
            :pagination="pairPagination"
            row-key="pairKey"
            size="middle"
            :scroll="{ x: 760 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'pair'">
                <div class="pair">
                  <span class="pair-user">{{ record.senderName }}</span>
                  <span class="pair-arrow">→</span>
                  <span class="pair-user">{{ record.receiverName }}</span>
                </div>
                <div class="muted mono">{{ record.sender }} → {{ record.receiver }}</div>
              </template>
              <template v-else-if="column.key === 'readRate'">
                <a-progress
                  :percent="Math.min(record.readRate, 100)"
                  size="small"
                  :stroke-color="record.readRate >= 80 ? 'var(--success)' : record.readRate >= 50 ? 'var(--warning)' : 'var(--danger)'"
                />
              </template>
              <template v-else-if="column.key === 'unread'">
                <span :style="record.unread ? { color: 'var(--warning)', fontWeight: 600 } : null">{{ record.unread }}</span>
              </template>
            </template>
            <template #emptyText><a-empty description="暂无传阅记录" /></template>
          </a-table>
        </a-card>

        <a-row :gutter="[16, 16]">
          <a-col :xs="24" :lg="12">
            <a-card :bordered="false" class="panel" title="发送排行" :loading="loading">
              <template #extra><span class="muted">谁在发传阅</span></template>
              <a-table :columns="sendColumns" :data-source="view.cc.senders" :pagination="false" row-key="userId" size="small">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'user'">
                    <div>{{ record.userName }}</div>
                    <div class="muted mono">{{ record.userId }}</div>
                  </template>
                  <template v-else-if="column.key === 'readRate'">{{ pct(record.readRate) }}</template>
                </template>
                <template #emptyText><a-empty description="暂无数据" /></template>
              </a-table>
            </a-card>
          </a-col>
          <a-col :xs="24" :lg="12">
            <a-card :bordered="false" class="panel" title="接收排行" :loading="loading">
              <template #extra><span class="muted">谁收得多、积压多</span></template>
              <a-table :columns="receiveColumns" :data-source="view.cc.receivers" :pagination="false" row-key="userId" size="small">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'user'">
                    <div>{{ record.userName }}</div>
                    <div class="muted mono">{{ record.userId }}</div>
                  </template>
                  <template v-else-if="column.key === 'unread'">
                    <span :style="record.unread ? { color: 'var(--warning)', fontWeight: 600 } : null">{{ record.unread }}</span>
                  </template>
                </template>
                <template #emptyText><a-empty description="暂无数据" /></template>
              </a-table>
            </a-card>
          </a-col>
        </a-row>

        <a-card :bordered="false" class="panel" title="传阅类型分布" :loading="loading">
          <a-space wrap>
            <a-tag v-for="item in view.cc.types" :key="item.name" color="purple">
              {{ item.name }} {{ item.value }}
            </a-tag>
            <span v-if="!view.cc.types.length" class="muted">暂无数据</span>
          </a-space>
        </a-card>
      </a-tab-pane>

      <a-tab-pane key="handle" :tab="`承办分析${view.summary.handleOverdue ? `（逾期 ${view.summary.handleOverdue}）` : ''}`">
        <a-card :bordered="false" class="panel" title="谁逾期未办" :loading="loading">
          <template #extra>
            <span class="muted">按超期时长排序，最多 {{ overdueLimit }} 条</span>
          </template>
          <a-table
            :columns="overdueColumns"
            :data-source="view.handle.overdueItems"
            :pagination="overduePagination"
            row-key="id"
            size="middle"
            :scroll="{ x: 980 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'title'">
                <div class="table-title">
                  <span class="table-title-main">{{ record.title || '（无标题）' }}</span>
                  <span class="table-title-sub">{{ record.processDefinitionKey || '-' }} · {{ shortId(record.processInstanceId) }}</span>
                </div>
                <div v-if="record.instruction" class="muted" style="font-size: 12px">
                  要求：{{ record.instruction }}
                </div>
              </template>
              <template v-else-if="column.key === 'assignee'">
                <div>{{ record.assigneeName }}</div>
                <div class="muted mono">{{ record.assignee }}</div>
              </template>
              <template v-else-if="column.key === 'sender'">
                {{ record.senderName || '-' }}
              </template>
              <template v-else-if="column.key === 'deadline'">
                {{ fmt(record.deadline) }}
              </template>
              <template v-else-if="column.key === 'overdue'">
                <a-tag :color="record.overdueDays >= 3 ? 'red' : 'orange'">
                  超期 {{ record.overdueDays }} 天
                </a-tag>
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag :color="record.handleStatus === 'HANDLING' ? 'blue' : 'default'">
                  {{ record.statusText }}
                </a-tag>
              </template>
            </template>
            <template #emptyText><a-empty description="没有逾期未办的承办" /></template>
          </a-table>
        </a-card>

        <a-card :bordered="false" class="panel" title="承办人守时情况" :loading="loading">
          <template #extra><span class="muted">逾期、完成率与平均耗时的共同口径</span></template>
          <a-table
            :columns="assigneeColumns"
            :data-source="view.handle.assignees"
            :pagination="assigneePagination"
            row-key="assignee"
            size="middle"
            :scroll="{ x: 820 }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'user'">
                <div>{{ record.assigneeName }}</div>
                <div class="muted mono">{{ record.assignee }}</div>
              </template>
              <template v-else-if="column.key === 'overdue'">
                <span :style="record.overdue ? { color: 'var(--danger)', fontWeight: 600 } : null">{{ record.overdue }}</span>
              </template>
              <template v-else-if="column.key === 'doneRate'">
                {{ pct(record.doneRate) }}
              </template>
              <template v-else-if="column.key === 'overdueRate'">
                <span :style="record.overdueRate ? { color: 'var(--danger)' } : null">{{ pct(record.overdueRate) }}</span>
              </template>
              <template v-else-if="column.key === 'avgHours'">
                {{ fmtHours(record.avgHours) }}
              </template>
            </template>
            <template #emptyText><a-empty description="暂无承办记录" /></template>
          </a-table>
        </a-card>

        <a-card :bordered="false" class="panel" title="承办状态分布" :loading="loading">
          <a-space wrap>
            <a-tag
              v-for="item in view.handle.statuses"
              :key="item.name"
              :color="item.name === '逾期' ? 'red' : item.name === '已完成' ? 'green' : 'blue'"
            >
              {{ item.name }} {{ item.value }}
            </a-tag>
            <span v-if="!view.handle.statuses.length" class="muted">暂无数据</span>
          </a-space>
        </a-card>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import message from 'ant-design-vue/es/message'
import dayjs from 'dayjs'
import {
  CheckCircleOutlined,
  EyeOutlined,
  FieldTimeOutlined,
  MailOutlined,
  SolutionOutlined,
  WarningOutlined
} from '@ant-design/icons-vue'
import { errorText, reportApi } from '../api'

const loading = ref(false)
const loaded = ref(false)
const days = ref(30)
const top = 20
const overdueLimit = 50
const tab = ref('cc')

const dayOptions = [
  { label: '近 7 天', value: 7 },
  { label: '近 30 天', value: 30 },
  { label: '近 90 天', value: 90 },
  { label: '全部', value: 0 }
]

function emptyView() {
  return {
    range: { label: '近 30 天' },
    summary: {},
    cc: { pairs: [], senders: [], receivers: [], types: [] },
    handle: { assignees: [], overdueItems: [], statuses: [] },
    trend: []
  }
}
const view = ref(emptyView())

const pairColumns = [
  { title: '发送人 → 接收人', key: 'pair', width: 240 },
  { title: '条数', dataIndex: 'total', key: 'total', width: 80, align: 'right' },
  { title: '已读', dataIndex: 'read', key: 'read', width: 80, align: 'right' },
  { title: '未读', key: 'unread', width: 80, align: 'right' },
  { title: '撤回', dataIndex: 'revoked', key: 'revoked', width: 80, align: 'right' },
  { title: '已读率', key: 'readRate', width: 150 }
]
const sendColumns = [
  { title: '发送人', key: 'user', width: 160 },
  { title: '发出', dataIndex: 'total', key: 'total', width: 70, align: 'right' },
  { title: '已读', dataIndex: 'read', key: 'read', width: 70, align: 'right' },
  { title: '撤销', dataIndex: 'revoked', key: 'revoked', width: 70, align: 'right' },
  { title: '已读率', key: 'readRate', width: 90, align: 'right' }
]
const receiveColumns = [
  { title: '接收人', key: 'user', width: 160 },
  { title: '收到', dataIndex: 'total', key: 'total', width: 70, align: 'right' },
  { title: '未读', key: 'unread', width: 70, align: 'right' },
  { title: '已读率', dataIndex: 'readRate', key: 'readRate', width: 90, align: 'right' }
]
const overdueColumns = [
  { title: '事项', key: 'title', width: 280 },
  { title: '承办人', key: 'assignee', width: 140 },
  { title: '发起人', key: 'sender', width: 100 },
  { title: '办理时限', key: 'deadline', width: 150 },
  { title: '超期', key: 'overdue', width: 110 },
  { title: '状态', key: 'status', width: 90, fixed: 'right' }
]
const assigneeColumns = [
  { title: '承办人', key: 'user', width: 160 },
  { title: '总计', dataIndex: 'total', key: 'total', width: 70, align: 'right' },
  { title: '完成', dataIndex: 'done', key: 'done', width: 70, align: 'right' },
  { title: '进行中', dataIndex: 'open', key: 'open', width: 80, align: 'right' },
  { title: '逾期', key: 'overdue', width: 70, align: 'right' },
  { title: '完成率', key: 'doneRate', width: 90, align: 'right' },
  { title: '逾期率', key: 'overdueRate', width: 90, align: 'right' },
  { title: '平均耗时', key: 'avgHours', width: 110, align: 'right' }
]

const simplePagination = (source) => ({
  pageSize: 10,
  showSizeChanger: false,
  showTotal: (total) => `共 ${total} 条`,
  hideOnSinglePage: source.length <= 10
})
const pairPagination = computed(() => simplePagination(view.value.cc.pairs))
const overduePagination = computed(() => simplePagination(view.value.handle.overdueItems))
const assigneePagination = computed(() => simplePagination(view.value.handle.assignees))

function num(value) {
  return value == null ? 0 : value
}
function pct(value) {
  if (value == null) return '0%'
  return `${Number(value).toFixed(1).replace(/\.0$/, '')}%`
}
function fmtHours(value) {
  if (value == null) return '-'
  if (value >= 24) return `${(value / 24).toFixed(1)} 天`
  return `${Number(value).toFixed(1).replace(/\.0$/, '')} 小时`
}
function fmt(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'
}
function shortDate(value) {
  return value ? value.slice(5) : ''
}
function shortId(id) {
  return id && id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-6)}` : id || '-'
}

const maxTrend = computed(() => {
  let max = 0
  for (const point of view.value.trend) {
    max = Math.max(max, point.cc || 0, point.done || 0)
  }
  return max || 1
})
function barHeight(value) {
  const ratio = (value || 0) / maxTrend.value
  // 0 值也留 2px，避免一整天完全空白看起来像渲染失败
  return `${Math.max(ratio * 100, value ? 3 : 1.5)}%`
}

async function load() {
  loading.value = true
  try {
    const data = await reportApi.ccHandle({ days: days.value, top })
    view.value = data || emptyView()
    loaded.value = true
    // row-key 需要每行唯一：同一组收发人只出现一次，这里补一个稳定键
    view.value.cc.pairs = (view.value.cc.pairs || []).map((row) => ({
      ...row,
      pairKey: `${row.sender}__${row.receiver}`
    }))
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.panel {
  border-radius: 10px;
  margin-bottom: 16px;
}

.tabs :deep(.ant-tabs-nav) {
  margin-bottom: 16px;
}

.trend {
  overflow-x: auto;
  padding-bottom: 4px;
}

.trend-bars {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  min-height: 140px;
  padding: 8px 2px 0;
  border-bottom: 1px solid var(--border-subtle);
}

.trend-col {
  flex: 0 0 26px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  height: 140px;
}

.trend-bar-group {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 100%;
  width: 100%;
  justify-content: center;
}

.bar {
  width: 9px;
  border-radius: 3px 3px 0 0;
  transition: height 0.25s ease;
}

.bar-cc {
  background: var(--brand);
}

.bar-done {
  background: var(--success);
}

.trend-label {
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-secondary);
  white-space: nowrap;
  transform: scale(0.92);
}

.legend {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--text-secondary);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
}

.dot-cc {
  background: var(--brand);
}

.dot-done {
  background: var(--success);
}

.pair {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}

.pair-arrow {
  color: var(--text-secondary);
}

@media (max-width: 768px) {
  .trend-col {
    flex-basis: 22px;
  }
}
</style>
