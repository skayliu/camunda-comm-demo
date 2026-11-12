<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">代理设置</h2>
        <p class="page-desc">在指定时间段内，把你的新待办自动委托给代理人；到期后只影响新任务</p>
      </div>
      <a-space>
        <a-button v-if="isAdmin" :type="showAll ? 'default' : 'dashed'" @click="toggleAll">
          {{ showAll ? '只看我的' : '管理员：全部规则' }}
        </a-button>
        <a-button type="primary" @click="openCreate">
          <template #icon><PlusOutlined /></template>
          新建规则
        </a-button>
        <a-button :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </a-space>
    </div>

    <a-alert
      type="info"
      show-icon
      style="margin-bottom: 14px"
      message="代理只对规则生效期内新创建的任务自动委托"
      description="规则到期后不会把已经代理出去的任务批量拉回，避免代理人处理到一半的任务突然消失。同一时间段只能有一条生效规则。"
    />

    <a-card :bordered="false">
      <a-table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="id"
        :pagination="{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }"
        size="middle"
        :scroll="{ x: 860 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'owner'">
            <a-space :size="6">
              <a-avatar :size="22" style="background: #722ed1; font-size: 11px">
                {{ (record.owner || '?').slice(0, 1).toUpperCase() }}
              </a-avatar>
              <span>{{ record.owner }}</span>
            </a-space>
          </template>
          <template v-else-if="column.key === 'agent'">
            <a-tag color="purple">{{ record.agentUser }}</a-tag>
          </template>
          <template v-else-if="column.key === 'period'">
            <div>{{ fmt(record.startTime) }}</div>
            <div class="muted" style="font-size: 12px">至 {{ fmt(record.endTime) }}</div>
          </template>
          <template v-else-if="column.key === 'scope'">
            <span v-if="!record.processDefinitionKeys" class="muted">全部流程</span>
            <a-space v-else :size="4" wrap>
              <a-tag v-for="k in record.processDefinitionKeys.split(',')" :key="k">{{ k }}</a-tag>
            </a-space>
          </template>
          <template v-else-if="column.key === 'mode'">
            <a-tag :color="record.delegateMode === 'SIGN' ? 'geekblue' : 'default'">
              {{ record.delegateMode === 'SIGN' ? '可直接办结' : '需我确认' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'state'">
            <a-tag :color="stateColor(record.state)">{{ stateText(record.state) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
              <a-button
                v-if="record.enabled === 1 && canOperate(record)"
                type="link"
                size="small"
                danger
                @click="toggleRule(record, false)"
              >停用</a-button>
              <a-button
                v-if="record.enabled !== 1 && canOperate(record)"
                type="link"
                size="small"
                @click="toggleRule(record, true)"
              >启用</a-button>
            </a-space>
          </template>
        </template>
        <template #emptyText>
          <a-empty description="还没有代理规则">
            <a-button size="small" @click="openCreate">新建一条</a-button>
          </a-empty>
        </template>
      </a-table>
    </a-card>

    <!-- 详情 -->
    <DetailDrawer v-if="detailMounted" v-model:open="detailOpen" title="代理规则详情" :width="560">
      <template v-if="current">
        <a-descriptions :column="1" bordered size="small">
          <a-descriptions-item label="规则 ID"><span class="mono">{{ current.id }}</span></a-descriptions-item>
          <a-descriptions-item label="委托人">{{ current.owner }}</a-descriptions-item>
          <a-descriptions-item label="代理人"><a-tag color="purple">{{ current.agentUser }}</a-tag></a-descriptions-item>
          <a-descriptions-item label="状态">
            <a-tag :color="stateColor(current.state)">{{ stateText(current.state) }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="办理方式">
            {{ current.delegateMode === 'SIGN' ? '代理人可直接办结' : '需委托人确认（代理人只能提交反馈）' }}
          </a-descriptions-item>
          <a-descriptions-item label="生效时间">{{ fmt(current.startTime) }}</a-descriptions-item>
          <a-descriptions-item label="结束时间">{{ fmt(current.endTime) }}</a-descriptions-item>
          <a-descriptions-item label="流程范围">
            <span v-if="!current.processDefinitionKeys" class="muted">全部流程</span>
            <span v-else>{{ current.processDefinitionKeys.split(',').join('、') }}</span>
          </a-descriptions-item>
          <a-descriptions-item label="创建时间">{{ fmt(current.createTime) }}</a-descriptions-item>
          <a-descriptions-item label="更新时间">{{ fmt(current.updateTime) }}</a-descriptions-item>
        </a-descriptions>
        <a-alert
          style="margin-top: 16px"
          type="warning"
          show-icon
          v-if="current.enabled !== 1"
          message="规则已停用"
          description="停用只影响后续新任务，已经代理出去的任务仍由代理人处理。"
        />
        <a-alert
          style="margin-top: 16px"
          type="success"
          show-icon
          v-else-if="current.state === 'ACTIVE'"
          message="代理生效中"
          description="当前时间段内新产生的待办会自动委托给代理人。"
        />
      </template>
      <template #footer>
        <a-button @click="detailOpen = false">关闭</a-button>
        <a-button v-if="current && current.owner === me" @click="editCurrent">编辑</a-button>
        <a-popconfirm
          v-if="current && current.enabled === 1 && canOperate(current)"
          title="停用后新任务不再代理，确定停用？"
          ok-text="停用"
          cancel-text="取消"
          @confirm="toggleRule(current, false)"
        >
          <a-button danger>停用</a-button>
        </a-popconfirm>
        <a-button
          v-if="current && current.enabled !== 1 && canOperate(current)"
          type="primary"
          @click="toggleRule(current, true)"
        >启用</a-button>
      </template>
    </DetailDrawer>

    <!-- 新建 / 编辑 -->
    <a-modal
      v-model:open="formOpen"
      :title="form.id ? '编辑代理规则' : '新建代理规则'"
      :confirm-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      @ok="submit"
    >
      <a-form layout="vertical" style="margin-top: 8px">
        <a-alert
          type="info"
          show-icon
          style="margin-bottom: 12px"
          message="同一时间段只能有一条生效规则"
          description="与已有规则时间重叠会被拒绝；停用的规则不参与重叠判断。"
        />
        <a-form-item label="代理人" required>
          <a-select
            v-model:value="form.agentUserId"
            show-search
            placeholder="选择代理人"
            :options="userOptions"
            :filter-option="filterOption"
            :loading="loadingUsers"
          />
        </a-form-item>
        <a-form-item label="办理方式">
          <a-radio-group v-model:value="form.delegateMode">
            <a-radio value="CONFIRM">需我确认</a-radio>
            <a-radio value="SIGN">可直接办结</a-radio>
          </a-radio-group>
          <div class="muted" style="font-size: 12px; margin-top: 4px">
            {{ form.delegateMode === 'SIGN'
              ? '代理人在自己名下审批，办结后流程直接往下走，你无需回来确认；适合出差、休假期间的日常审批。'
              : '任务仍记在你名下，代理人只能提交反馈并交回，你确认后才办结；适合合同、付款等敏感审批。' }}
          </div>
        </a-form-item>
        <a-form-item label="生效时间" required>
          <a-date-picker
            v-model:value="form.startTime"
            show-time
            format="YYYY-MM-DD HH:mm"
            style="width: 100%"
            placeholder="选择开始时间"
          />
        </a-form-item>
        <a-form-item label="结束时间" required>
          <a-date-picker
            v-model:value="form.endTime"
            show-time
            format="YYYY-MM-DD HH:mm"
            style="width: 100%"
            placeholder="选择结束时间"
          />
        </a-form-item>
        <a-form-item label="流程范围">
          <a-input
            v-model:value="form.processDefinitionKeys"
            placeholder="留空表示全部流程；多个用英文逗号分隔，如 approval,leave"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 停用确认：可选“同时收回该规则下委托中的任务” -->
    <a-modal
      v-model:open="disableOpen"
      title="停用代理规则"
      :confirm-loading="disabling"
      ok-text="确认停用"
      cancel-text="取消"
      @ok="confirmDisable"
    >
      <p style="margin-bottom: 12px">
        停用后，这条规则不再对新产生的待办自动委托。
      </p>
      <template v-if="pendingOf > 0">
        <a-alert
          type="warning"
          show-icon
          style="margin-bottom: 12px"
          :message="`该规则当前有 ${pendingOf} 条任务处于委托中`"
          description="不勾选则任务仍留在受托人名下，需要到「委托记录」里逐条收回。"
        />
        <a-checkbox v-model:checked="cancelActive">
          同时收回这 {{ pendingOf }} 条委托中的任务
        </a-checkbox>
      </template>
      <p v-else class="muted" style="margin: 0">
        当前没有委托中的任务，停用只影响后续新任务。
      </p>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, reactive, ref } from 'vue'
import message from 'ant-design-vue/es/message'
import dayjs from 'dayjs'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { agentApi, authApi, commApi, errorText, getUser } from '../api'
// 详情抽屉只在点开时下载
const DetailDrawer = defineAsyncComponent(() => import('../components/DetailDrawer.vue'))

const emit = defineEmits(['refresh-count'])

const me = ref(getUser())
const isAdmin = ref(false)
const showAll = ref(false)
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const detailOpen = ref(false)
const detailMounted = ref(false)
const current = ref(null)
const formOpen = ref(false)
const loadingUsers = ref(false)
const userOptions = ref([])
const form = reactive({
  id: null,
  agentUserId: undefined,
  delegateMode: 'CONFIRM',
  startTime: null,
  endTime: null,
  processDefinitionKeys: ''
})

const columns = [
  { title: '委托人', key: 'owner', width: 130 },
  { title: '代理人', key: 'agent', width: 120 },
  { title: '办理方式', key: 'mode', width: 110 },
  { title: '生效时间段', key: 'period', width: 190 },
  { title: '流程范围', key: 'scope', width: 200 },
  { title: '状态', key: 'state', width: 100 },
  { title: '操作', key: 'action', width: 140, fixed: 'right' }
]

const stateText = (s) => ({ ACTIVE: '生效中', UPCOMING: '未开始', EXPIRED: '已过期', DISABLED: '已停用' }[s] || s)
const stateColor = (s) => ({ ACTIVE: 'green', UPCOMING: 'blue', EXPIRED: 'default', DISABLED: 'red' }[s] || 'default')

function fmt(v) {
  return v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'
}

function filterOption(input, option) {
  return (option.label || '').toLowerCase().includes(input.toLowerCase())
}

// 本人可操作；管理员只能停用他人规则（后端同口径）
function canOperate(record) {
  return record.owner === me.value || isAdmin.value
}

async function load() {
  loading.value = true
  try {
    rows.value = (showAll.value ? await agentApi.allRules() : await agentApi.rules()) || []
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}

function toggleAll() {
  showAll.value = !showAll.value
  load()
}

async function loadUsers() {
  if (userOptions.value.length) return
  loadingUsers.value = true
  try {
    const list = await commApi.targets('')
    userOptions.value = list.map((u) => ({ value: u.id, label: `${u.name}（${u.id}）` }))
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loadingUsers.value = false
  }
}

function resetForm() {
  form.id = null
  form.agentUserId = undefined
  form.delegateMode = 'CONFIRM'
  form.startTime = null
  form.endTime = null
  form.processDefinitionKeys = ''
}

function openCreate() {
  resetForm()
  const start = dayjs().add(1, 'minute')
  form.startTime = start
  form.endTime = start.add(8, 'hour')
  formOpen.value = true
  loadUsers()
}

function openDetail(record) {
  current.value = record
  detailMounted.value = true
  detailOpen.value = true
}

function editCurrent() {
  const r = current.value
  detailOpen.value = false
  form.id = r.id
  form.agentUserId = r.agentUser
  form.delegateMode = r.delegateMode || 'CONFIRM'
  form.startTime = r.startTime ? dayjs(r.startTime) : null
  form.endTime = r.endTime ? dayjs(r.endTime) : null
  form.processDefinitionKeys = r.processDefinitionKeys || ''
  formOpen.value = true
  loadUsers()
}

async function submit() {
  if (!form.agentUserId) return message.warning('请选择代理人')
  if (!form.startTime || !form.endTime) return message.warning('请选择生效时间段')
  if (!form.startTime.isBefore(form.endTime)) return message.warning('开始时间必须早于结束时间')
  saving.value = true
  try {
    const res = await agentApi.save({
      id: form.id,
      agentUserId: form.agentUserId,
      startTime: form.startTime.format('YYYY-MM-DD HH:mm:ss'),
      endTime: form.endTime.format('YYYY-MM-DD HH:mm:ss'),
      processDefinitionKeys: form.processDefinitionKeys.trim(),
      delegateMode: form.delegateMode
    })
    if (res && res.error) {
      message.warning(res.message || '保存失败')
      return
    }
    message.success('规则已保存')
    formOpen.value = false
    await load()
  } catch (e) {
    message.error(errorText(e))
  } finally {
    saving.value = false
  }
}

const disableOpen = ref(false)
const disabling = ref(false)
const disableTarget = ref(null)
const cancelActive = ref(false)
// 规则下仍在委托中的条数由后端直接给出（含“任务仍在运行”的判定），前端不再二次过滤
const pendingOf = computed(() => (disableTarget.value && disableTarget.value.pendingCount) || 0)

async function toggleRule(record, enable) {
  if (!enable) {
    // 停用前先确认：是否连同委托中的任务一起收回
    disableTarget.value = record
    cancelActive.value = false
    disableOpen.value = true
    return
  }
  try {
    const res = await agentApi.enable(record.id)
    if (res && res.error) {
      message.warning(res.message || '启用失败')
      return
    }
    message.success('规则已启用')
    detailOpen.value = false
    await load()
  } catch (e) {
    message.error(errorText(e))
  }
}

async function confirmDisable() {
  const record = disableTarget.value
  if (!record) return
  disabling.value = true
  try {
    const res = await agentApi.disable(record.id, cancelActive.value)
    if (res && res.error) {
      message.warning(res.message || '停用失败')
      return
    }
    const canceled = (res && res.canceled) || 0
    message.success(canceled > 0 ? `规则已停用，同时收回 ${canceled} 条委托中任务` : '规则已停用')
    disableOpen.value = false
    detailOpen.value = false
    // 停用后角标要跟着变：通知外层重新拉一次待办/委托中数量
    emit('refresh-count')
    await load()
  } catch (e) {
    message.error(errorText(e))
  } finally {
    disabling.value = false
  }
}

onMounted(async () => {
  try {
    const info = await authApi.me()
    isAdmin.value = !!info.admin
  } catch (e) {
    // 权限信息失败不影响规则列表本身
  }
  load()
})
</script>

<style scoped>
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
