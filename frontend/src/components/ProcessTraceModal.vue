<template>
  <a-modal
    v-model:open="open"
    :width="modalWidth"
    :footer="null"
    :style="{ top: modalTop }"
    :wrap-class-name="wrapClassName"
    destroy-on-close
    @after-open-change="onAfterOpenChange"
  >
    <template #title>
      <div class="trace-title">
        <span>流程跟踪</span>
        <span v-if="data" class="trace-title-sub">{{ data.processTitle }}</span>
        <!-- 窗口缩放：放在标题栏右上角、关闭按钮左侧（移动端宽度已满，隐藏） -->
        <a-tooltip v-if="!isMobile" :title="isFullscreen ? '还原窗口尺寸' : '最大化窗口'">
          <a-button
            size="small"
            type="text"
            class="trace-window-btn"
            @click="toggleFullscreen"
          >
            <template #icon>
              <FullscreenExitOutlined v-if="isFullscreen" />
              <FullscreenOutlined v-else />
            </template>
          </a-button>
        </a-tooltip>
      </div>
    </template>

    <a-spin :spinning="loading" class="trace-spin">
      <template v-if="data">
        <div class="trace-body">
        <!-- 概览 + 工具栏 + 流程图：可整体收起，把高度让给节点执行明细 -->
        <div v-show="!collapsed" class="trace-top">
        <!-- 概览 -->
        <div class="trace-overview">
          <div class="trace-overview-main">
            <div class="trace-overview-title">
              {{ data.processTitle }}
              <a-tag :color="data.state === 'RUNNING' ? 'processing' : 'green'">
                {{ data.state === 'RUNNING' ? '进行中' : '已完成' }}
              </a-tag>
            </div>
            <div class="trace-overview-meta">
              <span>发起人 {{ data.startUserId || '-' }}</span>
              <span>发起于 {{ fmt(data.startTime) }}</span>
              <span v-if="data.endTime">结束于 {{ fmt(data.endTime) }}</span>
              <span v-else>已运行 {{ duration(data.durationInMillis, data.startTime) }}</span>
            </div>
            <div class="trace-overview-current">
              <span class="muted">当前节点：</span>
              <template v-if="data.currentNodeNames && data.currentNodeNames.length">
                <a-tag v-for="n in data.currentNodeNames" :key="n" color="blue">{{ n }}</a-tag>
              </template>
              <span v-else class="muted">流程已结束</span>
            </div>
          </div>
          <div class="trace-stats">
            <div class="trace-stat">
              <b>{{ data.stats.completed }}</b>
              <span>已完成</span>
            </div>
            <div class="trace-stat is-active">
              <b>{{ data.stats.active }}</b>
              <span>执行中</span>
            </div>
            <div class="trace-stat">
              <b>{{ data.stats.pending }}</b>
              <span>未开始</span>
            </div>
            <div class="trace-stat">
              <b>{{ data.stats.commTotal }}</b>
              <span>沟通任务</span>
            </div>
          </div>
        </div>

        <!-- 图例（视图操作统一收进画布右上角的浮动工具条） -->
        <div class="trace-toolbar">
          <div class="trace-legend">
            <span class="legend-item"><i class="dot done"></i>已完成</span>
            <span class="legend-item"><i class="dot active"></i>执行中</span>
            <span class="legend-item"><i class="dot flow"></i>流转中</span>
            <span class="legend-item"><i class="dot pending"></i>未开始</span>
            <span class="legend-item"><i class="dot comm"></i>沟通中</span>
            <span class="legend-item"><i class="dot withdrawn"></i>已收回</span>
            <span class="trace-hint">拖动平移 · 滚轮缩放</span>
          </div>
        </div>

        <!-- 流程图 -->
        <div
          ref="canvasRef"
          class="trace-canvas"
          :class="{ 'is-dragging': dragging }"
          @mouseleave="hideTip"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
          @wheel.prevent="onWheel"
        >
          <!-- 画布右上角浮动工具条：缩放 / 视图 / 刷新 / 全屏（全屏后依然可用） -->
          <div class="trace-canvas-tools" @pointerdown.stop @wheel.stop>
            <a-tooltip title="缩小">
              <a-button size="small" type="text" class="trace-tool-btn" @click="zoomBy(-0.15)">
                <template #icon><ZoomOutOutlined /></template>
              </a-button>
            </a-tooltip>
            <a-tooltip title="恢复到 100% 并居中">
              <span class="trace-zoom-text is-clickable" @click="resetZoom">
                {{ Math.round(scale * 100) }}%
              </span>
            </a-tooltip>
            <a-tooltip title="放大">
              <a-button size="small" type="text" class="trace-tool-btn" @click="zoomBy(0.15)">
                <template #icon><ZoomInOutlined /></template>
              </a-button>
            </a-tooltip>
            <span class="trace-canvas-tools-split"></span>
            <a-tooltip title="适应画布（宽高自适应）">
              <a-button size="small" type="text" class="trace-tool-btn" @click="fit">
                <template #icon><ExpandAltOutlined /></template>
              </a-button>
            </a-tooltip>
            <a-tooltip title="居中显示">
              <a-button size="small" type="text" class="trace-tool-btn" @click="recenter">
                <template #icon><AimOutlined /></template>
              </a-button>
            </a-tooltip>
            <span class="trace-canvas-tools-split"></span>
            <a-tooltip title="刷新跟踪数据">
              <a-button size="small" type="text" class="trace-tool-btn" :loading="loading" @click="load">
                <template #icon><ReloadOutlined /></template>
              </a-button>
            </a-tooltip>
            <a-tooltip title="导出流程图（带标题栏与图例）">
              <a-dropdown placement="bottomRight" :trigger="['click']">
                <a-button size="small" type="text" class="trace-tool-btn" :loading="exporting">
                  <template #icon><DownloadOutlined /></template>
                </a-button>
                <template #overlay>
                  <a-menu @click="onExportMenu">
                    <a-menu-item key="both">同时导出 SVG + PNG</a-menu-item>
                    <a-menu-item key="svg">仅导出 SVG（矢量）</a-menu-item>
                    <a-menu-item key="png">仅导出 PNG（2 倍图）</a-menu-item>
                    <a-menu-divider />
                    <a-menu-item key="detail">SVG + PNG（含节点明细）</a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </a-tooltip>
            <a-tooltip :title="canvasFullscreen ? '退出全屏（Esc）' : '全屏查看流程图'">
              <a-button size="small" type="text" class="trace-tool-btn" @click="toggleCanvasFullscreen">
                <template #icon>
                  <CompressOutlined v-if="canvasFullscreen" />
                  <ExpandOutlined v-else />
                </template>
              </a-button>
            </a-tooltip>
          </div>
          <div v-if="data.diagramAvailable" class="trace-stage" :style="stageStyle">
            <svg
            :width="svgWidth"
            :height="svgHeight"
            :viewBox="`0 0 ${data.diagramWidth} ${data.diagramHeight}`"
            class="trace-svg"
          >
            <defs>
              <marker
                id="trace-arrow-taken"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="7"
                markerHeight="7"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#52c41a" />
              </marker>
              <marker
                id="trace-arrow-active"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="7"
                markerHeight="7"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#1677ff" />
              </marker>
              <marker
                id="trace-arrow-idle"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="7"
                markerHeight="7"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#d9d9d9" />
              </marker>
            </defs>

            <!-- 连线 -->
            <g v-for="edge in data.edges" :key="edge.id">
              <polyline
                :points="edgePoints(edge)"
                fill="none"
                :class="{ 'trace-edge-active': edge.active }"
                :stroke="edge.active ? '#1677ff' : edge.taken ? '#52c41a' : '#d9d9d9'"
                :stroke-width="edge.active ? 2.2 : edge.taken ? 2 : 1.4"
                :marker-end="
                  edge.active
                    ? 'url(#trace-arrow-active)'
                    : edge.taken
                      ? 'url(#trace-arrow-taken)'
                      : 'url(#trace-arrow-idle)'
                "
              />
            </g>

            <!-- 节点 -->
            <g
              v-for="node in data.nodes"
              :key="node.nodeId"
              class="trace-node"
              :class="{ 'is-selected': selectedId === node.nodeId }"
              :data-node-id="node.nodeId"
              @mouseenter="showTip($event, node)"
              @mousemove="moveTip"
              @mouseleave="hideTip"
            >
              <!-- 执行中呼吸轮廓 -->
              <template v-if="node.status === 'ACTIVE'">
                <circle
                  v-if="isEvent(node)"
                  :cx="cx(node)"
                  :cy="cy(node)"
                  :r="node.width / 2 + 3"
                  fill="none"
                  :stroke="node.commLocked ? '#fa8c16' : '#1677ff'"
                  stroke-width="4"
                  class="trace-pulse"
                />
                <polygon
                  v-else-if="isGateway(node)"
                  :points="diamondPoints(node)"
                  fill="none"
                  :stroke="node.commLocked ? '#fa8c16' : '#1677ff'"
                  stroke-width="4"
                  class="trace-pulse"
                />
                <rect
                  v-else
                  :x="node.x - 3"
                  :y="node.y - 3"
                  :width="node.width + 6"
                  :height="node.height + 6"
                  rx="13"
                  fill="none"
                  :stroke="node.commLocked ? '#fa8c16' : '#1677ff'"
                  stroke-width="4"
                  class="trace-pulse"
                />
              </template>

              <!-- 开始 / 结束 / 中间 / 边界事件 -->
              <template v-if="isEvent(node)">
                <circle
                  :cx="cx(node)"
                  :cy="cy(node)"
                  :r="node.width / 2"
                  :fill="nodeFill(node)"
                  :stroke="nodeStroke(node)"
                  :stroke-width="node.type === 'endEvent' ? 3 : 2"
                />
                <circle
                  v-if="node.type === 'endEvent'"
                  :cx="cx(node)"
                  :cy="cy(node)"
                  :r="node.width / 2 - 4.5"
                  :fill="nodeStroke(node)"
                  opacity="0.9"
                />
                <text
                  :x="cx(node)"
                  :y="node.y + node.height + 14"
                  text-anchor="middle"
                  class="trace-label"
                  :fill="nodeTextColor(node)"
                >{{ shortName(node.name) }}</text>
              </template>

              <!-- 网关 -->
              <template v-else-if="isGateway(node)">
                <polygon
                  :points="diamondPoints(node)"
                  :fill="nodeFill(node)"
                  :stroke="nodeStroke(node)"
                  stroke-width="2"
                />
                <text
                  :x="cx(node)"
                  :y="cy(node) + 4"
                  text-anchor="middle"
                  class="trace-gateway-mark"
                  :fill="nodeTextColor(node)"
                >{{ gatewayMark(node) }}</text>
                <text
                  :x="cx(node)"
                  :y="node.y + node.height + 14"
                  text-anchor="middle"
                  class="trace-label"
                  :fill="nodeTextColor(node)"
                >{{ shortName(node.name) }}</text>
              </template>

              <!-- 任务 / 子流程 -->
              <template v-else>
                <rect
                  :x="node.x"
                  :y="node.y"
                  :width="node.width"
                  :height="node.height"
                  rx="10"
                  :fill="nodeFill(node)"
                  :stroke="nodeStroke(node)"
                  :stroke-width="selectedId === node.nodeId ? 2.6 : 1.6"
                />
                <text
                  :x="cx(node)"
                  :y="node.y + node.height / 2 - 4"
                  text-anchor="middle"
                  class="trace-label"
                  :fill="nodeTextColor(node)"
                >{{ shortName(node.name, 9) }}</text>
                <text
                  :x="cx(node)"
                  :y="node.y + node.height / 2 + 16"
                  text-anchor="middle"
                  class="trace-sublabel"
                  :fill="node.status === 'PENDING' ? '#bfbfbf' : '#8c8c8c'"
                >{{ subText(node) }}</text>
              </template>

              <!-- 状态角标 -->
              <g v-if="!isEvent(node)">
                <circle
                  :cx="node.x + node.width - 9"
                  :cy="node.y + 9"
                  r="6"
                  :fill="nodeStroke(node)"
                  stroke="#fff"
                  stroke-width="1.5"
                />
                <text
                  :x="node.x + node.width - 9"
                  :y="node.y + 12.5"
                  text-anchor="middle"
                  class="trace-badge"
                >{{ node.status === 'COMPLETED' ? '✓' : (node.status === 'ACTIVE' ? '▶' : '') }}</text>
              </g>

              <!-- 沟通任务角标：待反馈（橙）/ 已收回（灰）/ 已反馈（紫），三者互斥优先展示 -->
              <g v-if="node.commTasks && node.commTasks.length">
                <rect
                  :x="node.x + node.width - commBadge(node).width"
                  :y="node.y + node.height - 15"
                  :width="commBadge(node).width"
                  height="15"
                  rx="7.5"
                  :fill="commBadge(node).fill"
                />
                <text
                  :x="node.x + node.width - commBadge(node).width / 2"
                  :y="node.y + node.height - 4"
                  text-anchor="middle"
                  class="trace-comm-badge"
                >{{ commBadge(node).text }}</text>
              </g>
            </g>
            </svg>
          </div>

          <div v-else class="empty-tip">
            该流程定义的 BPMN 图形信息不可用，下方仍可查看节点执行明细。
          </div>

          <!-- 悬停信息 -->
          <div v-if="tipNode" class="trace-tip" :style="tipStyle">
            <div class="trace-tip-head">
              <span class="trace-tip-name">{{ tipNode.name }}</span>
              <span class="trace-tip-status" :class="'is-' + tipNode.status.toLowerCase()">
                {{ statusText(tipNode) }}
              </span>
            </div>
            <div class="trace-tip-row">
              <span>节点类型</span><b>{{ tipNode.typeLabel }}</b>
            </div>
            <div class="trace-tip-row">
              <span>处理人</span><b>{{ tipNode.assignees.length ? tipNode.assignees.join('、') : '—' }}</b>
            </div>
            <div class="trace-tip-row">
              <span>到达时间</span><b>{{ fmt(tipNode.startTime) }}</b>
            </div>
            <div class="trace-tip-row">
              <span>完成时间</span><b>{{ tipNode.endTime ? fmt(tipNode.endTime) : '—' }}</b>
            </div>
            <div class="trace-tip-row">
              <span>停留时长</span><b>{{ duration(tipNode.durationInMillis, tipNode.startTime) }}</b>
            </div>
            <div v-if="tipNode.executionCount > 1" class="trace-tip-row">
              <span>执行次数</span><b>{{ tipNode.executionCount }} 次</b>
            </div>
            <div v-if="tipNode.commTasks && tipNode.commTasks.length" class="trace-tip-row">
              <span>沟通任务</span>
              <b>{{ tipNode.commTasks.length }} 条{{ commSummaryText(tipNode) }}</b>
            </div>
            <div v-if="tipNode.comments && tipNode.comments.length" class="trace-tip-comment">
              {{ tipNode.comments[tipNode.comments.length - 1].message }}
            </div>
            <div class="trace-tip-foot">点击节点定位执行明细</div>
          </div>
        </div>
        </div>

        <!-- 节点执行明细 -->
        <div ref="detailRef" class="trace-detail">
          <div class="trace-detail-head">
            <div class="trace-detail-title">
              <a-tooltip
                :title="collapsed ? '展开概览与流程图' : '收起概览与流程图，给明细更多空间'"
              >
                <a-button type="text" size="small" class="trace-collapse-btn" @click="toggleCollapse">
                  <template #icon>
                    <DownOutlined v-if="collapsed" />
                    <UpOutlined v-else />
                  </template>
                  <span class="trace-collapse-label">
                    {{ collapsed ? '展开概览与流程图' : '收起概览与流程图' }}
                  </span>
                </a-button>
              </a-tooltip>
              <div class="drawer-section-title">
                节点执行明细
                <span v-if="data.stats.commTotal" class="trace-detail-stats">
                  沟通 {{ data.stats.commTotal }} 条
                  <b v-if="data.stats.commPending">· 待反馈 {{ data.stats.commPending }}</b>
                </span>
              </div>
            </div>
            <label class="trace-only-comm">
              <a-switch size="small" :checked="onlyComm" @change="toggleOnlyComm" />
              <span>只看有沟通的节点</span>
            </label>
          </div>
          <a-table
            ref="tableRef"
            :columns="columns"
            :data-source="tableData"
            :pagination="false"
            size="small"
            row-key="nodeId"
            :scroll="{ x: 720, y: tableScrollY }"
            :row-class-name="rowClassName"
            :custom-row="customRow"
            :expanded-row-keys="expandedKeys"
            :row-expandable="rowExpandable"
            @expand="onExpand"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'name'">
                <span style="font-weight: 500">{{ record.name }}</span>
                <div class="muted" style="font-size: 12px">{{ record.typeLabel }}</div>
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag :color="statusColor(record)">{{ statusText(record) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'assignee'">
                {{ record.assignees.length ? record.assignees.join('、') : '—' }}
              </template>
              <template v-else-if="column.key === 'time'">
                <div>{{ fmt(record.startTime) }}</div>
                <div class="muted" style="font-size: 12px">
                  {{ record.endTime ? '至 ' + fmt(record.endTime) : '处理中' }}
                </div>
              </template>
              <template v-else-if="column.key === 'duration'">
                {{ duration(record.durationInMillis, record.startTime) }}
              </template>
              <template v-else-if="column.key === 'memo'">
                <template v-if="record.commTasks && record.commTasks.length">
                  <a-tag
                    :color="record.pendingCommCount ? 'orange' : 'purple'"
                    class="trace-comm-tag"
                    @click.stop="toggleExpandRow(record)"
                  >
                    {{ commTagText(record) }}
                  </a-tag>
                </template>
                <span v-if="record.comments && record.comments.length" class="muted">
                  {{ record.comments[record.comments.length - 1].message }}
                </span>
                <span v-else-if="!record.commTasks || !record.commTasks.length" class="muted">—</span>
              </template>
            </template>

            <!-- 行内展开：该节点发起的沟通会话明细 -->
            <template #expandedRowRender="{ record }">
              <div class="trace-comm-panel">
                <div
                  v-for="session in commSessions(record)"
                  :key="session.key"
                  class="trace-comm-session"
                >
                  <div class="trace-comm-head">
                    <span class="trace-comm-target">
                      <i class="trace-comm-dot" :class="session.dotClass"></i>
                      沟通对象：{{ session.targetName }}
                      <span class="trace-comm-type">{{ session.targetTypeText }}</span>
                    </span>
                    <span class="trace-comm-status" :class="session.dotClass">
                      {{ session.statusText }}
                    </span>
                  </div>

                  <div
                    v-for="item in session.items"
                    :key="item.taskId"
                    class="trace-comm-item"
                    :class="{ 'is-follow': !!item.parentCommTaskId }"
                  >
                    <div class="trace-comm-meta">
                      <span class="trace-comm-from">{{ item.initiator || '—' }}</span>
                      <span class="trace-comm-arrow">→</span>
                      <span class="trace-comm-to">{{ item.targetName || item.assignee || '—' }}</span>
                      <span class="trace-comm-time">{{ fmtShort(item.createTime || item.startTime) }}</span>
                    </div>

                    <div
                      class="trace-comm-text"
                      :class="{ 'is-clamped': needClamp(item) && !isTextOpen(item.taskId) }"
                    >{{ item.content || '（无沟通内容）' }}</div>
                    <button
                      v-if="needClamp(item)"
                      type="button"
                      class="trace-comm-toggle"
                      @click.stop="toggleText(item.taskId)"
                    >{{ isTextOpen(item.taskId) ? '收起' : '展开' }}</button>

                    <div v-if="showReply(item)" class="trace-comm-reply">
                      <div class="trace-comm-meta">
                        <span class="trace-comm-arrow">↳</span>
                        <span class="trace-comm-from">{{ replyUser(item) }}</span>
                        <span class="trace-comm-time">
                          {{ fmtShort(replyTimeOf(item)) }}{{ replyCost(item) }}
                        </span>
                      </div>
                      <div class="trace-comm-reply-text" :class="{ 'is-empty': !item.replyContent }">
                        {{ item.replyContent || '（未填写反馈意见）' }}
                      </div>
                    </div>
                    <div v-else-if="item.pending" class="trace-comm-waiting">等待回复中…</div>
                    <div v-else-if="item.status === 'WITHDRAWN'" class="trace-comm-waiting is-muted">
                      已收回<span v-if="item.withdrawTime">于 {{ fmtShort(item.withdrawTime) }}</span>
                    </div>

                    <!-- 后端已把两个含义拆开：camundaTaskId 存沟通旁路任务ID，
                         deptProcInstId 预留给部门沟通子流程实例（当前恒为空）。
                         部门子流程尚未实现，仍在空值时隐藏，避免展示空壳信息。 -->
                    <div v-if="item.deptProcInstId" class="trace-comm-waiting is-muted">
                      部门流转实例：{{ item.deptProcInstId }}
                    </div>
                  </div>
                </div>
                <div v-if="!record.commTasks || !record.commTasks.length" class="muted">
                  该节点没有沟通记录
                </div>
              </div>
            </template>
          </a-table>
        </div>
        </div>
      </template>
      <div v-else-if="!loading" class="empty-tip">暂无跟踪数据</div>
    </a-spin>
  </a-modal>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import dayjs from 'dayjs'
import {
  AimOutlined,
  ExpandAltOutlined,
  CompressOutlined,
  DownloadOutlined,
  DownOutlined,
  ExpandOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  ReloadOutlined,
  UpOutlined,
  ZoomInOutlined,
  ZoomOutOutlined
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { errorText, wbApi } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  processInstanceId: { type: String, default: '' }
})
const emit = defineEmits(['update:open'])

const open = computed({
  get: () => props.open,
  set: (v) => emit('update:open', v)
})

const isMobile = ref(window.innerWidth <= 768)
// 默认全屏展示，再次点击可缩回常规尺寸（960px），并可随时切回全屏
const isFullscreen = ref(true)
// 流程图区域全屏（浏览器 Fullscreen API，作用于画布元素，Esc 可退出）
const canvasFullscreen = ref(false)
const loading = ref(false)
const data = ref(null)
// 概览 / 工具栏 / 流程图整体收起：明细多时把上方空间全部让给表格
const collapsed = ref(false)
// 导出中：图片栅格化（PNG）需要一点时间，按钮给个 loading 防止重复点击
const exporting = ref(false)
const scale = ref(1)
const selectedId = ref('')
// 明细表行展开：同一时间只展开一行，避免长流程里多段沟通把表格撑得找不到位置
const expandedKeys = ref([])
// 只看有沟通的节点：过滤明细表，长流程里快速定位沟通记录
const onlyComm = ref(false)
// 长沟通内容的展开状态，按 taskId 记录（内容超过 3 行时提供展开/收起）
const openTexts = ref({})
const canvasRef = ref(null)
const tableRef = ref(null)
const detailRef = ref(null)
// 明细表表体高度：按明细区实际可用高度推算，实现表头固定、表体内部滚动
const tableScrollY = ref(220)
const DETAIL_HEADER_HEIGHT = 40
const DETAIL_MIN_SCROLL = 120
let detailObserver = null

// 画布平移（px）：默认居中显示，并支持拖动查看
const panX = ref(0)
const panY = ref(0)
const dragging = ref(false)
// 用户是否手动平移/缩放过：手动操作后画布尺寸变化不再强制回中，避免视图被拽走
let userMoved = false
const MIN_SCALE = 0.2
const MAX_SCALE = 2
const PAN_MARGIN = 80
let dragStart = null
let dragMoved = false
let dragResetTimer = null
let resizeTimer = null
// 按下时命中的节点：与位移阈值一起判定「点击」还是「拖动」
let pressNodeId = ''
// 手动维护的行高亮，避免表格因 props 未变化而不重渲染导致 class 不生效
let activeRowEl = null
let rowFlashTimer = null
let canvasFsTimer = null

const tipNode = ref(null)
const tipStyle = ref({})

const columns = [
  { title: '节点', key: 'name', width: 200 },
  { title: '状态', key: 'status', width: 110 },
  { title: '处理人', key: 'assignee', width: 140 },
  { title: '开始 / 结束', key: 'time', width: 190 },
  { title: '停留时长', key: 'duration', width: 130 },
  { title: '意见 / 沟通', key: 'memo', width: 240, ellipsis: true }
]

const EVENT_TYPES = [
  'startEvent',
  'endEvent',
  'intermediateCatchEvent',
  'intermediateThrowEvent',
  'boundaryEvent'
]

// 明细表数据源：开启「只看有沟通的节点」后只保留发起过沟通的节点
const tableData = computed(() => {
  const nodes = data.value?.nodes || []
  return onlyComm.value ? nodes.filter((n) => n.commTasks && n.commTasks.length) : nodes
})

const svgWidth = computed(() =>
  data.value ? Math.max(1, Math.round(data.value.diagramWidth * scale.value)) : 0
)
const svgHeight = computed(() =>
  data.value ? Math.round(data.value.diagramHeight * scale.value) : 0
)

// 图内容的真实包围盒：BPMN 模型常带画布留白（节点 y 从 80 起、上下各留一截），
// 只居中外框 svg 会让内容看起来偏上或偏下，因此按内容包围盒居中
const contentBox = computed(() => {
  const d = data.value
  if (!d || !d.diagramAvailable) return null
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  ;(d.nodes || []).forEach((n) => {
    if (n.x == null || n.y == null) return
    minX = Math.min(minX, n.x)
    minY = Math.min(minY, n.y)
    maxX = Math.max(maxX, n.x + (n.width || 0))
    maxY = Math.max(maxY, n.y + (n.height || 0))
  })
  ;(d.edges || []).forEach((e) => {
    ;(e.waypoints || []).forEach((p) => {
      if (p.x == null || p.y == null) return
      minX = Math.min(minX, p.x)
      minY = Math.min(minY, p.y)
      maxX = Math.max(maxX, p.x)
      maxY = Math.max(maxY, p.y)
    })
  })
  if (!Number.isFinite(minX) || !Number.isFinite(minY) || maxX <= minX) return null
  return { minX, minY, maxX, maxY }
})

const stageStyle = computed(() => ({
  transform: `translate3d(${panX.value}px, ${panY.value}px, 0)`
}))

// 全屏：几乎占满视口；缩小：恢复原先的 960px 居中弹窗
const modalWidth = computed(() => {
  if (isMobile.value) return '100%'
  return isFullscreen.value ? 'calc(100vw - 32px)' : 960
})
const modalTop = computed(() => {
  if (isMobile.value) return '8px'
  return isFullscreen.value ? '12px' : '42px'
})
const wrapClassName = computed(() =>
  isFullscreen.value ? 'trace-modal trace-modal-full' : 'trace-modal'
)

// 全屏 / 缩小切换：尺寸变了要重算明细表高度并让流程图重新居中
function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value
  nextTick(() => {
    // 等 antd 完成尺寸变更（宽度为内联样式，瞬时生效；留一帧兜底）
    setTimeout(() => {
      measureDetail()
      // 尺寸变化后保持当前缩放比例，只重新居中，不改变缩放级别
      userMoved = false
      center()
    }, 60)
  })
}

