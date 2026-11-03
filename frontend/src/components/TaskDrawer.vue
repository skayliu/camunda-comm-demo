<template>
  <a-drawer
    :open="open"
    :width="isMobile ? '100%' : 680"
    :body-style="{ paddingBottom: '16px' }"
    destroy-on-close
    @close="close"
  >
    <template #title>
      <div style="display: flex; align-items: center; gap: 8px">
        <span>{{ task.taskName || '待办详情' }}</span>
        <a-tag v-if="task.isCommTask" :color="task.isFollowUp ? 'gold' : 'purple'">
          {{ task.isFollowUp ? '沟通追问' : '沟通待办' }}
        </a-tag>
        <a-tag v-else color="blue">审批待办</a-tag>
      </div>
    </template>

    <a-spin :spinning="loading">
      <template v-if="loaded">
        <!-- 基本信息 -->
        <div class="drawer-section">
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item label="流程" :span="2">
              {{ task.processTitle || task.processDefinitionName || '审批流程' }}
            </a-descriptions-item>
            <a-descriptions-item label="当前节点">
              {{ task.taskName }}
              <span v-if="task.sourceTaskDefKey" class="muted">
                （来源 {{ task.sourceTaskDefKey }}）
              </span>
            </a-descriptions-item>
            <a-descriptions-item label="发起人">
              {{ task.applicant || '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="到达时间">
              {{ fmt(task.createTime) }}
            </a-descriptions-item>
            <a-descriptions-item label="流程实例">
              <span class="mono">{{ shortId(task.processInstanceId) }}</span>
            </a-descriptions-item>
          </a-descriptions>
        </div>

        <!-- 沟通待办：我的反馈 -->
        <template v-if="task.isCommTask">
          <div class="drawer-section">
            <div class="drawer-section-title">沟通内容</div>
            <div class="comm-bubble">
              <div style="margin-bottom: 8px; color: rgba(0, 0, 0, 0.45); font-size: 12px">
                {{ task.commInitiator || '发起人' }} 于 {{ fmt(task.createTime) }} 发起
              </div>
              {{ task.commContent || '（无内容）' }}
            </div>
          </div>

          <div class="drawer-section">
            <div class="drawer-section-title">同轮反馈进度</div>
            <div
              v-for="item in task.sessionTasks || []"
              :key="item.id"
              class="comm-target"
            >
              <div class="comm-target-head">
                <div class="comm-target-name">
                  <a-avatar :size="24" style="background: #1677ff">
                    {{ (item.targetName || item.targetId || '?').slice(0, 1) }}
                  </a-avatar>
                  <span>{{ item.targetName || item.targetId }}</span>
                  <a-tag v-if="item.id === task.commRecordId" color="blue">我</a-tag>
                </div>
                <a-tag :color="statusColor(item.status)">
                  {{ statusText(item.status) }}
                </a-tag>
              </div>
              <div class="muted" style="font-size: 12.5px">{{ item.content }}</div>
              <div v-if="item.replyContent" class="comm-reply">
                {{ item.replyContent }}
                <div style="margin-top: 4px; color: #7a9c85; font-size: 11px">
                  {{ item.replyTime ? fmt(item.replyTime) : '' }}
                </div>
              </div>
            </div>
          </div>

          <div v-if="task.myCommStatus === 'PENDING'" class="drawer-section">
            <div class="drawer-section-title">我的反馈</div>
            <a-textarea
              v-model:value="replyContent"
              :rows="4"
              placeholder="填写你的核实结果或意见，提交后本条沟通待办将完成"
              :maxlength="500"
              show-count
            />
          </div>
          <a-alert
            v-else
            type="success"
            show-icon
            :message="`本条沟通已处理（${statusText(task.myCommStatus)}），无需重复反馈`"
          />
        </template>

        <!-- 审批待办：沟通会话 + 审批 -->
        <template v-else>
          <div class="drawer-section">
            <div class="drawer-section-title">沟通状态</div>
            <a-alert
              v-if="isLocked"
              type="warning"
              show-icon
              message="当前待办正在沟通中，暂不可审批"
              :description="lockDescription"
            />
            <a-alert
              v-else
              type="success"
              show-icon
              message="可以正常审批"
              description="如需补充信息，可先向相关人员发起沟通。"
            />
          </div>

          <div v-if="commTasks.length" class="drawer-section">
            <div class="drawer-section-title">
              沟通记录
              <a-tag v-if="comm.sessionActive" color="processing" style="margin-left: 4px">
                进行中 · 第 {{ comm.currentRound }} 轮
              </a-tag>
              <a-tag v-else-if="comm.sessionId" color="default" style="margin-left: 4px">
                已结束
              </a-tag>
            </div>
            <div class="comm-bubble" style="margin-bottom: 12px">
              <div style="margin-bottom: 8px; color: rgba(0, 0, 0, 0.45); font-size: 12px">
                {{ comm.initiator }} 发起 · {{ fmt(comm.createTime) }}
              </div>
              {{ comm.initialContent }}
            </div>

            <div v-for="item in commTasks" :key="item.id" class="comm-target">
              <div class="comm-target-head">
                <div class="comm-target-name">
                  <a-avatar :size="24" style="background: #722ed1">
                    {{ (item.targetName || item.targetId || '?').slice(0, 1) }}
                  </a-avatar>
                  <span>{{ item.targetName || item.targetId }}</span>
                  <span class="mono muted">{{ item.targetId }}</span>
                </div>
                <a-space :size="6">
                  <a-tag :color="statusColor(item.status)">
                    {{ statusText(item.status) }}
                  </a-tag>
                  <a-popconfirm
                    v-if="item.status === 'PENDING' && comm.canWithdraw"
                    title="收回给该对象的沟通？"
                    ok-text="收回"
                    cancel-text="取消"
                    @confirm="withdrawOne(item)"
                  >
                    <a-button size="small" danger type="link">收回</a-button>
                  </a-popconfirm>
                  <a-button
                    v-if="item.status === 'REPLIED'"
                    size="small"
                    type="link"
                    @click="openContinue(item)"
                  >
                    追问
                  </a-button>
                </a-space>
              </div>
              <div class="muted" style="font-size: 12.5px">{{ item.content }}</div>
              <div
                v-if="item.replyContent"
                class="comm-reply"
                :class="{ pending: false }"
              >
                {{ item.replyContent }}
                <div style="margin-top: 4px; color: #7a9c85; font-size: 11px">
                  {{ item.replyUserId }} · {{ fmt(item.replyTime) }}
                </div>
              </div>
              <div v-else-if="item.status === 'PENDING'" class="comm-reply pending">
                等待 {{ item.targetName || item.targetId }} 反馈
              </div>
              <div v-else-if="item.status === 'WITHDRAWN'" class="comm-reply withdrawn">
                已收回
              </div>
            </div>
          </div>

          <div class="drawer-section">
            <div class="drawer-section-title">审批意见</div>
            <a-textarea
              v-model:value="approveComment"
              :rows="3"
              placeholder="填写审批意见（可选）"
              :maxlength="500"
              show-count
            />
          </div>
        </template>
      </template>
    </a-spin>

    <template #footer>
      <div class="drawer-footer">
        <a-button @click="close">关闭</a-button>

        <template v-if="task.isCommTask">
          <a-button
            type="primary"
            :loading="submitting"
            :disabled="task.myCommStatus !== 'PENDING'"
            @click="submitReply"
          >
            提交反馈
          </a-button>
        </template>

        <template v-else>
          <a-button
            :disabled="!canInitiate"
            :title="canInitiate ? '' : '仅当前待办处理人可发起沟通'"
            @click="openStart"
          >
            发起沟通
          </a-button>
          <a-popconfirm
            v-if="comm.sessionActive && comm.canWithdraw"
            title="收回全部沟通？收回后本次沟通结束，待办恢复可审批"
            ok-text="全部收回"
            cancel-text="取消"
            @confirm="withdrawAll"
          >
            <a-button danger>全部收回</a-button>
          </a-popconfirm>
          <a-tooltip
            :title="isLocked ? '存在未反馈且未收回的沟通对象，无法审批' : ''"
          >
            <a-button
              type="primary"
              :loading="submitting"
              :disabled="isLocked"
              @click="submitApprove"
            >
              通过审批
            </a-button>
          </a-tooltip>
        </template>
      </div>
    </template>

    <!-- 发起 / 追问 沟通 -->
    <a-modal
      v-model:open="commModal.open"
      :title="commModal.title"
      :confirm-loading="commSubmitting"
      ok-text="提交"
      cancel-text="取消"
      @ok="submitComm"
    >
      <a-form layout="vertical" style="margin-top: 8px">
        <a-form-item label="沟通对象" required>
          <a-select
            v-model:value="commForm.targets"
            mode="multiple"
            placeholder="选择需要反馈的人员"
            :options="targetOptions"
            :filter-option="filterOption"
            :max-tag-count="4"
          />
        </a-form-item>
        <a-form-item label="沟通内容" required>
          <a-textarea
            v-model:value="commForm.content"
            :rows="4"
            placeholder="说明需要对方核实或反馈的问题"
            :maxlength="500"
            show-count
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </a-drawer>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { commApi, errorText, wbApi } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  taskId: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'refresh'])

