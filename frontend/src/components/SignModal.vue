<template>
  <a-modal
    v-model:open="visible"
    :title="title"
    :confirm-loading="submitting"
    :ok-text="okText"
    cancel-text="取消"
    @ok="submit"
  >
    <a-form layout="vertical" style="margin-top: 8px">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 12px"
        :message="tip"
        :description="`来源待办：${taskName || taskId}`"
      />
      <a-form-item label="加签人" required>
        <a-select
          v-model:value="userIds"
          mode="multiple"
          :options="userOptions"
          placeholder="可一次选择多人"
          :max-tag-count="5"
          :filter-option="filterOption"
        />
      </a-form-item>

      <!-- 并行/串行由发起人选择；完成条件由管理员在部署期配置，这里不提供 -->
      <a-form-item v-if="supportsMode" label="多人审批顺序">
        <a-radio-group v-model:value="sequential">
          <a-radio :value="false">并行（同时出待办）</a-radio>
          <a-radio :value="true">串行（按顺序逐人审）</a-radio>
        </a-radio-group>
        <div class="sign-hint">
          完成条件（全部通过 / 任一通过）由流程管理员配置，发起人不可修改。
        </div>
      </a-form-item>

      <a-form-item :label="commentLabel">
        <a-textarea
          v-model:value="comment"
          :rows="3"
          :maxlength="500"
          show-count
          :placeholder="commentPlaceholder"
        />
      </a-form-item>

      <a-alert
        v-if="signType === 'POST'"
        type="warning"
        show-icon
        message="提交后本条待办将暂时锁定"
        description="加签人全部处理完之前，流程不会离开当前节点；任一加签人驳回，整轮不通过。"
      />
    </a-form>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import message from 'ant-design-vue/es/message'
import { commApi, errorText, signApi } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  taskId: { type: String, default: '' },
  taskName: { type: String, default: '' },
  signType: { type: String, default: 'PRE' }
})
const emit = defineEmits(['update:open', 'done'])

const visible = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value)
})

const userIds = ref([])
const sequential = ref(false)
const comment = ref('')
const userOptions = ref([])
const submitting = ref(false)

const supportsMode = computed(() => props.signType === 'PRE' || props.signType === 'POST')

const title = computed(() => {
  if (props.signType === 'PARALLEL') return '并加签（同轮一起审）'
  if (props.signType === 'POST') return '通过并后加签（我审完再让他审）'
  return '前加签（我先请他审）'
})

const okText = computed(() => (props.signType === 'POST' ? '提交通过并发起后加签' : '发起加签'))

// 前加签/并加签的意见是「发起说明」，后加签的是「我的审批意见」：三者都要能写，
// 但落地语义不同，label 与 placeholder 必须区分，避免用户以为是同一条审批意见
const commentLabel = computed(() => (props.signType === 'POST' ? '我的审批意见' : '加签说明'))

const commentPlaceholder = computed(() => {
  if (props.signType === 'POST') {
    return '意见会先暂存，加签轮次结束后再与加签结果合并，决定节点是否通过'
  }
  if (props.signType === 'PARALLEL') {
    return '说明为什么并加签、请对方重点看什么；会随本条加签记录展示，不影响节点通过条件'
  }
  return '说明为什么请他先审、需要他确认什么；会随本条加签记录展示给加签人'
})

const tip = computed(() => {
  if (props.signType === 'PARALLEL') {
    return '加签人进入当前节点，与原审批人同轮审批，共享节点已有的完成条件'
  }
  if (props.signType === 'POST') {
    return '当前待办先锁定，加签人审完后按“原意见 + 轮次结果”合并，再离开节点'
  }
  return '原待办先锁定，加签人先审；通过后原待办解锁继续审批，驳回则整轮不通过'
})

function filterOption(input, option) {
  return (option.label || '').toLowerCase().includes(input.toLowerCase())
}

async function loadUsers() {
  try {
    const list = await commApi.targets('')
    userOptions.value = (list || []).map((item) => ({
      value: item.id,
      label: item.name || item.id
    }))
  } catch (e) {
    message.error(errorText(e))
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) return
    userIds.value = []
    sequential.value = false
    comment.value = ''
    loadUsers()
  }
)

async function submit() {
  if (!userIds.value.length) {
    message.warning('请选择加签人')
    return
  }
  submitting.value = true
  try {
    if (props.signType === 'POST') {
      const res = await signApi.completePost({
        taskId: props.taskId,
        users: userIds.value,
        sequential: sequential.value,
        approved: true,
        comment: comment.value.trim() || null
      })
      if (res && res.error) {
        message.warning(res.message || '后加签发起失败')
        return
      }
      message.success('已提交，加签轮次结束后流程继续')
    } else {
      const res = await signApi.add({
        taskId: props.taskId,
        signType: props.signType,
        users: userIds.value,
        sequential: sequential.value,
        comment: comment.value.trim() || null
      })
      if (res && res.error) {
        message.warning(res.message || '加签发起失败')
        return
      }
      message.success(props.signType === 'PARALLEL' ? '已并加签' : '已发起前加签，原待办已锁定')
    }
    visible.value = false
    emit('done')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.sign-hint {
  margin-top: 6px;
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
  line-height: 1.6;
}
</style>