// 画布全屏：直接把画布元素交给浏览器全屏，DOM 不移动，隐藏时用 Esc 退出
async function toggleCanvasFullscreen() {
  const el = canvasRef.value
  if (!el) return
  if (!data.value || !data.value.diagramAvailable) {
    message.info('该流程没有可显示的流程图')
    return
  }
  const fsEl = document.fullscreenElement || document.webkitFullscreenElement
  if (fsEl === el) {
    exitCanvasFullscreen()
    return
  }
  // 收起状态下画布是 display:none，全屏前先展开
  if (collapsed.value) {
    collapsed.value = false
    await nextTick()
  }
  const request = el.requestFullscreen || el.webkitRequestFullscreen
  if (!request) {
    message.info('当前浏览器不支持流程图全屏查看')
    return
  }
  try {
    await request.call(el)
  } catch (e) {
    message.warning('进入全屏失败，可能被浏览器权限设置拦截')
  }
}

// 退出画布全屏（弹窗关闭或组件卸载时也要确保浏览器回到普通视图）
function exitCanvasFullscreen() {
  const el = canvasRef.value
  const fsEl = document.fullscreenElement || document.webkitFullscreenElement
  if (!el || fsEl !== el) return
  const exit = document.exitFullscreen || document.webkitExitFullscreen
  if (!exit) return
  try {
    const ret = exit.call(document)
    if (ret && typeof ret.catch === 'function') ret.catch(() => {})
  } catch (e) {
    /* 忽略：元素已销毁时浏览器会自动退出全屏 */
  }
}

