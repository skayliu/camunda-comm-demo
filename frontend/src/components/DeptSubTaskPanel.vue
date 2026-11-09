<template>
  <div class="dept-sub-panel">
    <a-spin :spinning="loading">
      <a-alert
        v-if="detail && detail.content"
        type="info"
        show-icon
        style="margin-bottom: 12px"
        :message="`${detail.deptName || detail.deptId} · ${detail.taskName}`"
        :description="detail.content"
      />

      <!-- 文书分发 -->
      <template v-if="isDispatch">
        <div class="panel-title">选择经办人</div>
        <a-form layout="vertical">
          <a-form-item label="经办人（仅限本部门成员）" required>
            <a-select
              v-model:value="form.handlerUserIds"
              mode="multiple"
              placeholder="选择本部门经办人"
              :options="memberOptions"
              :max-tag-count="6"
            />
          </a-form-item>
          <a-form-item label="意见规则" required>
            <a-radio-group v-model:value="form.opinionRule">
              <a-radio value="ALL">需全部经办人反馈</a-radio>
              <a-radio value="ANY">任一经办人反馈即可</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item label="分发说明">
            <a-textarea v-model:value="form.remark" :rows="2" />
          </a-form-item>
        </a-form>
      </template>

      <!-- 经办人意见 -->
      <template v-else-if="isHandler">
        <div class="panel-title">经办意见</div>
        <a-form layout="vertical">
          <a-form-item label="意见内容" required>
            <a-textarea v-model:value="form.opinion" :rows="4" :maxlength="500" show-count />
          </a-form-item>
          <a-form-item label="附件地址">
            <a-input v-model:value="form.attachments" placeholder="可选" />
          </a-form-item>
          <a-form-item>
            <a-checkbox v-model:checked="form.returnToClerk">无法办理，退回文书重新分发</a-checkbox>
          </a-form-item>
        </a-form>
      </template>

      <!-- 专属流程中间节点 -->
      <template v-else-if="isNode">
        <div class="panel-title">{{ detail.taskName }}</div>
        <a-form layout="vertical">
          <a-form-item label="本节点意见" required>
            <a-textarea v-model:value="form.opinion" :rows="4" :maxlength="500" show-count />
          </a-form-item>
        </a-form>
      </template>

      <!-- 汇总 -->
      <template v-else-if="isSummary">
        <div class="panel-title">经办意见汇总</div>
        <div v-if="opinionRows.length" class="opinion-list">
          <div v-for="row in opinionRows" :key="row.key" class="opinion-item">
            <div class="opinion-head">
              <b>{{ row.name }}</b>
              <span v-if="row.nodeName" class="muted">· {{ row.nodeName }}</span>
            </div>
            <div class="opinion-body">{{ row.opinion || '（未填写）' }}</div>
          </div>
        </div>
        <div v-else class="muted" style="margin-bottom: 12px">暂无经办意见</div>

        <a-form layout="vertical">
          <a-form-item label="部门最终意见" :required="form.actionType === 'SUBMIT'">
            <a-textarea v-model:value="form.finalOpinion" :rows="4" :maxlength="1000" show-count />
          </a-form-item>
          <a-form-item label="操作">
            <a-select v-model:value="form.actionType">
              <a-select-option value="SUBMIT">提交部门意见</a-select-option>
              <a-select-option value="RETURN_SUPPLEMENT">退回经办人补充</a-select-option>
              <a-select-option value="REDISPATCH">重新分发</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item v-if="form.actionType === 'RETURN_SUPPLEMENT'" label="需要补充的经办人" required>
            <a-select v-model:value="form.returnHandlerIds" mode="multiple" :options="memberOptions" />
          </a-form-item>
        </a-form>
      </template>

      <a-alert v-else type="warning" show-icon message="该节点暂不支持在待办中心办理" />
    </a-spin>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { deptSubApi } from '../api'

const props = defineProps({
  taskId: { type: String, default: '' }
})
const emit = defineEmits(['done'])

