<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">部门沟通流程登记</h2>
        <p class="page-desc">
          流程登记决定「哪个部门的沟通启动哪个流程」。以 <span class="mono">dept_comm_</span> 开头的流程部署后会自动写入流程登记，归属部门与启停状态由管理员维护
        </p>
      </div>
      <div class="head-actions">
        <a-tooltip placement="bottomRight">
          <template #title>
            <!-- 提示层渲染在 body 下，scoped 样式不生效，这里用内联样式 -->
            <div style="font-size: 12px; line-height: 1.8">
              <div style="font-weight: 600; margin-bottom: 2px">自动登记器</div>
              <template v-if="diag">
                <div>待登记队列：{{ diag.pendingCount }} 条</div>
                <div>逐条重试中：{{ diag.visibilityRetrying }} 条（上限 {{ diag.maxVisibilityRetry }} 轮）</div>
                <div>兜底同步中：{{ diag.fallbackWaiting }} 条（预算 {{ diag.maxFallbackSync }} 次）</div>
                <div>累计兜底 {{ diag.fallbackTotal }} 次 · 累计放弃 {{ diag.giveUpTotal }} 条</div>
                <div>已处理 {{ diag.processedTotal }} 条 · 消费线程{{ consumerText }}</div>
              </template>
              <div v-else>诊断接口不可用，无法读取队列状态</div>
            </div>
          </template>
          <div class="reg-health" :class="health.tone">
            <span class="dot"></span>
            <span>队列 {{ diag ? diag.pendingCount : '—' }}</span>
            <span class="sep">·</span>
            <span>兜底 {{ diag ? diag.fallbackTotal : '—' }}</span>
            <template v-if="diag && diag.giveUpTotal > 0">
              <span class="sep">·</span>
              <span>放弃 {{ diag.giveUpTotal }}</span>
            </template>
          </div>
        </a-tooltip>
        <a-space>
          <a-button :loading="syncing" @click="syncFromEngine">
            <template #icon><SyncOutlined /></template>
            从引擎同步
          </a-button>
          <a-button type="primary" @click="openCreate">
            <template #icon><PlusOutlined /></template>
            新增登记
          </a-button>
          <a-button :loading="loading" @click="load">
            <template #icon><ReloadOutlined /></template>
            刷新
          </a-button>
        </a-space>
      </div>
    </div>

    <a-alert
      v-if="health.tone === 'danger'"
      type="error"
      show-icon
      closable
      style="margin-bottom: 14px"
      :message="health.message"
      description="自动登记器已放弃过的部署不会再自动补。点「从引擎同步」可立即对齐一次；若反复出现，先查引擎部署是否延迟可见或登记表是否可写。"
    />

    <a-alert
      type="info"
      show-icon
      style="margin-bottom: 14px"
      message="自动登记只补流程侧字段，不会覆盖人工判断"
      description="部署时自动写入流程名称与引擎版本；归属部门、启停状态和说明由管理员维护，重新部署不会把它们改回去。流程 key 登记后不可修改。"
    />

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-label">登记总数</div>
        <div class="stat-value">{{ stats.total }}</div>
        <div class="stat-hint">含已停用记录</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">启用中</div>
        <div class="stat-value ok">{{ stats.enabled }}</div>
        <div class="stat-hint">发起时可选</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">待指定部门</div>
        <div class="stat-value" :class="{ warn: stats.unbound > 0 }">{{ stats.unbound }}</div>
        <div class="stat-hint">BPMN 未写 deptId 属性</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">引擎未部署</div>
        <div class="stat-value" :class="{ danger: stats.undeployed > 0 }">{{ stats.undeployed }}</div>
        <div class="stat-hint">登记了但启动会失败</div>
      </div>
    </div>

    <a-card :bordered="false" class="table-card">
      <div class="toolbar">
        <a-input-search
          v-model:value="keyword"
          allow-clear
          placeholder="搜索流程 key / 名称 / 部门"
          style="max-width: 320px"
          @search="load"
        />
        <span class="muted toolbar-hint">共 {{ rows.length }} 条</span>
      </div>

      <a-table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="id"
        size="middle"
        :pagination="{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }"
        :scroll="{ x: 1140 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'process'">
            <div class="proc-name">{{ record.processName }}</div>
            <div class="mono muted">{{ record.processKey }}</div>
          </template>

          <template v-else-if="column.key === 'dept'">
            <a-tag v-if="record.general" color="blue">全部门通用</a-tag>
            <a-tag v-else-if="record.unbound" color="orange">待指定部门</a-tag>
            <span v-else>
              {{ record.deptName }}
              <span class="mono muted dept-id">{{ record.deptId }}</span>
            </span>
          </template>

          <template v-else-if="column.key === 'bizType'">
            <span v-if="record.bizType" class="mono">{{ record.bizType }}</span>
            <span v-else class="muted">不限</span>
          </template>

          <template v-else-if="column.key === 'version'">
            <div>
              登记 <span class="mono">v{{ record.version }}</span>
            </div>
            <div class="muted" :class="{ 'text-danger': versionGap(record) }">
              <template v-if="!record.deployed">引擎未部署</template>
              <template v-else>引擎 <span class="mono">v{{ record.engineVersion }}</span></template>
            </div>
          </template>

          <template v-else-if="column.key === 'status'">
            <a-tag :color="record.status === 1 ? 'green' : 'default'">
              {{ record.status === 1 ? '启用' : '停用' }}
            </a-tag>
          </template>

          <template v-else-if="column.key === 'updateTime'">
            <span class="muted">{{ fmt(record.updateTime) }}</span>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space :size="0">
              <a-button
                type="link"
                size="small"
                :disabled="!record.deployed"
                @click="openDiagram(record)"
              >
                流程图
              </a-button>
              <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
              <a-button
                type="link"
                size="small"
                :disabled="record.unbound && record.status !== 1"
                @click="toggleStatus(record)"
              >
                {{ record.status === 1 ? '停用' : '启用' }}
              </a-button>
              <a-popconfirm
                title="删除后这条登记立即从可选列表消失"
                description="已经发起、仍在运行的实例不受影响；流程定义本身仍保留在引擎里。"
                ok-text="确认删除"
                cancel-text="取消"
                @confirm="remove(record)"
              >
                <a-button type="link" size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>

        <template #emptyText>
          <a-empty description="还没有流程登记记录">
            <a-button size="small" @click="openCreate">新增流程登记</a-button>
          </a-empty>
        </template>
      </a-table>
    </a-card>

    <a-modal
      v-model:open="formOpen"
      :title="form.id ? '编辑登记' : '新增登记'"
      :confirm-loading="saving"
      width="620px"
      ok-text="保存"
      cancel-text="取消"
      @ok="submit"
    >
      <a-form layout="vertical" style="margin-top: 8px">
        <a-form-item label="流程 key" required>
          <a-input
            v-model:value="form.processKey"
            :disabled="!!form.id"
            class="mono"
            placeholder="dept_comm_finance_budget"
            @blur="fillEngineInfo"
          />
          <div class="field-hint">
            <template v-if="form.id">key 是发起时定位流程的唯一依据，登记后不允许修改</template>
            <template v-else>必须与 BPMN 里的 process id 一致，且以 dept_comm_ 开头</template>
          </div>
        </a-form-item>

        <a-form-item label="流程名称" required>
          <a-input v-model:value="form.processName" :placeholder="form.processKey || '部署后自动取流程名'" />
        </a-form-item>

        <a-form-item label="归属部门" required>
          <a-select
            v-model:value="form.deptId"
            show-search
            :filter-option="false"
            placeholder="选择 DEPARTMENT 类型的组"
            :options="deptOptions"
            :loading="loadingDepts"
            @search="searchDepts"
          >
            <template #notFoundContent>
              <span class="muted">没有匹配的部门组</span>
            </template>
          </a-select>
          <div class="field-hint">只有类型为 DEPARTMENT 的组能被当作归属部门；全部门通用请选第一项</div>
        </a-form-item>

        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="业务类型">
              <a-input v-model:value="form.bizType" placeholder="如 CONTRACT / BUDGET，可留空" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="版本号">
              <a-input-number v-model:value="form.version" :min="0" style="width: 100%" />
              <div class="field-hint">填 0 或不填时自动取引擎最新版本</div>
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="状态">
          <a-radio-group v-model:value="form.status">
            <a-radio :value="1">启用</a-radio>
            <a-radio :value="0">停用</a-radio>
          </a-radio-group>
        </a-form-item>

        <a-form-item label="说明">
          <a-textarea
            v-model:value="form.description"
            :rows="3"
            :maxlength="500"
            show-count
            placeholder="写清这个流程适用于什么场景，便于发起人选择"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <BpmnDiagramModal
      v-model:open="diagramOpen"
      :process-key="diagramTarget.processKey"
      :process-name="diagramTarget.processName"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import message from 'ant-design-vue/es/message'