// 全屏状态以浏览器为准：Esc、点击浏览器退出按钮都能同步回按钮图标
function onFullscreenChange() {
  const el = canvasRef.value
  canvasFullscreen.value = !!el && (document.fullscreenElement === el || document.webkitFullscreenElement === el)
  // 进出全屏都会改变画布尺寸，重新按内容包围盒居中（100% 比例不变）
  nextTick(() => {
    clearTimeout(canvasFsTimer)
    canvasFsTimer = setTimeout(() => {
      measureDetail()
      userMoved = false
      center()
    }, 80)
  })
}

// ===== 导出 SVG =====
// 页面上的 svg 依赖组件内的 class 样式（字体、字数、虚线动画等），
// 直接序列化会丢掉这些样式，因此克隆后把计算样式逐元素内联，再按内容包围盒裁剪 viewBox。
const SVG_NS = 'http://www.w3.org/2000/svg'
const SVG_INLINE_PROPS = [
  'fill',
  'fill-opacity',
  'fill-rule',
  'stroke',
  'stroke-width',
  'stroke-opacity',
  'stroke-dasharray',
  'stroke-linecap',
  'stroke-linejoin',
  'opacity',
  'paint-order',
  'font-family',
  'font-size',
  'font-weight',
  'font-style',
  'letter-spacing',
  'text-anchor',
  'dominant-baseline'
]