const loading = ref(false)
const detail = ref(null)
const form = reactive({
  handlerUserIds: [],
  opinionRule: 'ALL',
  remark: '',
  opinion: '',
  attachments: '',
  returnToClerk: false,
  finalOpinion: '',
  actionType: 'SUBMIT',
  returnHandlerIds: []
})

const isDispatch = computed(() => detail.value && detail.value.nodeType === 'DISPATCH')
const isHandler = computed(() => detail.value && detail.value.nodeType === 'HANDLER')
const isNode = computed(() => detail.value && detail.value.nodeType === 'NODE')
const isSummary = computed(() => detail.value && detail.value.nodeType === 'SUMMARY')

const memberOptions = computed(() => {
  const members = (detail.value && detail.value.deptMembers) || []
  return members.map((m) => ({ value: m.id, label: `${m.name || m.id}（${m.id}）` }))
})

// opinionMap 的 value 结构在通用流程（userId/opinion）与专属流程（nodeName/opinion）下不同
const opinionRows = computed(() => {
  const map = (detail.value && detail.value.opinionMap) || {}
  return Object.keys(map).map((k) => {
    const v = map[k] || {}
    const names = (detail.value && detail.value.handlerNames) || {}
    return {
      key: k,
      name: names[v.userId] || v.userId || k,
      nodeName: v.nodeName || '',
      opinion: v.opinion || ''
    }
  })
})

async function load() {
  if (!props.taskId) return
  loading.value = true
  try {
    detail.value = await deptSubApi.detail(props.taskId)
    if (detail.value.nodeType === 'SUMMARY') {
      form.finalOpinion = detail.value.finalOpinion || ''
      form.returnHandlerIds = detail.value.handlerUserIds || []
    }
  } catch (e) {
    message.error((e.response && e.response.data && e.response.data.message) || '任务加载失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!props.taskId) return
  if (detail.value && detail.value.nodeType === 'SUMMARY' && !canSubmitSummary()) return
  try {
    let res
    if (isDispatch.value) {
      if (!form.handlerUserIds.length) return message.warning('请至少选择一名经办人')
      res = await deptSubApi.dispatch(props.taskId, {
        handlerUserIds: form.handlerUserIds,
        opinionRule: form.opinionRule,
        remark: form.remark
      })
    } else if (isHandler.value) {
      res = await deptSubApi.opinion(props.taskId, {
        opinion: form.opinion,
        attachments: form.attachments,
        returnToClerk: form.returnToClerk
      })
    } else if (isNode.value) {
      res = await deptSubApi.nodeOpinion(props.taskId, { opinion: form.opinion })
    } else {
      res = await deptSubApi.summary(props.taskId, {
        finalOpinion: form.finalOpinion,
        actionType: form.actionType,
        returnHandlerIds: form.returnHandlerIds
      })
    }
    if (res && res.error) {
      message.warning(res.message || '操作失败')
      return
    }
    message.success((res && res.message) || '操作成功')
    emit('done')
  } catch (e) {
    message.error((e.response && e.response.data && e.response.data.message) || '操作失败')
  }
}

function canSubmitSummary() {
  if (form.actionType === 'SUBMIT' && !form.finalOpinion.trim()) {
    message.warning('请填写部门最终意见')
    return false
  }
  if (form.actionType === 'RETURN_SUPPLEMENT' && !form.returnHandlerIds.length) {
    message.warning('请选择需要补充意见的经办人')
    return false
  }
  return true
}

function submitDisabled() {
  return loading.value || !detail.value
}

watch(() => props.taskId, load, { immediate: true })

defineExpose({ submit, submitDisabled })
</script>

<style scoped>
.panel-title {
  font-weight: 600;
  margin: 4px 0 10px;
}
.opinion-list {
  margin-bottom: 12px;
}
.opinion-item {
  border-left: 3px solid #e6f4ff;
  padding: 6px 10px;
  margin-bottom: 8px;
  background: #fafafa;
}
.opinion-head {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
  margin-bottom: 4px;
}
.opinion-body {
  white-space: pre-wrap;
}
</style>