import { PlusOutlined, ReloadOutlined, SyncOutlined } from '@ant-design/icons-vue'
import BpmnDiagramModal from '../components/BpmnDiagramModal.vue'
import { adminSubProcessApi, errorText } from '../api'

const rows = ref([])
const keyword = ref('')
const loading = ref(false)
const syncing = ref(false)
const saving = ref(false)

// 自动登记器诊断：用来把"注册表少了一条记录"提前暴露成页面上的可见信号
const diag = ref(null)
let diagTimer = null
const DIAG_REFRESH_MS = 10000

const deptOptions = ref([{ value: '*', label: '全部门通用（*）' }])
const loadingDepts = ref(false)

const formOpen = ref(false)

// 流程图查看：默认取引擎里的最新版本，注册表版本只作为列表提示
const diagramOpen = ref(false)
const diagramTarget = reactive({ processKey: '', processName: '' })
const form = reactive({
  id: null,
  processKey: '',
  processName: '',
  deptId: '*',
  bizType: '',
  version: 0,
  status: 1,
  description: ''
})

const columns = [
  { title: '流程', key: 'process', width: 280 },
  { title: '归属部门', key: 'dept', width: 200 },
  { title: '业务类型', key: 'bizType', width: 120 },
  { title: '版本', key: 'version', width: 130 },
  { title: '状态', key: 'status', width: 90 },
  { title: '更新时间', key: 'updateTime', width: 170 },
  { title: '操作', key: 'action', width: 248, fixed: 'right' }
]