// 文件名里不能出现路径分隔符等字符，否则部分浏览器会直接放弃下载
function safeFileName(name) {
  return String(name || '')
    .replace(/[\\/:*?"<>|\r\n\t]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 60)
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}

// 导出配色与界面图例保持一致，避免导出后颜色与页面不一致
const EXPORT_COLORS = {
  done: '#52c41a',
  active: '#1677ff',
  pending: '#d9d9d9',
  comm: '#fa8c16',
  title: '#1f1f1f',
  muted: '#8c8c8c',
  faint: '#bfbfbf',
  divider: '#f0f0f0'
}
const EXPORT_LEGEND = [
  { label: '已完成', color: EXPORT_COLORS.done, shape: 'dot' },
  { label: '执行中', color: EXPORT_COLORS.active, shape: 'dot' },
  { label: '流转中', color: EXPORT_COLORS.active, shape: 'line' },
  { label: '未开始', color: EXPORT_COLORS.pending, shape: 'dot' },
  { label: '沟通中', color: EXPORT_COLORS.comm, shape: 'dot' },
  { label: '已收回', color: EXPORT_COLORS.muted, shape: 'dot' }
]
const EXPORT_HEADER_H = 96
const EXPORT_FOOTER_H = 46
const EXPORT_PAD_X = 28
// 含节点明细时追加的表格：标题 34 + 表头 24 + 每行 22
const EXPORT_DETAIL_TITLE_H = 34
const EXPORT_DETAIL_HEAD_H = 24
const EXPORT_DETAIL_ROW_H = 22
const EXPORT_DETAIL_MAX_ROWS = 80
const EXPORT_DETAIL_COLS = [
  { key: 'name', label: '节点', flex: true },
  { key: 'status', label: '状态', width: 58 },
  { key: 'assignee', label: '处理人', width: 104 },
  { key: 'start', label: '开始时间', width: 112 },
  { key: 'end', label: '结束时间', width: 112 },
  { key: 'duration', label: '停留时长', width: 88 }
]
// 含明细时画布的最小宽度：保证固定列不被挤压
const EXPORT_DETAIL_MIN_W =
  EXPORT_DETAIL_COLS.reduce((sum, c) => sum + (c.width || 0), 0) + 120 + 16 + EXPORT_PAD_X * 2

// 粗估文字宽度：中文/全角按 1em，其余按 0.56em。只用于排版避让，不追求精确
function estimateTextWidth(text, fontSize) {
  let units = 0
  for (const ch of String(text || '')) {
    units += /[\u2e80-\u9fff\uff00-\uffef]/.test(ch) ? 1 : 0.56
  }
  return units * fontSize
}

// 超宽文字按估算宽度截断并补省略号，保证导出图不出血
function clampText(text, maxWidth, fontSize) {
  const src = String(text || '')
  if (maxWidth <= 0) return ''
  if (estimateTextWidth(src, fontSize) <= maxWidth) return src
  let out = src
  while (out.length > 1 && estimateTextWidth(`${out}…`, fontSize) > maxWidth) {
    out = out.slice(0, -1)
  }
  return `${out}…`
}

// 明细表格的数据源：取全部节点，不受界面「只看有沟通的节点」筛选影响
function exportDetailRows() {
  const colorOf = {
    orange: EXPORT_COLORS.comm,
    green: EXPORT_COLORS.done,
    processing: EXPORT_COLORS.active
  }
  return (data.value?.nodes || []).map((n) => ({
    name: n.name || n.nodeId || '',
    status: statusText(n),
    statusColor: colorOf[statusColor(n)] || EXPORT_COLORS.muted,
    assignee: n.assignees && n.assignees.length ? n.assignees.join('、') : '—',
    start: n.startTime ? fmtShort(n.startTime) : '—',
    end: n.endTime ? fmtShort(n.endTime) : '—',
    duration: duration(n.durationInMillis, n.startTime)
  }))
}

function detailTableHeight(rows, overflow) {
  return (
    EXPORT_DETAIL_TITLE_H +
    EXPORT_DETAIL_HEAD_H +
    rows.length * EXPORT_DETAIL_ROW_H +
    (overflow ? 18 : 0) +
    10
  )
}

// 把节点明细画成表格：与界面列一致（节点/状态/处理人/开始/结束/停留时长）
function appendDetailTable(outer, { x, y, width, rows, total, overflow }) {
  const g = svgNode('g')
  const innerX = x + 8
  const innerW = width - 16
  const fixedSum = EXPORT_DETAIL_COLS.reduce((sum, c) => sum + (c.width || 0), 0)
  const nameW = Math.max(120, innerW - fixedSum)

  g.appendChild(
    svgNode('line', {
      x1: x,
      y1: y + 0.5,
      x2: x + width,
      y2: y + 0.5,
      stroke: EXPORT_COLORS.divider,
      'stroke-width': 1
    })
  )
  g.appendChild(
    svgNode(
      'text',
      { x: innerX, y: y + 21, 'font-size': 12, 'font-weight': 600, fill: EXPORT_COLORS.title },
      `节点执行明细（${total} 个节点${overflow ? `，仅列前 ${rows.length} 个` : ''}）`
    )
  )

  const headY = y + EXPORT_DETAIL_TITLE_H
  g.appendChild(
    svgNode('rect', {
      x,
      y: headY,
      width,
      height: EXPORT_DETAIL_HEAD_H,
      fill: '#fafafa'
    })
  )
  let colX = innerX
  EXPORT_DETAIL_COLS.forEach((col) => {
    const cw = col.flex ? nameW : col.width
    g.appendChild(
      svgNode(
        'text',
        { x: colX + 2, y: headY + 16, 'font-size': 11, fill: EXPORT_COLORS.muted },
        col.label
      )
    )
    colX += cw
  })

  rows.forEach((row, i) => {
    const ry = headY + EXPORT_DETAIL_HEAD_H + i * EXPORT_DETAIL_ROW_H
    if (i % 2 === 1) {
      g.appendChild(svgNode('rect', { x, y: ry, width, height: EXPORT_DETAIL_ROW_H, fill: '#fcfcfc' }))
    }
    let cx = innerX
    EXPORT_DETAIL_COLS.forEach((col) => {
      const cw = col.flex ? nameW : col.width
      const text = clampText(row[col.key], cw - 6, 11)
      const attrs = { x: cx + 2, y: ry + 15, 'font-size': 11, fill: '#434343' }
      if (col.key === 'status') attrs.fill = row.statusColor
      g.appendChild(svgNode('text', attrs, text))
      cx += cw
    })
    g.appendChild(
      svgNode('line', {
        x1: x,
        y1: ry + EXPORT_DETAIL_ROW_H - 0.5,
        x2: x + width,
        y2: ry + EXPORT_DETAIL_ROW_H - 0.5,
        stroke: '#f5f5f5',
        'stroke-width': 1
      })
    )
  })

  if (overflow) {
    g.appendChild(
      svgNode(
        'text',
        {
          x: innerX,
          y: headY + EXPORT_DETAIL_HEAD_H + rows.length * EXPORT_DETAIL_ROW_H + 13,
          'font-size': 11,
          fill: EXPORT_COLORS.faint
        },
        `… 另有 ${total - rows.length} 个节点未列出，完整明细请查看页面表格`
      )
    )
  }
  outer.appendChild(g)
}

function svgNode(tag, attrs, text) {
  const el = document.createElementNS(SVG_NS, tag)
  if (attrs) {
    Object.keys(attrs).forEach((key) => {
      if (attrs[key] !== null && attrs[key] !== undefined) el.setAttribute(key, String(attrs[key]))
    })
  }
  if (text !== undefined && text !== null) el.textContent = String(text)
  return el
}

// 页面样式全在组件 CSS 里，克隆后逐个元素把计算样式内联，脱离页面才能保持原样
function inlineSvgStyles(srcSvg, clone) {
  const srcEls = srcSvg.querySelectorAll('*')
  const dstEls = clone.querySelectorAll('*')
  for (let i = 0; i < srcEls.length && i < dstEls.length; i += 1) {
    const src = srcEls[i]
    const dst = dstEls[i]
    if (!dst.style) continue
    const cs = window.getComputedStyle(src)
    SVG_INLINE_PROPS.forEach((prop) => {
      const value = cs.getPropertyValue(prop)
      if (value) dst.style.setProperty(prop, value)
    })
    // 静态图里动画没有意义：执行中呼吸轮廓固定为中间态，流转中的边保留虚线但不再流动
    if (src.classList.contains('trace-pulse')) {
      dst.style.setProperty('opacity', '0.28')
    }
    if (src.classList.contains('trace-edge-active')) {
      dst.style.setProperty('stroke-dasharray', '7 5')
    }
    dst.removeAttribute('class')
  }
}

function exportBaseName() {
  const d = data.value || {}
  return safeFileName(d.processTitle) || safeFileName(props.processInstanceId) || '流程跟踪图'
}

// 组装导出画布：标题栏（流程名/状态/发起信息/节点统计）+ 流程图 + 图例
// SVG 与 PNG 共用同一份画布，保证两种格式内容完全一致
function buildExportFigure(options = {}) {
  const withDetail = !!options.withDetail
  const host = canvasRef.value
  const srcSvg = host ? host.querySelector('svg.trace-svg') : null
  const d = data.value
  if (!d || !d.diagramAvailable || !srcSvg) return null

  const clone = srcSvg.cloneNode(true)
  inlineSvgStyles(srcSvg, clone)
  ;['width', 'height', 'viewBox', 'class', 'style'].forEach((attr) => clone.removeAttribute(attr))

  // 图形区域按内容包围盒裁剪，BPMN 模型自带的画布留白不该带进导出图
  const box = contentBox.value
  const padTop = 28
  // 节点下方的名称标签会超出节点边框，底部多留一些
  const padBottom = 44
  let contentW = Math.max(1, Math.round(d.diagramWidth || 1))
  let contentH = Math.max(1, Math.round(d.diagramHeight || 1))
  let offsetX = 0
  let offsetY = 0
  if (box) {
    contentW = Math.max(1, Math.ceil(box.maxX - box.minX))
    contentH = Math.max(1, Math.ceil(box.maxY - box.minY))
    offsetX = -box.minX
    offsetY = -box.minY
  }
  const areaW = contentW + EXPORT_PAD_X * 2
  const areaH = contentH + padTop + padBottom

  const title = d.processTitle || '流程跟踪'
  const stateLabel = d.state === 'RUNNING' ? '进行中' : '已完成'
  const stampText = `导出 ${dayjs().format('YYYY-MM-DD HH:mm')}`
  const legendWidth = EXPORT_LEGEND.reduce(
    (sum, item) => sum + 16 + 7 + estimateTextWidth(item.label, 12) + 20,
    0
  )
  const chipW = estimateTextWidth(stateLabel, 11) + 18
  // 画布至少要放得下标题、状态标签与图例，否则窄流程图会把文字挤到图外
  const allRows = withDetail ? exportDetailRows() : []
  const detailRows = allRows.slice(0, EXPORT_DETAIL_MAX_ROWS)
  const detailOverflow = allRows.length > detailRows.length
  const detailH = withDetail ? detailTableHeight(detailRows, detailOverflow) : 0

  const width = Math.max(
    areaW,
    Math.ceil(chipW + estimateTextWidth(title, 18) + EXPORT_PAD_X * 2 + 40),
    Math.ceil(legendWidth + EXPORT_PAD_X * 2 + (stampText ? estimateTextWidth(stampText, 11) + 24 : 0)),
    withDetail ? EXPORT_DETAIL_MIN_W : 0,
    380
  )
  const height = EXPORT_HEADER_H + areaH + detailH + EXPORT_FOOTER_H

  const outer = svgNode('svg', {
    xmlns: SVG_NS,
    'xmlns:xlink': 'http://www.w3.org/1999/xlink',
    width,
    height,
    viewBox: `0 0 ${width} ${height}`
  })
  const fontFamily = window.getComputedStyle(srcSvg).fontFamily
  if (fontFamily) outer.style.fontFamily = fontFamily
  outer.appendChild(svgNode('title', null, `${title}（流程跟踪）`))
  // 白底：避免在深色背景的看图器里文字/线条看不清
  outer.appendChild(svgNode('rect', { x: 0, y: 0, width, height, fill: '#ffffff' }))

  // ---- 流程图（水平居中，垂直紧跟标题栏） ----
  const diagram = svgNode('g', {
    transform: `translate(${(width - contentW) / 2 + offsetX}, ${EXPORT_HEADER_H + padTop + offsetY})`
  })
  while (clone.firstChild) diagram.appendChild(clone.firstChild)
  outer.appendChild(diagram)

  // ---- 标题栏 ----
  const header = svgNode('g')
  const timestampW = stampText ? estimateTextWidth(stampText, 11) : 0
  const titleMaxW = width - EXPORT_PAD_X * 2 - chipW - 14 - (width >= 560 ? timestampW + 24 : 0)
  const titleText = clampText(title, titleMaxW, 18)

  header.appendChild(
    svgNode(
      'text',
      { x: EXPORT_PAD_X, y: 38, 'font-size': 18, 'font-weight': 600, fill: EXPORT_COLORS.title },
      titleText
    )
  )
  const chipX = EXPORT_PAD_X + estimateTextWidth(titleText, 18) + 12
  header.appendChild(
    svgNode('rect', {
      x: chipX,
      y: 22,
      width: chipW,
      height: 21,
      rx: 10.5,
      fill: d.state === 'RUNNING' ? 'rgba(22,119,255,0.10)' : 'rgba(82,196,26,0.12)'
    })
  )
  header.appendChild(
    svgNode(
      'text',
      {
        x: chipX + chipW / 2,
        y: 36,
        'font-size': 11,
        'text-anchor': 'middle',
        fill: d.state === 'RUNNING' ? EXPORT_COLORS.active : '#389e0d'
      },
      stateLabel
    )
  )
  if (width >= 560) {
    header.appendChild(
      svgNode(
        'text',
        { x: width - EXPORT_PAD_X, y: 36, 'font-size': 11, 'text-anchor': 'end', fill: EXPORT_COLORS.faint },
        stampText
      )
    )
  }

  // 发起信息
  const metaParts = [`发起人 ${d.startUserId || '-'}`, `发起于 ${fmt(d.startTime)}`]
  if (d.endTime) metaParts.push(`结束于 ${fmt(d.endTime)}`)
  else metaParts.push(`已运行 ${duration(d.durationInMillis, d.startTime)}`)
  if (d.currentNodeNames && d.currentNodeNames.length) {
    metaParts.push(`当前节点：${d.currentNodeNames.join('、')}`)
  }
  // 当前节点较多时整行会超出画布，从末尾逐项裁掉，保证导出图不出血
  const metaMaxW = width - EXPORT_PAD_X * 2
  while (
    metaParts.length > 1 &&
    estimateTextWidth(metaParts.join('　·　'), 12) > metaMaxW
  ) {
    metaParts.pop()
  }
  let metaText = metaParts.join('　·　')
  if (estimateTextWidth(metaText, 12) > metaMaxW) {
    while (metaText.length > 1 && estimateTextWidth(`${metaText}…`, 12) > metaMaxW) {
      metaText = metaText.slice(0, -1)
    }
    metaText = `${metaText}…`
  }
  header.appendChild(
    svgNode('text', { x: EXPORT_PAD_X, y: 62, 'font-size': 12, fill: EXPORT_COLORS.muted }, metaText)
  )

  // 节点统计
  const stats = d.stats || {}
  const statParts = [
    { label: '已完成', value: stats.completed || 0, color: EXPORT_COLORS.done },
    { label: '执行中', value: stats.active || 0, color: EXPORT_COLORS.active },
    { label: '未开始', value: stats.pending || 0, color: EXPORT_COLORS.pending },
    { label: '沟通任务', value: stats.commTotal || 0, color: EXPORT_COLORS.comm }
  ]
  const statsText = svgNode('text', { x: EXPORT_PAD_X, y: 84, 'font-size': 12 })
  let first = true
  statParts.forEach((item) => {
    if (!first) statsText.appendChild(svgNode('tspan', { fill: '#d9d9d9' }, '　  '))
    first = false
    statsText.appendChild(svgNode('tspan', { fill: EXPORT_COLORS.muted }, `${item.label} `))
    statsText.appendChild(
      svgNode('tspan', { fill: item.color, 'font-weight': 600 }, String(item.value))
    )
  })
  header.appendChild(statsText)
  header.appendChild(
    svgNode('line', {
      x1: 0,
      y1: EXPORT_HEADER_H - 1,
      x2: width,
      y2: EXPORT_HEADER_H - 1,
      stroke: EXPORT_COLORS.divider,
      'stroke-width': 1
    })
  )
  outer.appendChild(header)

  // ---- 节点明细（可选） ----
  if (withDetail) {
    appendDetailTable(outer, {
      x: EXPORT_PAD_X,
      y: EXPORT_HEADER_H + areaH,
      width: width - EXPORT_PAD_X * 2,
      rows: detailRows,
      total: allRows.length,
      overflow: detailOverflow
    })
  }

  // ---- 图例 ----
  const footer = svgNode('g')
  const footerTop = EXPORT_HEADER_H + areaH + detailH
  footer.appendChild(
    svgNode('line', {
      x1: 0,
      y1: footerTop + 0.5,
      x2: width,
      y2: footerTop + 0.5,
      stroke: EXPORT_COLORS.divider,
      'stroke-width': 1
    })
  )
  let lx = EXPORT_PAD_X
  const ly = footerTop + 30
  EXPORT_LEGEND.forEach((item) => {
    if (item.shape === 'line') {
      // 与页面一致：流转中用虚线短线段表示，而不是实心色块
      footer.appendChild(
        svgNode('line', {
          x1: lx,
          y1: ly - 4,
          x2: lx + 16,
          y2: ly - 4,
          stroke: item.color,
          'stroke-width': 2.2,
          'stroke-dasharray': '4 3',
          'stroke-linecap': 'round'
        })
      )
    } else {
      footer.appendChild(
        svgNode('circle', { cx: lx + 5, cy: ly - 4, r: 4.5, fill: item.color })
      )
    }
    lx += 16 + 7
    footer.appendChild(svgNode('text', { x: lx, y: ly, 'font-size': 12, fill: '#595959' }, item.label))
    lx += estimateTextWidth(item.label, 12) + 20
  })
  outer.appendChild(footer)

  return {
    svg: outer,
    width,
    height,
    markup: `<?xml version="1.0" encoding="UTF-8"?>\n${new XMLSerializer().serializeToString(outer)}`,
    fileNameBase: exportBaseName()
  }
}

// 把导出的 SVG 栅格化成 PNG：2 倍图保证贴进文档后依然清晰，超大流程图按上限降采样
function svgMarkupToPngBlob(markup, width, height) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(new Blob([markup], { type: 'image/svg+xml;charset=utf-8' }))
    const cleanup = () => URL.revokeObjectURL(url)
    const img = new Image()
    img.onload = () => {
      try {
        // 2 倍图更清晰；超大流程图按 4096px 上限等比缩小，避免超出画布尺寸限制
        const scale = Math.min(2, 4096 / Math.max(1, width), 4096 / Math.max(1, height))
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(width * scale))
        canvas.height = Math.max(1, Math.round(height * scale))
        const ctx = canvas.getContext('2d')
        ctx.fillStyle = '#ffffff'
        ctx.fillRect(0, 0, canvas.width, canvas.height)
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
        cleanup()
        canvas.toBlob(
          (blob) => (blob ? resolve(blob) : reject(new Error('PNG 编码失败'))),
          'image/png'
        )
      } catch (e) {
        cleanup()
        reject(e)
      }
    }
    img.onerror = () => {
      cleanup()
      reject(new Error('SVG 渲染失败，可能含不支持的样式'))
    }
    img.src = url
  })
}

