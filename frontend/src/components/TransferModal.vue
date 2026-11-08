<template>
  <a-modal
    :open="open"
    title="转办待办"
    :confirm-loading="submitting"
    ok-text="确认转办"
    cancel-text="取消"
    @ok="submit"
    @cancel="close"
  >
    <a-form layout="vertical" style="margin-top: 8px">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 12px"
        :message="taskName ? `当前节点：${taskName}` : '转办待办'"
        description="转办后该待办交给对方办理，你将不再看到它；转办记录会保留在待办详情与流程跟踪的可见范围内。"
      />

      <a-form-item label="转办给" required>
        <a-select
          v-model:value="target"
          show-search
          allow-clear
          placeholder="选择接收人"
          :options="options"
          :filter-option="filterOption"
          :loading="loadingUsers"
        />
      </a-form-item>

      <a-form-item label="转办说明">
        <a-textarea
          v-model:value="reason"
          :rows="3"
          placeholder="说明转办原因（可选，最多 200 字）"
          :maxlength="200"
          show-count
        />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { ref, watch } from 'vue'
import message from 'ant-design-vue/es/message'
import { commApi, errorText, wbApi } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  taskId: { type: String, default: '' },
  taskName: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'done'])

const submitting = ref(false)
const loadingUsers = ref(false)
const target = ref(undefined)
const reason = ref('')
const options = ref([])

function filterOption(input, option) {
  return (option.label || '').toLowerCase().includes(input.toLowerCase())
}

function close() {
  emit('update:open', false)
}

async function loadUsers() {
  loadingUsers.value = true
  try {
    // 复用沟通对象接口：它已排除当前登录用户，正好避免“转给自己”
    const list = await commApi.targets('')
    options.value = list.map((u) => ({
      value: u.id,
      label: `${u.name}（${u.id}）`
    }))
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loadingUsers.value = false
  }
}

async function submit() {
  if (!target.value) {
    message.warning('请选择转办接收人')
    return
  }
  submitting.value = true
  try {
    const res = await wbApi.transfer(props.taskId, {
      targetUserId: target.value,
      reason: reason.value.trim()
    })
    // 业务拒绝是 200 + error:true，不判这里会把失败提示成“已转办”
    if (res && res.error) {
      message.warning(res.message || '无法转办')
      return
    }
    message.success(`已转办给 ${target.value}`)
    target.value = undefined
    reason.value = ''
    close()
    emit('done')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    submitting.value = false
  }
}

watch(
  () => props.open,
  (val) => {
    if (val) {
      target.value = undefined
      reason.value = ''
      loadUsers()
    }
  },
  // 组件可能由 v-if 在「已打开」状态下才挂载（按需加载），需要 immediate
  { immediate: true }
)
</script>