const stats = computed(() => {
  const list = rows.value
  return {
    total: list.length,
    enabled: list.filter((r) => r.status === 1).length,
    unbound: list.filter((r) => r.unbound).length,
    undeployed: list.filter((r) => !r.deployed).length
  }
})

// 注册表版本与引擎版本不一致时标红：这是"弹窗里看得到、点启动却报未部署"的根因
function versionGap(record) {
  return !record.deployed || Number(record.engineVersion) !== Number(record.version)
}

/**
 * 健康分级只按"是否已经产生后果"排序，不按数字大小：
 * 消费线程停掉 → 后续部署全部不登记；放弃过 → 注册表确实可能缺记录；
 * 有积压/兜底但没放弃 → 只是可见性抖动，正在自愈，用黄色提示即可。
 */
const health = computed(() => {
  const d = diag.value
  if (!d) return { tone: 'muted', message: '诊断接口不可用，无法确认自动登记状态' }
  if (d.consumerStarted && !d.consumerAlive) {
    return { tone: 'danger', message: '自动登记消费线程已停止，之后的部署不会再自动登记' }
  }
  if (d.giveUpTotal > 0) {
    return {
      tone: 'danger',
      message: `已有 ${d.giveUpTotal} 条部署被放弃自动登记，注册表可能缺记录`
    }
  }
  if (d.pendingCount > 0 || d.fallbackTotal > 0) {
    return {
      tone: 'warn',
      message: `队列积压 ${d.pendingCount} 条，累计兜底 ${d.fallbackTotal} 次`
    }
  }
  return { tone: 'ok', message: '自动登记正常' }
})

