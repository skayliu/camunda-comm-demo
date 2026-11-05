<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">我的发起</h2>
        <p class="page-desc">我发起的所有流程，以及当前停在哪一步</p>
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

    <a-card :bordered="false" style="border-radius: 10px">
      <div class="toolbar">
        <a-input-search
          v-model:value="keyword"
          placeholder="搜索流程、实例ID"
          allow-clear
          style="max-width: 320px"
          @search="search"
        />
        <span class="muted" style="font-size: 13px">共 {{ total }} 条</span>
      </div>

      <a-table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="processInstanceId"
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

          <template v-else-if="column.key === 'state'">
            <a-tag :color="record.state === 'RUNNING' ? 'processing' : 'green'">
              {{ record.state === 'RUNNING' ? '进行中' : '已完成' }}
            </a-tag>
          </template>

          <template v-else-if="column.key === 'current'">
            <template v-if="record.currentTasks && record.currentTasks.length">
              <div
                v-for="t in record.currentTasks"
                :key="t.taskId"
                style="line-height: 1.9"
              >
                <a-space :size="6">
                  <span>{{ t.taskName }}</span>
                  <a-tag v-if="t.isCommTask" :color="t.isFollowUp ? 'gold' : 'purple'">
                    {{ t.isFollowUp ? '追问' : '首轮沟通' }}
                  </a-tag>
                  <span class="muted" style="font-size: 12px">{{ t.assignee }}</span>
                </a-space>
              </div>
            </template>
            <span v-else class="muted">—</span>
          </template>

          <template v-else-if="column.key === 'startTime'">
            <div>{{ fmt(record.startTime) }}</div>
            <div class="muted" style="font-size: 12px">{{ fromNow(record.startTime) }}</div>
          </template>

          <template v-else-if="column.key === 'endTime'">
            {{ record.endTime ? fmt(record.endTime) : '—' }}
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
              <a-button type="link" size="small" @click="openTrace(record)">跟踪</a-button>
            </a-space>
          </template>
        </template>

        <template #emptyText>
          <a-empty description="还没有发起过流程">
            <a-button size="small" @click="createProcess">发起一条</a-button>
          </a-empty>
        </template>
      </a-table>
    </a-card>

    <DetailDrawer v-model:open="detailOpen" title="发起详情" :width="560">
      <template v-if="current">
        <a-descriptions :column="1" bordered size="small">
          <a-descriptions-item label="流程">{{ current.processTitle }}</a-descriptions-item>
          <a-descriptions-item label="状态">
            <a-tag :color="current.state === 'RUNNING' ? 'processing' : 'green'">
              {{ current.state === 'RUNNING' ? '进行中' : '已完成' }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="发起人">{{ current.applicant || '-' }}</a-descriptions-item>
          <a-descriptions-item label="发起时间">{{ fmt(current.startTime) }}</a-descriptions-item>
          <a-descriptions-item label="结束时间">
            {{ current.endTime ? fmt(current.endTime) : '—' }}
          </a-descriptions-item>
          <a-descriptions-item label="流程定义">
            {{ current.processDefinitionName }}
            <span class="mono muted">（{{ current.processDefinitionKey }}）</span>
          </a-descriptions-item>
          <a-descriptions-item label="流程实例">
            <span class="mono">{{ current.processInstanceId }}</span>
          </a-descriptions-item>
        </a-descriptions>

        <!-- 一个流程实例可能包含多条待办、多次转办，这里按实例汇总展示 -->
        <DetailSection
          v-if="transfers.length"
          title="转办记录"
          :extra="`共 ${transfers.length} 次`"
          style="margin-top: 20px"
        >
          <TransferChain :transfers="transfers" />
        </DetailSection>

        <DetailSection title="当前节点" :style="{ marginTop: transfers.length ? '' : '20px' }">
          <div v-if="current.currentTasks && current.currentTasks.length">
            <div
              v-for="t in current.currentTasks"
              :key="t.taskId"
              class="comm-target"
              style="margin-bottom: 8px"
            >
              <div class="comm-target-head" style="margin-bottom: 0">
                <div class="comm-target-name">
                  <span>{{ t.taskName }}</span>
                  <a-tag v-if="t.isCommTask" :color="t.isFollowUp ? 'gold' : 'purple'">
                    {{ t.isFollowUp ? '追问' : '首轮沟通' }}
                  </a-tag>
                </div>
                <span class="muted" style="font-size: 12px">处理人 {{ t.assignee }}</span>
              </div>
            </div>
          </div>
          <div v-else class="empty-tip">流程已结束，无待处理节点</div>
        </DetailSection>
      </template>

      <template #footer>
        <a-button @click="detailOpen = false">关闭</a-button>
      </template>
    </DetailDrawer>

    <ProcessTraceModal v-model:open="traceOpen" :process-instance-id="tracePid" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { commApi, errorText, wbApi } from '../api'
import ProcessTraceModal from '../components/ProcessTraceModal.vue'
import DetailDrawer from '../components/DetailDrawer.vue'
import DetailSection from '../components/DetailSection.vue'
import TransferChain from '../components/TransferChain.vue'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

const loading = ref(false)
const creating = ref(false)
const rows = ref([])
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const detailOpen = ref(false)
const current = ref(null)
const transfers = ref([])
const traceOpen = ref(false)
const tracePid = ref('')

const columns = [
  { title: '流程', key: 'title', width: 240 },
  { title: '状态', key: 'state', width: 100 },
  { title: '当前节点', key: 'current', width: 200 },
  { title: '发起时间', key: 'startTime', width: 150 },
  { title: '结束时间', key: 'endTime', width: 140 },
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
    const res = await wbApi.started({
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
  detailOpen.value = true
  loadTransfers(record.processInstanceId)
}

// 转办链是附加信息：接口失败只留空，不打断详情展示
async function loadTransfers(processInstanceId) {
  try {
    transfers.value = (await wbApi.processTransfers(processInstanceId)) || []
  } catch (e) {
    transfers.value = []
  }
}

function openTrace(record) {
  tracePid.value = record.processInstanceId
  traceOpen.value = true
}

async function createProcess() {
  creating.value = true
  try {
    const res = await commApi.startProcess()
    message.success(`流程已发起：${shortId(res.processInstanceId)}`)
    page.value = 1
    await load()
  } catch (e) {
    message.error(errorText(e))
  } finally {
    creating.value = false
  }
}

onMounted(load)
</script>