function onExportMenu({ key }) {
  exportFigure(key)
}

async function exportFigure(kind) {
  const withDetail = kind === 'detail'
  const figure = buildExportFigure({ withDetail })
  if (!figure) {
    message.warning('当前没有可导出的流程图')
    return
  }
  const stamp = dayjs().format('YYYYMMDD-HHmmss')
  const wantSvg = kind === 'svg' || kind === 'both' || withDetail
  const wantPng = kind === 'png' || kind === 'both' || withDetail
  const suffix = withDetail ? '（含节点明细）' : ''
  exporting.value = true
  try {
    if (wantSvg) {
      downloadBlob(
        new Blob([figure.markup], { type: 'image/svg+xml;charset=utf-8' }),
        `${figure.fileNameBase}-${stamp}.svg`
      )
    }
    if (wantPng) {
      const png = await svgMarkupToPngBlob(figure.markup, figure.width, figure.height)
      downloadBlob(png, `${figure.fileNameBase}-${stamp}.png`)
    }
    message.success(
      wantSvg && wantPng
        ? `已导出 SVG 与 PNG${suffix}`
        : `已导出 ${wantSvg ? 'SVG' : 'PNG'} 流程图${suffix}`
    )
  } catch (e) {
    message.error('导出失败：' + (e && e.message ? e.message : '未知错误'))
  } finally {
    exporting.value = false
  }
}

// 一键收起 / 展开概览与流程图：收起后明细表吃掉整块高度，能多显示若干行
function toggleCollapse() {
  collapsed.value = !collapsed.value
  nextTick(() => {
    measureDetail()
    if (!collapsed.value) {
      // 展开后画布尺寸恢复，重新按内容居中（用户手动调整过则保持原视图）
      setTimeout(() => {
        if (!userMoved) center()
      }, 30)
    }
  })
}

function isEvent(node) {
  return EVENT_TYPES.includes(node.type)
}

function isGateway(node) {
  return node.type.indexOf('Gateway') >= 0
}

function cx(node) {
  return node.x + node.width / 2
}

function cy(node) {
  return node.y + node.height / 2
}

function diamondPoints(node) {
  return `${cx(node)},${node.y} ${node.x + node.width},${cy(node)} ${cx(node)},${node.y + node.height} ${node.x},${cy(node)}`
}

function gatewayMark(node) {
  if (node.type === 'parallelGateway') return '+'
  if (node.type === 'inclusiveGateway') return '○'
  if (node.type === 'eventBasedGateway') return '◇'
  return '×'
}

function nodeFill(node) {
  if (node.commLocked) return '#fff7e6'
  if (node.status === 'COMPLETED') return '#f6ffed'
  if (node.status === 'ACTIVE') return '#e6f4ff'
  return '#fafafa'
}

function nodeStroke(node) {
  if (node.commLocked) return '#fa8c16'
  if (node.status === 'COMPLETED') return '#52c41a'
  if (node.status === 'ACTIVE') return '#1677ff'
  return '#d9d9d9'
}

function nodeTextColor(node) {
  if (node.status === 'PENDING' && !node.commLocked) return '#8c8c8c'
  if (node.commLocked) return '#ad4e00'
  if (node.status === 'ACTIVE') return '#0958d9'
  return '#135200'
}

function shortName(name, max = 7) {
  if (!name) return ''
  return name.length > max ? `${name.slice(0, max)}…` : name
}

function subText(node) {
  if (node.assignees && node.assignees.length) return node.assignees[0]
  if (node.status === 'COMPLETED') return '已完成'
  if (node.status === 'ACTIVE') return node.commLocked ? '沟通中' : '处理中'
  return '未开始'
}

function statusText(node) {
  if (node.commLocked) return '沟通中'
  if (node.status === 'COMPLETED') return '已完成'
  if (node.status === 'ACTIVE') return '执行中'
  return '未开始'
}

function statusColor(node) {
  if (node.commLocked) return 'orange'
  if (node.status === 'COMPLETED') return 'green'
  if (node.status === 'ACTIVE') return 'processing'
  return 'default'
}

// ===== 沟通明细（行内展开） =====
function rowExpandable(record) {
  return !!(record && record.commTasks && record.commTasks.length)
}

function onExpand(expanded, record) {
  expandedKeys.value = expanded ? [record.nodeId] : []
}

function toggleExpandRow(record) {
  if (!rowExpandable(record)) return
  const isOpen = expandedKeys.value.includes(record.nodeId)
  expandedKeys.value = isOpen ? [] : [record.nodeId]
  if (!isOpen) {
    // 展开后行高变化，重新滚动到该行
    nextTick(() => {
      const row = findDetailRow(record.nodeId)
      if (row) scrollDetailRowIntoView(row)
    })
  }
}

function toggleOnlyComm(checked) {
  onlyComm.value = checked === true
  // 过滤后原展开行可能已被隐藏，统一收起
  expandedKeys.value = []
  nextTick(() => measureDetail())
}

function timeOf(task) {
  const value = task.createTime || task.startTime
  return value ? dayjs(value).valueOf() : 0
}

// 图上沟通角标：待反馈优先（橙），其次已收回（灰），最后才是已反馈/进行中的沟通（紫）。
// 收回的记录在引擎里没有任务了，若仍按条数显示“N沟通”，图上会看起来像还在推进。
function commBadge(node) {
  const pending = node.pendingCommCount || 0
  const withdrawn = node.withdrawnCommCount || 0
  const total = (node.commTasks || []).length
  if (pending > 0) return { width: 30, fill: '#fa8c16', text: `${pending}待` }
  if (withdrawn > 0) return { width: 34, fill: '#8c8c8c', text: `${withdrawn}收回` }
  return { width: 30, fill: '#722ed1', text: `${total}沟通` }
}

// 悬停浮层里的沟通汇总，同样区分待反馈与已收回
function commSummaryText(node) {
  const parts = []
  if (node.pendingCommCount) parts.push(`待反馈 ${node.pendingCommCount}`)
  if (node.withdrawnCommCount) parts.push(`已收回 ${node.withdrawnCommCount}`)
  return parts.length ? `（${parts.join(' / ')}）` : ''
}