const consumerText = computed(() => {
  const d = diag.value
  if (!d) return '未知'
  if (!d.consumerStarted) return '未启动'
  return d.consumerAlive ? '运行中' : '已停止'
})

function fmt(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function load() {
  loading.value = true
  try {
    rows.value = (await adminSubProcessApi.list(keyword.value)) || []
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loading.value = false
  }
  // 诊断与列表互不依赖：诊断失败不该把列表一起判成失败
  await loadDiag()
}

/** 诊断是辅助信息，失败时静默降级为"—"，不弹错误打断管理员操作 */
async function loadDiag() {
  try {
    const res = await adminSubProcessApi.registrarDiagnostics()
    diag.value = res && !res.error ? res : null
  } catch (e) {
    diag.value = null
  }
}

function startDiagTimer() {
  stopDiagTimer()
  diagTimer = window.setInterval(() => {
    // 标签页在后台时不必轮询，回来时 loadDiag 会补一次
    if (!document.hidden) loadDiag()
  }, DIAG_REFRESH_MS)
}

function stopDiagTimer() {
  if (diagTimer) {
    window.clearInterval(diagTimer)
    diagTimer = null
  }
}

async function searchDepts(kw) {
  loadingDepts.value = true
  try {
    const list = (await adminSubProcessApi.departments(kw)) || []
    const options = list.map((d) => ({
      value: d.id,
      label: d.memberCount > 0 ? `${d.name}（${d.id}，${d.memberCount} 人）` : `${d.name}（${d.id}，无成员）`
    }))
    // 全部门通用始终置顶；当前已选中但搜索不到的值也要保留，否则下拉会显示成裸 ID
    const current = form.deptId && !['*', 'UNKNOWN'].includes(form.deptId)
      ? deptOptions.value.find((o) => o.value === form.deptId)
      : null
    const merged = [{ value: '*', label: '全部门通用（*）' }, ...options]
    if (current && !merged.some((o) => o.value === current.value)) merged.push(current)
    deptOptions.value = merged
  } catch (e) {
    message.error(errorText(e))
  } finally {
    loadingDepts.value = false
  }
}

function resetForm() {
  form.id = null
  form.processKey = ''
  form.processName = ''
  form.deptId = '*'
  form.bizType = ''
  form.version = 0
  form.status = 1
  form.description = ''
}

function openCreate() {
  resetForm()
  deptOptions.value = [{ value: '*', label: '全部门通用（*）' }]
  searchDepts('')
  formOpen.value = true
}

function openEdit(record) {
  form.id = record.id
  form.processKey = record.processKey
  form.processName = record.processName
  form.deptId = record.deptId
  form.bizType = record.bizType || ''
  form.version = record.version || 0
  form.status = record.status
  form.description = record.description || ''
  deptOptions.value = [{ value: '*', label: '全部门通用（*）' }]
  if (record.deptId === 'UNKNOWN') {
    deptOptions.value.push({ value: 'UNKNOWN', label: '待指定部门（UNKNOWN）' })
  }
  searchDepts('')
  formOpen.value = true
}

function openDiagram(record) {
  diagramTarget.processKey = record.processKey
  diagramTarget.processName = record.processName
  diagramOpen.value = true
}

// 新增时按 key 回填引擎里的名称与版本，减少手工填错；查不到就保持用户输入
async function fillEngineInfo() {
  if (form.id || !form.processKey) return
  const hit = rows.value.find((r) => r.processKey === form.processKey.trim())
  if (!hit) return
  if (!form.processName) form.processName = hit.processName
  if (!form.version) form.version = hit.version
}

async function submit() {
  if (!form.processKey || !form.processKey.trim()) return message.warning('请填写流程 key')
  if (!form.processName || !form.processName.trim()) return message.warning('请填写流程名称')
  if (!form.deptId) return message.warning('请选择归属部门')
  saving.value = true
  try {
    const res = await adminSubProcessApi.save({
      id: form.id,
      processKey: form.processKey.trim(),
      processName: form.processName.trim(),
      deptId: form.deptId,
      bizType: form.bizType ? form.bizType.trim() : null,
      version: form.version || 0,
      status: form.status,
      description: form.description ? form.description.trim() : null
    })
    if (res && res.error) {
      message.warning(res.message || '保存失败')
      return
    }
    message.success('登记已保存')
    formOpen.value = false
    await load()
  } catch (e) {
    message.error(errorText(e))
  } finally {
    saving.value = false
  }
}

async function toggleStatus(record) {
  const next = record.status === 1 ? 0 : 1
  try {
    const res = await adminSubProcessApi.setStatus(record.id, next)
    if (res && res.error) {
      message.warning(res.message || (next === 1 ? '启用失败' : '停用失败'))
      return
    }
    message.success(next === 1 ? '已启用，发起时可见' : '已停用，只影响后续发起')
    await load()
  } catch (e) {
    message.error(errorText(e))
  }
}

async function remove(record) {
  try {
    const res = await adminSubProcessApi.remove(record.id)
    if (res && res.error) {
      message.warning(res.message || '删除失败')
      return
    }
    message.success('登记已删除')
    await load()
  } catch (e) {
    message.error(errorText(e))
  }
}

async function syncFromEngine() {
  syncing.value = true
  try {
    const res = await adminSubProcessApi.sync()
    if (res && res.error) {
      message.warning(res.message || '同步失败')
      return
    }
    message.success(`同步完成：新增 ${res.created || 0}，更新 ${res.updated || 0}，无变化 ${res.skipped || 0}`)
    await load()
  } catch (e) {
    message.error(errorText(e))
  } finally {
    syncing.value = false
  }
}

onMounted(() => {
  load()
  startDiagTimer()
})

onBeforeUnmount(stopDiagTimer)
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.page-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
}

