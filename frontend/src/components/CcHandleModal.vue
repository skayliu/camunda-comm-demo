<template>
  <a-modal
    v-model:open="visible"
    :title="isCc ? '传阅（送达即可，不阻塞流程）' : '发起阅办（承办人反馈办理结果）'"
    :confirm-loading="submitting"
    :ok-text="isCc ? '发起传阅' : '发起阅办'"
    cancel-text="取消"
    @ok="submit"
  >
    <a-form layout="vertical" style="margin-top: 8px">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 12px"
        :message="isCc ? '待阅人只能查看，没有同意/拒绝等审批权限' : '阅办人只反馈办理结果，不参与审批决策'"
        :description="taskName ? `来源待办：${taskName}` : ''"
      />
      <a-form-item v-if="isCc" label="待阅人" required>
        <a-select v-model:value="userIds" mode="multiple" :options="userOptions" placeholder="选择待阅人" :max-tag-count="5" :filter-option="filterOption" />
      </a-form-item>
      <a-form-item v-if="isCc" label="传阅附言">
        <a-textarea v-model:value="note" :rows="2" :maxlength="200" show-count placeholder="可选，随传阅一起送达；待阅人只能查看，不能回复" />
      </a-form-item>

      <template v-else>
        <a-form-item label="承办人" required>
          <a-select v-model:value="assigneeId" :options="userOptions" placeholder="选择承办人" show-search :filter-option="filterOption" />
        </a-form-item>
        <a-form-item label="办理要求">
          <a-textarea v-model:value="instruction" :rows="3" :maxlength="500" show-count placeholder="说明需要对方办理/反馈的内容" />
        </a-form-item>
        <a-form-item label="办理时限">
          <a-input v-model:value="deadline" type="datetime-local" />
        </a-form-item>
      </template>
    </a-form>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import message from 'ant-design-vue/es/message'
import { ccApi, errorText, handleApi } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  mode: { type: String, default: 'cc' },
  taskId: { type: String, default: '' },
  processInstanceId: { type: String, default: '' },
  taskName: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'done'])

const isCc = computed(() => props.mode === 'cc')
const visible = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value)
})

const userOptions = ref([])
const userIds = ref([])
const note = ref('')
const assigneeId = ref(undefined)
const instruction = ref('')
const deadline = ref('')
const submitting = ref(false)

function filterOption(input, option) {
  return (option.name || '').toLowerCase().includes(input.toLowerCase())
}

async function loadUsers() {
  try {
    const list = isCc.value ? await ccApi.targets('') : await handleApi.users('')
    userOptions.value = (list || []).map((item) => ({ value: item.id, label: item.name, name: item.name }))
  } catch (e) {
    message.error(errorText(e))
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) return
    userIds.value = []
    note.value = ''
    assigneeId.value = undefined
    instruction.value = ''
    deadline.value = ''
    loadUsers()
  }
)

async function submit() {
  if (!props.processInstanceId) {
    message.warning('缺少流程实例，无法发起')
    return
  }
  submitting.value = true
  try {
    if (isCc.value) {
      if (!userIds.value.length) {
        message.warning('请选择待阅人')
        return
      }
      const res = await ccApi.add({
        processInstanceId: props.processInstanceId,
        taskId: props.taskId || null,
        userIds: userIds.value,
        ccType: 'MANUAL',
        note: note.value.trim() || null
      })
      message.success(`已传阅 ${res.addedCount || 0} 人${res.skippedCount ? `，跳过 ${res.skippedCount} 人（已有未撤回的传阅）` : ''}`)
    } else {
      if (!assigneeId.value) {
        message.warning('请选择承办人')
        return
      }
      const res = await handleApi.add({
        processInstanceId: props.processInstanceId,
        taskId: props.taskId || null,
        assigneeId: assigneeId.value,
        instruction: instruction.value || null,
        deadline: deadline.value ? deadline.value.replace('T', ' ') : null
      })
      message.success(res.duplicated ? res.message : '阅办已发起')
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