function typeText(type) {
  if (type === 'USER') return '个人'
  if (type === 'DEPT') return '部门'
  return ''
}

// 沟通条数标签：已收回的记录同样计入总数（它们已从引擎历史里消失，由业务表补回），
// 单列「已收回」数量，避免把收回的沟通误读成还在推进。
function commTagText(record) {
  const total = (record.commTasks || []).length
  const withdrawn = record.withdrawnCommCount || 0
  const base = record.pendingCommCount ? `沟通待反馈 ${record.pendingCommCount}` : `沟通 ${total} 条`
  return withdrawn ? `${base}（已收回 ${withdrawn}）` : base
}

// 同一 rootTaskId 视为一次沟通会话；追问（有 parentCommTaskId）在会话内按时间排在后面并缩进
function commSessions(record) {
  const tasks = (record.commTasks || []).slice().sort((a, b) => timeOf(a) - timeOf(b))
  const groups = new Map()
  tasks.forEach((task) => {
    const key = task.rootTaskId || task.parentCommTaskId || task.taskId
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(task)
  })
  const result = []
  groups.forEach((items, key) => {
    const first = items[0]
    const pending = items.some((t) => t.pending || t.status === 'PENDING')
    const replied = items.some(
      (t) => t.status === 'REPLIED' || t.status === 'FINISHED' || !!t.replyContent
    )
    result.push({
      key,
      targetName: first.targetName || first.assignee || '—',
      targetTypeText: typeText(first.targetType),
      statusText: pending ? '待反馈' : replied ? '已反馈' : '已收回',
      dotClass: pending ? 'is-pending' : replied ? 'is-replied' : 'is-withdrawn',
      items
    })
  })
  return result
}

// 约 3 行（90 字）以上才折叠，短内容不出现无意义的「展开」
function needClamp(task) {
  return (task.content || '').length > 90
}

function isTextOpen(taskId) {
  return !!openTexts.value[taskId]
}

function toggleText(taskId) {
  openTexts.value = { ...openTexts.value, [taskId]: !openTexts.value[taskId] }
}

function fmtShort(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'
}

// 已反馈：业务状态为已反馈，或已有回复内容 / 回复时间
function isReplied(task) {
  return (
    task.status === 'REPLIED' ||
    task.status === 'FINISHED' ||
    !!task.replyContent ||
    !!task.replyTime
  )
}

// 反馈块显示条件：已反馈就显示，即使历史数据缺反馈内容也给出占位，保证反馈时间可见
function showReply(task) {
  if (!isReplied(task)) return false
  if (task.status === 'WITHDRAWN' && !task.replyContent) return false
  return true
}

function replyUser(task) {
  return task.replyUserId || task.targetName || task.assignee || '沟通对象'
}

// 反馈时间：优先业务表记录，历史数据缺失时用沟通任务结束时间兜底
function replyTimeOf(task) {
  return task.replyTime || task.endTime || null
}

function replyCost(task) {
  const start = task.createTime || task.startTime
  const repliedAt = replyTimeOf(task)
  if (!repliedAt || !start) return ''
  const cost = dayjs(repliedAt).valueOf() - dayjs(start).valueOf()
  return cost > 0 ? `（耗时 ${duration(cost)}）` : ''
}

function edgePoints(edge) {
  return (edge.waypoints || []).map((p) => `${p.x},${p.y}`).join(' ')
}

function fmt(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—'
}

function duration(ms, startTime) {
  let v = ms
  if ((v === null || v === undefined) && startTime) {
    v = Date.now() - dayjs(startTime).valueOf()
  }
  if (v === null || v === undefined) return '—'
  const minutes = Math.floor(v / 60000)
  if (minutes < 1) return '不足 1 分钟'
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时 ${minutes % 60} 分`
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`
}

function rowClassName(record) {
  return record.nodeId === selectedId.value ? 'trace-row-active' : ''
}

// 直接操作行 DOM：antd 表格在 dataSource / rowClassName 引用未变时可能跳过重渲染，
// 仅靠 rowClassName 会出现「状态已变但行的 class 没变」的情况
function highlightRow(row) {
  if (activeRowEl && activeRowEl !== row) {
    activeRowEl.classList.remove('trace-row-active', 'trace-row-flash')
  }
  activeRowEl = row
  row.classList.add('trace-row-active')
  // 重触发闪烁动画：先移除，强制回流后再加
  row.classList.remove('trace-row-flash')
  void row.getBoundingClientRect()
  row.classList.add('trace-row-flash')
  clearTimeout(rowFlashTimer)
  rowFlashTimer = setTimeout(() => {
    row.classList.remove('trace-row-flash')
  }, 1700)
}

function customRow(record) {
  return {
    onClick: (event) => {
      // 展开箭头交给表格处理，否则刚收起又会被行点击重新展开
      if (event?.target?.closest?.('.ant-table-row-expand-icon')) return
      selectedId.value = record.nodeId
      // 行点击与点节点一致：有沟通则展开该行
      if (rowExpandable(record)) expandedKeys.value = [record.nodeId]
      if (event?.currentTarget) highlightRow(event.currentTarget)
      // 反向定位：表格行被点击时，若该节点不在画布可视区内，平移过去
      panNodeIntoCanvas(record)
    }
  }
}

// 明细区高度由布局分配，表格按「可用高度 - 标题 - 表头」取表体高度，表头随之固定
function measureDetail() {
  const el = detailRef.value
  if (!el) return
  const cs = window.getComputedStyle(el)
  const padV = (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0)
  let titleH = 0
  // 标题行现在包含沟通统计与过滤开关，按整行高度计算
  const titleEl = el.querySelector('.trace-detail-head') || el.querySelector('.drawer-section-title')
  if (titleEl) {
    const ts = window.getComputedStyle(titleEl)
    titleH =
      titleEl.getBoundingClientRect().height +
      (parseFloat(ts.marginTop) || 0) +
      (parseFloat(ts.marginBottom) || 0)
  }
  const avail = el.clientHeight - padV - titleH - DETAIL_HEADER_HEIGHT - 14
  const next = Math.max(DETAIL_MIN_SCROLL, Math.floor(avail))
  // 只有明显变化时才更新，避免观察器与表格高度互相触发
  if (Math.abs(next - tableScrollY.value) > 1) {
    tableScrollY.value = next
  }
}

// 画布尺寸就绪或变化后自动回中：弹窗展开、全屏切换、窗口缩放都会改变画布高度，
// 只在用户没手动拖过/缩放过时才回中，避免把手动调整的视图拽回去
let canvasObserver = null
function observeCanvas() {
  disconnectCanvas()
  const el = canvasRef.value
  if (!el || typeof ResizeObserver === 'undefined') return
  canvasObserver = new ResizeObserver(() => {
    if (!props.open || !data.value || userMoved || collapsed.value) return
    center()
  })
  canvasObserver.observe(el)
}

function disconnectCanvas() {
  if (canvasObserver) {
    canvasObserver.disconnect()
    canvasObserver = null
  }
}

function observeDetail() {
  disconnectDetail()
  const el = detailRef.value
  if (!el) return
  measureDetail()
  if (typeof ResizeObserver === 'undefined') return
  detailObserver = new ResizeObserver(() => measureDetail())
  detailObserver.observe(el)
}

function disconnectDetail() {
  if (detailObserver) {
    detailObserver.disconnect()
    detailObserver = null
  }
}

// 明细表的行由 antd 渲染，用 data-row-key 精确匹配，避免节点 id 含特殊字符时选择器失效
function findDetailRow(nodeId) {
  const root = tableRef.value?.$el || detailRef.value
  if (!root) return null
  const rows = root.querySelectorAll('tr[data-row-key]')
  for (let i = 0; i < rows.length; i += 1) {
    if (rows[i].getAttribute('data-row-key') === nodeId) return rows[i]
  }
  // 兜底：某些版本不写 data-row-key，按节点在当前数据源中的顺序取行
  const index = tableData.value.findIndex((n) => n.nodeId === nodeId)
  if (index < 0) return null
  return root.querySelectorAll('tbody tr.ant-table-row')[index] || null
}

function isScrollable(el, axis) {
  if (!el || el.nodeType !== 1) return false
  const style = window.getComputedStyle(el)
  const overflow = axis === 'y' ? style.overflowY : style.overflowX
  if (!/(auto|scroll|overlay)/.test(overflow)) return false
  return axis === 'y'
    ? el.scrollHeight - el.clientHeight > 1
    : el.scrollWidth - el.clientWidth > 1
}

// 逐个可滚动祖先滚动，兼容弹窗 body 的纵向滚动与表格的横向滚动
function scrollDetailRowIntoView(row) {
  const PAD = 12
  let el = row.parentElement
  while (el && el !== document.body) {
    if (isScrollable(el, 'y')) {
      const rowRect = row.getBoundingClientRect()
      const boxRect = el.getBoundingClientRect()
      const needScroll = rowRect.top < boxRect.top + PAD || rowRect.bottom > boxRect.bottom - PAD
      if (needScroll) {
        // 滚动后让目标行落在容器偏上位置（约 1/3 处），定位更醒目且不贴边
        const offset = rowRect.top - boxRect.top
        const target = el.scrollTop + offset - el.clientHeight / 3
        const max = el.scrollHeight - el.clientHeight
        el.scrollTo({ top: Math.max(0, Math.min(max, Math.round(target))), behavior: 'smooth' })
      }
    }
    el = el.parentElement
  }
}

// 节点整体不在画布可视范围内时，平移使其居中
function panNodeIntoCanvas(node) {
  const el = canvasRef.value
  if (!el || !data.value || !data.value.diagramAvailable) return
  const cw = el.clientWidth
  const ch = el.clientHeight
  const margin = 24
  const nx = panX.value + node.x * scale.value
  const ny = panY.value + node.y * scale.value
  const nw = node.width * scale.value
  const nh = node.height * scale.value
  const visible =
    nx >= margin && ny >= margin && nx + nw <= cw - margin && ny + nh <= ch - margin
  if (visible) return
  userMoved = true
  panX.value = Math.round(cw / 2 - (node.x + node.width / 2) * scale.value)
  panY.value = Math.round(ch / 2 - (node.y + node.height / 2) * scale.value)
  clampPan()
}

// 限制平移范围：内容至少保留 PAN_MARGIN 可见，避免拖出画布后找不到图
function clampPan() {
  const el = canvasRef.value
  if (!el) return
  const cw = el.clientWidth
  const ch = el.clientHeight
  const w = svgWidth.value
  const h = svgHeight.value
  panX.value = Math.round(Math.min(cw - PAN_MARGIN, Math.max(PAN_MARGIN - w, panX.value)))
  panY.value = Math.round(Math.min(ch - PAN_MARGIN, Math.max(PAN_MARGIN - h, panY.value)))
}

