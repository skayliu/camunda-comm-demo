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

    <a-card :bordered="false" style="border-radius: 10px">
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
              <a-tag v-if="record.isCommTask" :color="record.isFollowUp ? 'gold' : 'purple'">
                {{ record.isFollowUp ? '追问' : '首轮沟通' }}
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
            <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
          </template>
        </template>

        <template #emptyText>
          <a-empty description="还没有已办记录" />
        </template>
      </a-table>
    </a-card>

    <a-drawer
      v-model:open="detailOpen"
      :width="isMobile ? '100%' : 560"
      title="已办详情"
    >
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
              {{ current.isFollowUp ? '追问' : '首轮沟通' }}
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

        <div class="drawer-section" style="margin-top: 20px">
          <div class="drawer-section-title">审批意见</div>
          <div v-if="current.comments && current.comments.length" class="comm-bubble">
            <div v-for="c in current.comments" :key="c.id" style="margin-bottom: 8px">
              <div style="color: rgba(0, 0, 0, 0.45); font-size: 12px">
                {{ c.userId }} · {{ fmt(c.time) }}
              </div>
              {{ c.message }}
            </div>
          </div>
          <div v-else class="empty-tip">未填写审批意见</div>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { errorText, wbApi } from '../api'

const loading = ref(false)
const rows = ref([])
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const detailOpen = ref(false)
const current = ref(null)
const isMobile = ref(window.innerWidth <= 768)

const columns = [
  { title: '流程', key: 'title', width: 240 },
  { title: '处理节点', key: 'node', width: 170 },
  { title: '发起人', dataIndex: 'applicant', key: 'applicant', width: 110 },
  { title: '处理时间', key: 'endTime', width: 150 },
  { title: '结果', key: 'result', width: 110 },
  { title: '审批意见', key: 'comment', width: 200, ellipsis: true },
  { title: '操作', key: 'action', width: 80, fixed: 'right' }
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
  detailOpen.value = true
}

onMounted(load)
</script>
