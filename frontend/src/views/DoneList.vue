<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的已办</h2>
        <p class="page-desc">我已处理完成的任务记录，含审批意见与耗时</p>
      </div>
      <a-button type="primary" :loading="loading" @click="load">
        <template #icon><ReloadOutlined /></template>
        刷新
      </a-button>
    </div>

    <a-card :bordered="false">
      <div class="toolbar">
        <a-input-search
          v-model:value="keyword"
          placeholder="搜索流程、发起人、实例ID"
          allow-clear
          style="max-width: 320px"
          @search="search"
        />
        <span class="muted" style="font-size: 13px">共 {{ total }} 条处理记录</span>
      </div>

      <a-table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="taskId"
        :pagination="pagination"
        :scroll="{ x: 880 }"
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
              <a-tag v-if="record.isCommTask" :color="record.isFollowUp ? 'gold' : 'purple'">
                {{ record.isFollowUp ? '沟通' : '首轮沟通' }}
              </a-tag>
              <a-tag v-if="record.transferCount" color="cyan">
                已转办 {{ record.transferCount }} 次
              </a-tag>
            </a-space>
          </template>

          <template v-else-if="column.key === 'result'">
            <a-tag v-if="record.deleteReason" color="default">已收回/删除</a-tag>
            <a-tag v-else color="green">已完成</a-tag>
          </template>

          <template v-else-if="column.key === 'endTime'">
            <div>{{ fmt(record.endTime) }}</div>
            <div class="muted" style="font-size: 12px">
              耗时 {{ duration(record.durationInMillis) }}
            </div>
          </template>

          <template v-else-if="column.key === 'comment'">
            <span :class="{ muted: !record.comment }">
              {{ record.comment || '—' }}
            </span>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
              <a-button type="link" size="small" @click="openTrace(record)">跟踪</a-button>
            </a-space>
          </template>
        </template>

        <template #emptyText>
          <a-empty description="还没有已办记录" />
        </template>
      </a-table>
    </a-card>

    <DetailDrawer v-if="detailMounted" v-model:open="detailOpen" title="已办详情" :width="560">
      <template v-if="current">
        <a-descriptions :column="1" bordered size="small">
          <a-descriptions-item label="流程">
            {{ current.processTitle }}
          </a-descriptions-item>
          <a-descriptions-item label="处理节点">
            {{ current.taskName }}
            <a-tag
              v-if="current.isCommTask"
              :color="current.isFollowUp ? 'gold' : 'purple'"
              style="margin-left: 6px"
            >
              {{ current.isFollowUp ? '沟通' : '首轮沟通' }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="发起人">
            {{ current.applicant || '-' }}
          </a-descriptions-item>
          <a-descriptions-item label="开始时间">{{ fmt(current.startTime) }}</a-descriptions-item>
          <a-descriptions-item label="完成时间">{{ fmt(current.endTime) }}</a-descriptions-item>
          <a-descriptions-item label="耗时">
            {{ duration(current.durationInMillis) }}
          </a-descriptions-item>
          <a-descriptions-item label="处理结果">
            {{ current.deleteReason ? `已删除（${current.deleteReason}）` : '已完成' }}
          </a-descriptions-item>
          <a-descriptions-item label="流程实例">
            <span class="mono">{{ current.processInstanceId }}</span>
          </a-descriptions-item>
        </a-descriptions>

        <!-- 转办记录：已办里最后一条接收人是历史办理人，不能标成「当前办理人」 -->
        <DetailSection
          v-if="transfers.length"
          title="转办记录"
          :extra="`共 ${transfers.length} 次`"
          style="margin-top: 20px"
        >
          <TransferChain :transfers="transfers" />
        </DetailSection>

        <DetailSection title="审批意见" :style="{ marginTop: transfers.length ? '' : '20px' }">
          <div v-if="current.comments && current.comments.length" class="comm-bubble">
            <div v-for="c in current.comments" :key="c.id" style="margin-bottom: 8px">
              <div style="color: var(--text-tertiary); font-size: 12px">
                {{ c.userId }} · {{ fmt(c.time) }}
              </div>
              {{ c.message }}
            </div>
          </div>
          <div v-else class="empty-tip">未填写审批意见</div>
        </DetailSection>
      </template>

      <template #footer>
        <a-button @click="detailOpen = false">关闭</a-button>
      </template>
    </DetailDrawer>

    <ProcessTraceModal v-if="traceMounted" v-model:open="traceOpen" :process-instance-id="tracePid" />
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, ref } from 'vue'
import message from 'ant-design-vue/es/message'
import dayjs from 'dayjs'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { errorText, wbApi } from '../api'
// 详情抽屉与跟踪弹窗只在点开时才需要，改为按需加载，不占首屏
const ProcessTraceModal = defineAsyncComponent(() => import('../components/ProcessTraceModal.vue'))
const DetailDrawer = defineAsyncComponent(() => import('../components/DetailDrawer.vue'))
import DetailSection from '../components/DetailSection.vue'
import TransferChain from '../components/TransferChain.vue'

const loading = ref(false)
const rows = ref([])
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const detailOpen = ref(false)
const detailMounted = ref(false)
const current = ref(null)
const transfers = ref([])
const traceOpen = ref(false)
const tracePid = ref('')
const traceMounted = ref(false)

const columns = [
  { title: '流程', key: 'title', width: 240 },
  { title: '处理节点', key: 'node', width: 170 },
  { title: '发起人', dataIndex: 'applicant', key: 'applicant', width: 110 },
  { title: '处理时间', key: 'endTime', width: 150 },
  { title: '结果', key: 'result', width: 110 },
  { title: '审批意见', key: 'comment', width: 200, ellipsis: true },
  { title: '操作', key: 'action', width: 130, fixed: 'right' }
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

function duration(ms) {
  if (ms === null || ms === undefined) return '-'
  const minutes = Math.round(ms / 60000)
  if (minutes < 1) return '不足 1 分钟'
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时 ${minutes % 60} 分`
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`
}

function shortId(id) {
  if (!id) return '-'
  return id.length > 18 ? `${id.slice(0, 10)}…${id.slice(-6)}` : id
}

async function load() {
  loading.value = true
  try {
    const res = await wbApi.done({
      keyword: keyword.value,
      page: page.value,
      size: size.value
    })
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

function openDetail(record) {
  current.value = record
  transfers.value = []
  detailMounted.value = true
  detailOpen.value = true
  loadTransfers(record.taskId)
}

// 转办链是附加信息：接口失败（例如历史任务已无权限）只留空，不打断详情展示
async function loadTransfers(taskId) {
  try {
    transfers.value = (await wbApi.transfers(taskId)) || []
  } catch (e) {
    transfers.value = []
  }
}

function openTrace(record) {
  tracePid.value = record.processInstanceId
  traceMounted.value = true
  traceOpen.value = true
}

onMounted(load)
</script>