// 默认居中：内容中心对齐画布中心；内容超出画布时两侧均分溢出，可拖动查看
function center() {
  const el = canvasRef.value
  if (!el || !data.value) return
  // 收起状态画布不显示，尺寸为 0，此时不做居中，等展开后再算
  if (collapsed.value) return
  const cw = el.clientWidth
  const ch = el.clientHeight
  // 画布高度可能因弹窗展开动画/布局尚未就绪而为 0，此时算出的纵向偏移是错的
  if (!cw || !ch) {
    scheduleCenter()
    return
  }
  const box = contentBox.value
  if (box) {
    panX.value = Math.round(cw / 2 - ((box.minX + box.maxX) / 2) * scale.value)
    panY.value = Math.round(ch / 2 - ((box.minY + box.maxY) / 2) * scale.value)
  } else {
    panX.value = Math.round((cw - svgWidth.value) / 2)
    panY.value = Math.round((ch - svgHeight.value) / 2)
  }
  centerRetryCount = 0
  clampPan()
}

// 手动点「居中显示」：重置手动标志，之后画布尺寸变化仍会自动回中
function recenter() {
  userMoved = false
  center()
}

// 画布尺寸未就绪时延迟重试（最多几次，避免异常情况下无限循环）
let centerRetryTimer = null
let centerRetryCount = 0
function scheduleCenter() {
  if (centerRetryCount >= 8) return
  centerRetryCount += 1
  clearTimeout(centerRetryTimer)
  centerRetryTimer = setTimeout(() => {
    center()
    if (canvasRef.value && canvasRef.value.clientHeight) centerRetryCount = 0
  }, 60)
}

// 以画布内某点为锚点缩放（不传锚点则用画布中心），保持视觉中心不跳动
function applyScale(next, anchor) {
  const el = canvasRef.value
  if (!el) return
  const target = Math.min(MAX_SCALE, Math.max(MIN_SCALE, Number(next.toFixed(2))))
  if (target === scale.value) return
  const rect = el.getBoundingClientRect()
  const ax = anchor ? anchor.x : rect.width / 2
  const ay = anchor ? anchor.y : rect.height / 2
  const ratio = target / scale.value
  panX.value = ax - (ax - panX.value) * ratio
  panY.value = ay - (ay - panY.value) * ratio
  scale.value = target
  userMoved = true
  clampPan()
}

// 适应画布：宽、高分别算出可用比例，取较小值，保证整张图完整落在画布内
function fit() {
  const el = canvasRef.value
  if (!data.value || !el) return
  const cw = el.clientWidth
  const ch = el.clientHeight
  if (!cw || !ch) return
  // 四周留一点内边距，避免描边和箭头贴着画布边缘被裁切
  const padding = 16
  const box = contentBox.value
  const contentW = box ? box.maxX - box.minX : data.value.diagramWidth
  const contentH = box ? box.maxY - box.minY : data.value.diagramHeight
  if (!contentW || !contentH) return
  const next = Math.min((cw - padding * 2) / contentW, (ch - padding * 2) / contentH)
  scale.value = Math.min(MAX_SCALE, Math.max(MIN_SCALE, Number(next.toFixed(2))))
  userMoved = false
  center()
}

// 默认视图：100% 原始比例 + 画布居中
function resetZoom() {
  if (!data.value) return
  scale.value = 1
  userMoved = false
  center()
}

function zoomBy(delta) {
  applyScale(scale.value + delta, null)
}

function onWheel(event) {
  if (!data.value || !data.value.diagramAvailable || !canvasRef.value) return
  const rect = canvasRef.value.getBoundingClientRect()
  const delta = event.deltaY > 0 ? -0.1 : 0.1
  applyScale(scale.value + delta, {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  })
}

function onPointerDown(event) {
  if (!data.value || !data.value.diagramAvailable) return
  if (event.button !== undefined && event.button !== 0) return
  dragging.value = true
  dragMoved = false
  // 记录按下位置命中的节点，抬起时据此判定是否触发了点击
  const nodeEl = event.target?.closest?.('.trace-node')
  pressNodeId = nodeEl ? nodeEl.getAttribute('data-node-id') || '' : ''
  dragStart = { x: event.clientX, y: event.clientY, panX: panX.value, panY: panY.value }
  hideTip()
  clearTimeout(dragResetTimer)
  // 这里不能用 setPointerCapture：一旦捕获，后续 click 会被重定向到画布，
  // 节点上的点击处理就不会执行（表现为点节点没反应）。
  // 改为拖动期间在 window 上监听，效果相同且不影响点击。
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerUp)
}

function onPointerMove(event) {
  if (!dragging.value || !dragStart) return
  const dx = event.clientX - dragStart.x
  const dy = event.clientY - dragStart.y
  if (!dragMoved && Math.abs(dx) + Math.abs(dy) > 3) dragMoved = true
  if (!dragMoved) return
  userMoved = true
  panX.value = dragStart.panX + dx
  panY.value = dragStart.panY + dy
  clampPan()
}

function onPointerUp(event) {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)
  if (!dragging.value) return
  dragging.value = false
  dragStart = null
  const nodeId = pressNodeId
  pressNodeId = ''
  // 位移未超过阈值 => 视为点击，定位到对应明细行
  const isClick = !dragMoved
  dragResetTimer = setTimeout(() => {
    dragMoved = false
  }, 0)
  if (isClick && nodeId) {
    const node = (data.value?.nodes || []).find((n) => n.nodeId === nodeId)
    if (node) selectNode(node)
  }
}

// 点击节点：选中高亮 + 定位并高亮明细行；该节点有沟通时自动展开沟通会话
async function selectNode(node) {
  selectedId.value = node.nodeId
  // 开启「只看有沟通的节点」时目标节点可能被过滤掉，先退出过滤再定位
  if (onlyComm.value && !rowExpandable(node)) onlyComm.value = false
  if (rowExpandable(node)) expandedKeys.value = [node.nodeId]
  await nextTick()
  // 展开行会改变行高与表体滚动高度，等一帧再定位
  await nextTick()
  const row = findDetailRow(node.nodeId)
  if (!row) return
  highlightRow(row)
  scrollDetailRowIntoView(row)
}

function showTip(event, node) {
  if (dragging.value) return
  tipNode.value = node
  moveTip(event)
}

function moveTip(event) {
  if (!tipNode.value) return
  const pad = 14
  const width = 260
  const viewport = window.innerWidth
  let left = event.clientX + pad
  if (left + width > viewport - 12) {
    left = event.clientX - width - pad
  }
  tipStyle.value = { left: `${Math.max(8, left)}px`, top: `${event.clientY + pad}px` }
}

function hideTip() {
  tipNode.value = null
}

async function load() {
  if (!props.processInstanceId) return
  loading.value = true
  try {
    data.value = await wbApi.trace(props.processInstanceId)
    selectedId.value = ''
    expandedKeys.value = []
    openTexts.value = {}
    clearTimeout(rowFlashTimer)
    activeRowEl = null
    await nextTick()
    // 默认 100% 原始比例并居中；需要整图完整可见时用工具栏「适应画布」
    resetZoom()
    observeCanvas()
    observeDetail()
  } catch (e) {
    message.error(errorText(e))
    data.value = null
    disconnectDetail()
  } finally {
    loading.value = false
  }
}

// 弹窗展开动画结束后画布才有稳定尺寸，此时再居中一次
function onAfterOpenChange(isOpen) {
  if (isOpen && data.value) {
    nextTick(() => {
      center()
      observeCanvas()
      observeDetail()
    })
  }
}

watch(
  () => [props.open, props.processInstanceId],
  ([isOpen]) => {
    if (isOpen) {
      // 每次打开都回到全屏，避免上次缩小后本次打开仍是小窗
      isFullscreen.value = true
      // 默认展开概览与流程图
      collapsed.value = false
      load()
    } else {
      hideTip()
      exitCanvasFullscreen()
      // 弹窗关闭后内容被销毁，停止观察旧节点
      disconnectCanvas()
      disconnectDetail()
    }
  },
  { immediate: true }
)

// 画布尺寸变化后重新居中，保证「默认居中」在窗口缩放、移动端旋转后依然成立
function onWindowResize() {
  isMobile.value = window.innerWidth <= 768
  if (!props.open || !data.value) return
  clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => {
    userMoved = false
    center()
  }, 120)
}

onMounted(() => {
  window.addEventListener('resize', onWindowResize)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  document.addEventListener('webkitfullscreenchange', onFullscreenChange)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onWindowResize)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  document.removeEventListener('webkitfullscreenchange', onFullscreenChange)
  exitCanvasFullscreen()
  disconnectCanvas()
  disconnectDetail()
  clearTimeout(resizeTimer)
  clearTimeout(dragResetTimer)
  clearTimeout(rowFlashTimer)
  clearTimeout(centerRetryTimer)
  clearTimeout(canvasFsTimer)
})
</script>