const loading = ref(false)
const loaded = ref(false)
const submitting = ref(false)
const task = ref({})
const approveComment = ref('')
const replyContent = ref('')
const isMobile = ref(window.innerWidth <= 768)

const comm = ref({})
const commTasks = computed(() =>
  Array.isArray(comm.value.commTasks) ? comm.value.commTasks : []
)

const isLocked = computed(() => comm.value.bizStatus === 'COMMUNICATING')
const canInitiate = computed(() => !!comm.value.canInitiate)
const lockDescription = computed(() => {
  const pending = commTasks.value.filter((t) => t.status === 'PENDING')
  if (!pending.length) {
    return '沟通状态正在刷新，请稍后再试。'
  }
  return `还有 ${pending.length} 位对象未反馈：${pending
    .map((t) => t.targetName || t.targetId)
    .join('、')}。需全部反馈或全部收回后才能审批。`
})

// 发起 / 追问
const commModal = reactive({ open: false, mode: 'start', parentId: null, title: '' })
const commForm = reactive({ targets: [], content: '' })
const commSubmitting = ref(false)
const targetOptions = ref([])
const targetNames = ref({})

function filterOption(input, option) {
  return (option.label || '').toLowerCase().includes(input.toLowerCase())
}

function fmt(value) {
  if (!value) return '-'
  return dayjs(value).format('YYYY-MM-DD HH:mm')
}

