<template>
  <a-modal
    :open="open"
    title="委托待办"
    :confirm-loading="submitting"
    ok-text="确认委托"
    cancel-text="取消"
    @ok="submit"
    @cancel="close"
  >
    <a-form layout="vertical" style="margin-top: 8px">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 12px"
        :message="taskName ? `当前节点：${taskName}` : '委托待办'"
        description="委托后责任仍在你名下：对方只能提交受托意见，不能办结；你确认后再审批，也可随时收回。"
      />
      <a-form-item label="委托给" required>
        <a-select
          v-model:value="target"
          show-search
          allow-clear
          placeholder="选择受托人"
          :options="options"
          :filter-option="filterOption"
          :loading="loadingUsers"
        />
      </a-form-item>
      <a-form-item label="委托说明">
        <a-textarea
          v-model:value="reason"
          :rows="3"
          placeholder="说明委托原因（可选，最多 500 字）"
          :maxlength="500"
          show-count
        />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
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
    // 复用沟通对象接口：已排除当前登录用户，避免“委托给自己”
    const list = await commApi.targets('')
    options.value = list.map((u) => ({ value: u.id, label: `${u.name}（${u.id}）` }))
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loadingUsers.value = false
  }
}

async function submit() {
  if (!target.value) {
    message.warning('请选择受托人')
    return
  }
  submitting.value = true
  try {
    const res = await wbApi.delegate(props.taskId, {
      targetUserId: target.value,
      reason: reason.value.trim()
    })
    if (res && res.error) {
      message.warning(res.message || '无法委托')
      return
    }
    message.success(`已委托给 ${target.value}`)
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
  }
)
</script>