<style scoped>
.trace-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  /* 给右上角的窗口缩放按钮 + 关闭按钮留出位置，避免长标题被压到按钮下面 */
  padding-right: 92px;
}
.trace-title-sub {
  font-size: 13px;
  font-weight: 400;
  color: rgba(0, 0, 0, 0.45);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
/* 窗口缩放按钮：紧贴在弹窗关闭图标左侧 */
.trace-window-btn {
  position: absolute;
  top: 12px;
  right: 52px;
  width: 32px;
  height: 32px;
  color: rgba(0, 0, 0, 0.45);
  z-index: 2;
}
.trace-window-btn:hover {
  color: #1677ff;
  background: rgba(0, 0, 0, 0.04);
}
.trace-overview {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid #eef0f3;
  border-radius: 10px;
  background: linear-gradient(135deg, #f7fbff 0%, #fbfdff 100%);
  flex-wrap: wrap;
}
.trace-overview-title {
  font-size: 16px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}
.trace-overview-meta {
  margin-top: 6px;
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}
.trace-overview-current {
  margin-top: 8px;
  font-size: 13px;
}
.trace-stats {
  display: flex;
  gap: 8px;
}
.trace-stat {
  min-width: 62px;
  padding: 6px 10px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #eef0f3;
  text-align: center;
}
.trace-stat b {
  display: block;
  font-size: 18px;
  line-height: 1.2;
}
.trace-stat span {
  font-size: 11px;
  color: rgba(0, 0, 0, 0.45);
}
.trace-stat.is-active b {
  color: #1677ff;
}
.trace-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  margin: 12px 0 8px;
  flex-wrap: wrap;
}
.trace-legend {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  display: inline-block;
}
.dot.done {
  background: #52c41a;
}
.dot.active {
  background: #1677ff;
}
/* 连线图例：用短线表示“已到达但下游仍在办理”的流转中连线 */
.dot.flow {
  width: 14px;
  height: 3px;
  border-radius: 2px;
  background: #1677ff;
}
.dot.pending {
  background: #d9d9d9;
}
.dot.comm {
  background: #fa8c16;
}
.dot.withdrawn {
  background: #8c8c8c;
}
.trace-zoom-text {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
  min-width: 38px;
  text-align: center;
}
.trace-zoom-text.is-clickable {
  cursor: pointer;
  border-radius: 4px;
  user-select: none;
  transition: all 0.2s;
}
.trace-zoom-text.is-clickable:hover {
  color: #1677ff;
  background: rgba(22, 119, 255, 0.08);
}
.trace-canvas {
  position: relative;
  overflow: hidden;
  flex: 0 0 auto;
  height: clamp(260px, 38vh, 460px);
  border: 1px dashed #e6e8eb;
  border-radius: 10px;
  background: #fcfdff;
  background-image: radial-gradient(#e9edf3 1px, transparent 1px);
  background-size: 16px 16px;
  cursor: grab;
  user-select: none;
  touch-action: none;
}
.trace-canvas.is-dragging {
  cursor: grabbing;
}
/* 画布右上角浮动工具条：缩放 / 适应画布 / 居中 / 刷新 / 全屏 */
.trace-canvas-tools {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 0;
  padding: 2px 4px;
  border-radius: 8px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  cursor: default;
  backdrop-filter: blur(2px);
}
.trace-canvas-tools .trace-tool-btn {
  width: 24px;
  min-width: 24px;
  height: 24px;
  padding: 0;
  line-height: 22px;
}
.trace-canvas-tools .trace-zoom-text {
  min-width: 38px;
  line-height: 24px;
  padding: 0 2px;
}
.trace-canvas-tools-split {
  width: 1px;
  height: 14px;
  margin: 0 2px;
  background: rgba(0, 0, 0, 0.1);
}
/* 全屏时画布铺满屏幕，工具条常显 */
.trace-canvas:fullscreen,
.trace-canvas:-webkit-full-screen {
  position: fixed;
  inset: 0;
  width: 100vw;
  height: 100vh;
  border: none;
  border-radius: 0;
  background-color: #fcfdff;
}
.trace-canvas:fullscreen .trace-canvas-tools {
  top: 16px;
  right: 16px;
}
.trace-stage {
  position: absolute;
  top: 0;
  left: 0;
  will-change: transform;
}
.trace-hint {
  color: rgba(0, 0, 0, 0.35);
}
.trace-svg {
  display: block;
}
/* 流转中：已到达但下游仍在办理，用流动虚线表示正在推进 */
.trace-edge-active {
  stroke-dasharray: 7 5;
  animation: trace-edge-flow 0.9s linear infinite;
}
@keyframes trace-edge-flow {
  from {
    stroke-dashoffset: 12;
  }
  to {
    stroke-dashoffset: 0;
  }
}
.trace-node {
  cursor: pointer;
}
.trace-label {
  font-size: 12px;
  font-family: inherit;
}
.trace-sublabel {
  font-size: 11px;
  font-family: inherit;
}
.trace-gateway-mark {
  font-size: 14px;
  font-weight: 700;
}
.trace-badge {
  font-size: 7px;
  fill: #fff;
  font-family: inherit;
}
.trace-comm-badge {
  font-size: 9px;
  fill: #fff;
  font-family: inherit;
}
.trace-pulse {
  opacity: 0.18;
  animation: trace-pulse 1.8s ease-out infinite;
}
@keyframes trace-pulse {
  0% {
    opacity: 0.35;
  }
  70% {
    opacity: 0.05;
  }
  100% {
    opacity: 0.35;
  }
}
.trace-tip {
  position: fixed;
  z-index: 1200;
  width: 260px;
  background: rgba(255, 255, 255, 0.98);
  border: 1px solid #e6e8eb;
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.14);
  padding: 10px 12px;
  pointer-events: none;
  font-size: 12px;
}
.trace-tip-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.trace-tip-name {
  font-size: 13px;
  font-weight: 600;
}
.trace-tip-status {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 9px;
  background: #fafafa;
  color: #8c8c8c;
}
.trace-tip-status.is-completed {
  background: #f6ffed;
  color: #389e0d;
}
.trace-tip-status.is-active {
  background: #e6f4ff;
  color: #0958d9;
}
.trace-tip-row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  line-height: 1.9;
}
.trace-tip-row span {
  color: rgba(0, 0, 0, 0.45);
}
.trace-tip-row b {
  font-weight: 500;
  text-align: right;
}
.trace-tip-comment {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed #eef0f3;
  color: #5c6672;
  line-height: 1.6;
  max-height: 48px;
  overflow: hidden;
}
.trace-tip-foot {
  margin-top: 6px;
  font-size: 11px;
  color: #1677ff;
}
/* 概览 + 工具栏 + 流程图：整体收起后高度归零，节点执行明细自动占满剩余空间 */
.trace-top {
  display: flex;
  flex-direction: column;
  flex: 0 0 auto;
  min-height: 0;
}
.trace-detail {
  flex: 1 1 auto;
  min-height: 0;
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.trace-detail-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.trace-collapse-btn {
  height: 24px;
  padding: 0 4px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
}
.trace-collapse-btn:hover {
  color: #1677ff;
}
.trace-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.trace-detail-head .drawer-section-title {
  margin-bottom: 0;
}
.trace-detail-stats {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
  color: rgba(0, 0, 0, 0.45);
}
.trace-detail-stats b {
  color: #fa8c16;
  font-weight: 500;
}
.trace-only-comm {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 0;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
  cursor: pointer;
  user-select: none;
}
.trace-comm-tag {
  cursor: pointer;
}
/* 弹窗内容为固定高度的三段式布局：概览 + 画布固定，明细表占剩余高度并内部滚动 */
.trace-body {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.trace-spin {
  height: 100%;
  min-height: 0;
}
:global(.trace-modal .ant-spin-nested-loading),
:global(.trace-modal .ant-spin-container) {
  height: 100%;
}
:global(.trace-modal .ant-modal-body) {
  height: calc(100vh - 150px);
  max-height: 820px;
  overflow: hidden;
}
/* 全屏模式：弹窗贴近视口上下沿，内容高度随之放大，不受 820px 上限约束 */
:global(.trace-modal-full .ant-modal-body) {
  height: calc(100vh - 116px);
  max-height: none;
}
:global(.trace-modal .trace-row-active td) {
  background: #eaf2ff !important;
}
:global(.trace-modal .trace-row-active td:first-child) {
  box-shadow: inset 3px 0 0 #1677ff;
}

@media (max-width: 768px) {
  .trace-overview {
    padding: 12px;
  }
  .trace-stats {
    width: 100%;
  }
  .trace-stat {
    flex: 1;
  }
  .trace-canvas {
    height: clamp(200px, 32vh, 320px);
  }
  .trace-canvas-tools {
    top: 6px;
    right: 6px;
    padding: 1px 2px;
  }
  .trace-canvas-tools .trace-tool-btn {
    width: 22px;
    min-width: 22px;
    height: 22px;
    line-height: 20px;
  }
  .trace-canvas-tools .trace-zoom-text {
    min-width: 34px;
    line-height: 22px;
    font-size: 11px;
  }
  .trace-collapse-label {
    display: none;
  }
  :global(.trace-modal .ant-modal-body) {
    height: calc(100vh - 96px);
  }
}
</style>

<!-- 定位行的闪烁提示：antd 渲染的行不带 scoped 属性，且 keyframes 在 scoped 中会被改名，故独立为全局样式 -->
<style>
.trace-modal .trace-row-flash td {
  animation: trace-row-flash 1.6s ease-out;
}
@keyframes trace-row-flash {
  0% {
    box-shadow: inset 0 0 0 9999px rgba(22, 119, 255, 0.28);
  }
  70% {
    box-shadow: inset 0 0 0 9999px rgba(22, 119, 255, 0.1);
  }
  100% {
    box-shadow: inset 0 0 0 9999px rgba(22, 119, 255, 0);
  }
}

/* 沟通明细展开区由 antd 内部渲染，不带父组件 scoped 属性，故使用全局样式 */
.trace-modal .ant-table-expanded-row > td {
  padding: 0 !important;
  background: #fbfcfe !important;
}
.trace-modal .trace-comm-panel {
  padding: 10px 14px 12px;
  border-left: 2px solid #e6f0ff;
}
.trace-modal .trace-comm-session + .trace-comm-session {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #e8ecf2;
}
.trace-modal .trace-comm-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 12px;
}
.trace-modal .trace-comm-target {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
}
.trace-modal .trace-comm-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  flex: 0 0 auto;
}
.trace-modal .trace-comm-dot.is-pending {
  background: #fa8c16;
}
.trace-modal .trace-comm-dot.is-replied {
  background: #52c41a;
}
.trace-modal .trace-comm-dot.is-withdrawn {
  background: #bfbfbf;
}
.trace-modal .trace-comm-type {
  font-weight: 400;
  font-size: 11px;
  color: rgba(0, 0, 0, 0.45);
  background: rgba(0, 0, 0, 0.04);
  border-radius: 4px;
  padding: 0 4px;
}
.trace-modal .trace-comm-status {
  flex: 0 0 auto;
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 9px;
}
.trace-modal .trace-comm-status.is-pending {
  color: #ad4e00;
  background: #fff7e6;
}
.trace-modal .trace-comm-status.is-replied {
  color: #389e0d;
  background: #f6ffed;
}
.trace-modal .trace-comm-status.is-withdrawn {
  color: #8c8c8c;
  background: #fafafa;
}
.trace-modal .trace-comm-item {
  position: relative;
  padding: 0 0 8px 16px;
}
.trace-modal .trace-comm-item::before {
  content: '';
  position: absolute;
  left: 3px;
  top: 7px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #c9d3e0;
}
.trace-modal .trace-comm-item.is-follow {
  margin-left: 18px;
}
.trace-modal .trace-comm-item.is-follow::before {
  background: #bfbfbf;
}
.trace-modal .trace-comm-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  font-size: 12px;
  line-height: 1.6;
  color: rgba(0, 0, 0, 0.45);
}
.trace-modal .trace-comm-from {
  color: rgba(0, 0, 0, 0.75);
  font-weight: 500;
}
.trace-modal .trace-comm-to {
  color: rgba(0, 0, 0, 0.75);
}
.trace-modal .trace-comm-arrow {
  color: #bfbfbf;
}
.trace-modal .trace-comm-time {
  margin-left: 6px;
  font-variant-numeric: tabular-nums;
}
.trace-modal .trace-comm-text {
  margin-top: 2px;
  font-size: 13px;
  line-height: 1.65;
  color: rgba(0, 0, 0, 0.82);
  white-space: pre-wrap;
  word-break: break-word;
}
.trace-modal .trace-comm-text.is-clamped {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
}
.trace-modal .trace-comm-toggle {
  margin-top: 2px;
  padding: 0;
  border: 0;
  background: none;
  font-size: 12px;
  color: #1677ff;
  cursor: pointer;
}
.trace-modal .trace-comm-toggle:hover {
  text-decoration: underline;
}
.trace-modal .trace-comm-reply {
  margin-top: 4px;
  padding: 4px 8px;
  border-radius: 6px;
  border-left: 2px solid #b7eb8f;
  background: #f6ffed;
}
.trace-modal .trace-comm-reply-text {
  font-size: 12.5px;
  line-height: 1.6;
  color: rgba(0, 0, 0, 0.78);
  white-space: pre-wrap;
  word-break: break-word;
}
.trace-modal .trace-comm-reply-text.is-empty {
  color: rgba(0, 0, 0, 0.35);
  font-style: italic;
}
.trace-modal .trace-comm-waiting {
  margin-top: 4px;
  font-size: 12px;
  color: #d46b08;
}
.trace-modal .trace-comm-waiting.is-muted {
  color: #8c8c8c;
}
@media (max-width: 768px) {
  .trace-modal .trace-comm-panel {
    padding: 8px 10px;
  }
  .trace-modal .trace-comm-item.is-follow {
    margin-left: 10px;
  }
}
</style>