function shortId(id) {
  if (!id) return '-'
  return id.length > 14 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id
}

function statusText(status) {
  return { PENDING: '待反馈', REPLIED: '已反馈', WITHDRAWN: '已收回' }[status] || status || '-'
}

function statusColor(status) {
  return { PENDING: 'orange', REPLIED: 'green', WITHDRAWN: 'default' }[status] || 'default'
}

function close() {
  emit('update:open', false)
}

async function load() {
  if (!props.taskId) return
  loading.value = true
  loaded.value = false
  try {
    const data = await wbApi.taskDetail(props.taskId)
    task.value = data
    comm.value = data.commStatus || {}
    replyContent.value = ''
    approveComment.value = ''
    loaded.value = true
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
}

async function ensureTargets() {
  if (targetOptions.value.length) return
  try {
    const list = await commApi.targets('')
    targetOptions.value = list.map((u) => ({
      value: u.id,
      label: `${u.name}（${u.id}）`
    }))
    targetNames.value = Object.fromEntries(list.map((u) => [u.id, u.name]))
  } catch (e) {
    message.error(errorText(e))
  }
}

async function openStart() {
  await ensureTargets()
  commModal.mode = 'start'
  commModal.parentId = null
  commModal.title = '发起沟通'
  commForm.targets = []
  commForm.content = ''
  commModal.open = true
}

async function openContinue(item) {
  await ensureTargets()
  commModal.mode = 'continue'
  commModal.parentId = item.id
  commModal.title = `追问：${item.targetName || item.targetId}`
  commForm.targets = [item.targetId]
  commForm.content = ''
  commModal.open = true
}

function buildTargets() {
  return commForm.targets.map((id) => ({
    type: 'USER',
    id,
    name: targetNames.value[id] || id
  }))
}

async function submitComm() {
  if (!commForm.targets.length) {
    message.warning('请选择沟通对象')
    return
  }
  if (!commForm.content.trim()) {
    message.warning('请填写沟通内容')
    return
  }
  commSubmitting.value = true
  try {
    if (commModal.mode === 'start') {
      await commApi.start({
        taskId: props.taskId,
        content: commForm.content.trim(),
        targets: buildTargets()
      })
    } else {
      await commApi.continueCommunication({
        parentCommTaskRecordId: commModal.parentId,
        content: commForm.content.trim(),
        targets: buildTargets()
      })
    }
    message.success(commModal.mode === 'start' ? '沟通已发起' : '追问已发送')
    commModal.open = false
    await load()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    commSubmitting.value = false
  }
}

async function withdrawOne(item) {
  try {
    await commApi.withdraw(comm.value.sessionId, [item.id])
    message.success('已收回')
    await load()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  }
}

async function withdrawAll() {
  try {
    await commApi.withdraw(comm.value.sessionId, [])
    message.success('已全部收回')
    await load()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  }
}

async function submitReply() {
  if (!replyContent.value.trim()) {
    message.warning('请填写反馈内容')
    return
  }
  submitting.value = true
  try {
    await commApi.reply({
      commTaskRecordId: task.value.commRecordId,
      content: replyContent.value.trim()
    })
    message.success('反馈已提交')
    close()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    submitting.value = false
  }
}

async function submitApprove() {
  submitting.value = true
  try {
    const res = await commApi.complete(props.taskId, approveComment.value.trim())
    if (res && res.error) {
      message.warning(res.message || '当前无法审批')
    } else {
      message.success('审批已通过')
      close()
      emit('refresh')
    }
  } catch (e) {
    message.error(errorText(e))
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.open,
  (val) => {
    if (val) load()
  }
)

watch(
  () => props.taskId,
  () => {
    if (props.open) load()
  }
)
</script>