.page-desc {
  margin: 0;
  color: var(--text-tertiary);
  font-size: 13px;
}

.head-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.reg-health {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 18px;
  cursor: default;
  border: 1px solid transparent;
  white-space: nowrap;
}

.reg-health .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}

.reg-health .sep {
  opacity: 0.45;
}

.reg-health.ok {
  color: var(--success);
  background: var(--success-soft);
  border-color: var(--success-border);
}

.reg-health.warn {
  color: var(--warning);
  background: var(--warning-soft);
  border-color: var(--warning-border);
}

.reg-health.danger {
  color: var(--danger);
  background: var(--danger-soft);
  border-color: var(--danger-border);
}

.reg-health.muted {
  color: var(--text-secondary);
  background: var(--bg-subtle);
  border-color: var(--border-subtle);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.muted {
  color: var(--text-secondary);
}

.text-danger {
  color: var(--danger);
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.stat-card {
  padding: 14px 16px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
}

.stat-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.stat-value {
  margin: 4px 0 2px;
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-value.ok {
  color: var(--success);
}

.stat-value.warn {
  color: var(--warning);
}

.stat-value.danger {
  color: var(--danger);
}

.stat-hint {
  font-size: 12px;
  color: var(--text-secondary);
}

.table-card {
  border-radius: 10px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.toolbar-hint {
  font-size: 12px;
}

.proc-name {
  font-weight: 500;
}

.dept-id {
  margin-left: 6px;
  font-size: 12px;
}

.field-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-tertiary);
}

@media (max-width: 900px) {
  .stat-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
