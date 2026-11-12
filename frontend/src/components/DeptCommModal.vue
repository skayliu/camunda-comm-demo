<template>
  <a-modal
    :open="open"
    title="部门沟通"
    ok-text="发起"
    cancel-text="取消"
    :confirm-loading="submitting"
    @ok="submit"
    @cancel="close"
  >
    <a-alert
      type="info"
      show-icon
      style="margin-bottom: 12px"
      message="部门沟通由部门内部闭环处理"
      description="选择部门后，由该部门按选定流程处理并回传最终意见；期间当前待办将被锁定，不可审批。"
    />

    <a-form layout="vertical">
      <a-form-item label="征询部门" required>
        <a-select
          v-model:value="form.deptId"
          placeholder="选择部门"
          show-search
          option-filter-prop="label"
          :options="deptOptions"
          @change="onDeptChange"
        />
      </a-form-item>

      <a-form-item label="沟通流程" required>
        <a-spin :spinning="subLoading">
          <a-select v-model:value="form.subProcessKey" placeholder="选择该部门使用的流程">
            <a-select-option v-for="sp in subProcesses" :key="sp.processKey" :value="sp.processKey">
              <div class="sp-option">
                <span>{{ sp.processName }}</span>
                <a-tag v-if="sp.general" color="blue" style="margin-inline-start: 6px">通用</a-tag>
                <a-tag v-else color="purple" style="margin-inline-start: 6px">专属</a-tag>
              </div>
              <div class="sp-desc">{{ sp.description }}</div>
            </a-select-option>
          </a-select>
        </a-spin>
      </a-form-item>

      <a-form-item label="沟通内容" required>
        <a-textarea
          v-model:value="form.content"
          :rows="4"
          :maxlength="500"
          show-count
          placeholder="说明需要该部门反馈的问题"
        />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { commApi } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  taskId: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'submitted'])

const submitting = ref(false)
const subLoading = ref(false)
const departments = ref([])
const subProcesses = ref([])
const form = reactive({ deptId: undefined, subProcessKey: undefined, content: '' })

const deptOptions = computed(() =>
  departments.value.map((d) => ({
    value: d.id,
    label: `${d.name}（${d.memberCount || 0} 人）`,
    name: d.name
  }))
)

async function loadDepartments() {
  if (departments.value.length) return
  try {
    departments.value = await commApi.departments('')
  } catch (e) {
    message.error('部门列表加载失败')
  }
}

async function onDeptChange(deptId) {
  form.subProcessKey = undefined
  subProcesses.value = []
  subLoading.value = true
  try {
    subProcesses.value = await commApi.subprocesses(deptId)
    if (subProcesses.value.length === 1) {
      form.subProcessKey = subProcesses.value[0].processKey
    }
  } catch (e) {
    message.error('流程列表加载失败')
  } finally {
    subLoading.value = false
  }
}

function close() {
  emit('update:open', false)
}

async function submit() {
  if (!form.deptId) return message.warning('请选择征询部门')
  if (!form.subProcessKey) return message.warning('请选择沟通流程')
  if (!form.content.trim()) return message.warning('请填写沟通内容')

  const dept = departments.value.find((d) => d.id === form.deptId)
  submitting.value = true
  try {
    const res = await commApi.start({
      taskId: props.taskId,
      content: form.content.trim(),
      targets: [{
        type: 'DEPT',
        id: form.deptId,
        name: dept ? dept.name : form.deptId,
        subProcessKey: form.subProcessKey
      }]
    })
    if (res && res.error) {
      message.warning(res.message || '无法发起部门沟通')
      return
    }
    const added = (res && res.addedTargets) || []
    message.success(added.length ? `部门沟通已发起：${added.join('、')}` : '部门沟通已发起')
    form.content = ''
    emit('submitted')
    close()
  } catch (e) {
    message.error((e.response && e.response.data && e.response.data.message) || '发起失败')
  } finally {
    submitting.value = false
  }
}

// 每次打开时刷新部门（新部门可能刚配置）
function ensureLoaded() {
  loadDepartments()
}
ensureLoaded()
</script>

<style scoped>
.sp-option {
  display: flex;
  align-items: center;
}
.sp-desc {
  color: var(--text-tertiary);
  font-size: 12px;
  white-space: normal;
}
</style>
