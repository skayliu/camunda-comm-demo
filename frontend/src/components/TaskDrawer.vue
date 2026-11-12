<template>
  <!-- 页脚最多同时出现 9 个按钮（转办/委托/加签/传阅/阅办/发起沟通/部门沟通/收回/通过审批），
       680px 下按钮组总宽超出正文区，「通过审批」会被裁掉，加宽到 780 -->
  <DetailDrawer
    :open="open"
    :width="780"
    :loading="loading"
    @update:open="(val) => { if (!val) close() }"
  >
    <template #title>
      <div style="display: flex; align-items: center; gap: 8px">
        <span>{{ task.taskName || '待办详情' }}</span>
        <a-tag v-if="task.isCommTask" :color="task.isFollowUp ? 'gold' : 'purple'">
          沟通
        </a-tag>
        <a-tag v-else color="blue">审批待办</a-tag>
        <a-tag v-if="task.isAddSignTask" color="purple">加签待办</a-tag>
        <a-tag v-else-if="signLocked" color="orange">加签中</a-tag>
        <a-tag v-if="task.delegationState === 'PENDING'" color="purple">委托中</a-tag>
        <a-tag v-else-if="task.delegationMode === 'SIGN'" color="geekblue">全权受托</a-tag>
        <a-tag v-if="transfers.length" color="cyan">已转办 {{ transfers.length }} 次</a-tag>
      </div>
    </template>

    <!-- 加载失败要单独成态：task 为空时下方所有 v-if 都不成立，
         抽屉会只剩“空白 + 底部审批按钮”，看起来像办理界面，实际是详情没取到 -->
    <div v-if="loadError && !loaded" class="drawer-section">
      <a-result status="warning" :title="loadError">
        <template #extra>
          <a-button @click="load">重新加载</a-button>
        </template>
      </a-result>
    </div>

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

      <!-- 转办记录：这条待办一路被谁交给谁，最后一条的接收人即当前办理人 -->
      <DetailSection v-if="transfers.length" title="转办记录" :extra="`共 ${transfers.length} 次`">
        <TransferChain :transfers="transfers" :current-assignee="task.assignee" />
      </DetailSection>

      <!-- 委托记录：责任仍在委托人，受托人只能提交反馈 -->
      <DetailSection v-if="delegations.length" title="委托记录" :extra="`共 ${delegations.length} 次`">
        <DelegationChain :delegations="delegations" />
      </DetailSection>

      <!-- 加签记录：前/后/并加签都挂在来源待办上，减签入口也只出现在未处理的人身上 -->
      <DetailSection v-if="signRecords.length" title="加签记录" :extra="`共 ${signRecords.length} 条`">
        <div v-for="r in signRecords" :key="r.id" class="sign-item">
          <!-- 类型 + 姓名留在左侧，模式/状态/减签收到右侧单独一组：
               标签之间用 gap 统一控制，且清掉 antd Tag 自带的右外边距，
               避免「并行」和「已撤销」两个彩块贴成一条 -->
          <div
            class="sign-item-head"
            style="display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px"
          >
            <a-tag :color="signTypeColor(r.signType)" style="margin-inline-end: 0">
              {{ signTypeText(r.signType) }}
            </a-tag>
            <b>{{ r.addUserId }}</b>
            <span style="flex: 1"></span>
            <span style="display: inline-flex; align-items: center; flex-wrap: wrap; gap: 12px">
              <a-tag
                v-if="r.signMode"
                :color="r.signMode === 'SEQUENTIAL' ? 'cyan' : 'geekblue'"
                style="margin-inline-end: 0"
              >
                {{ r.signMode === 'SEQUENTIAL' ? '串行' : '并行' }}
              </a-tag>
              <a-tag :color="signStatusColor(r.status)" style="margin-inline-end: 0">
                {{ signStatusText(r.status) }}
              </a-tag>
              <a-popconfirm
                v-if="r.status === 'PENDING' && r.initiatorId === currentUser"
                title="减签？减掉后可按同一配置补人，不推翻整轮"
                ok-text="减签"
                cancel-text="取消"
                @confirm="removeSign(r)"
              >
                <a-button size="small" type="link" danger style="padding-inline: 4px">
                  减签
                </a-button>
              </a-popconfirm>
            </span>
          </div>
          <!-- 意见单独成块：这是发起人「为什么加签」的说明，长文本需要与标签行分离 -->
          <div v-if="r.opinion" class="sign-opinion">
            意见：{{ r.opinion }}
          </div>
          <div class="muted" style="font-size: 11.5px; margin-top: 4px">
            发起人 {{ r.initiatorId }} · {{ fmt(r.createTime) }}
            <span v-if="r.finishTime"> · 完成 {{ fmt(r.finishTime) }}</span>
          </div>
        </div>
      </DetailSection>

      <div v-if="task.canResolveDelegation" class="drawer-section">
        <div class="drawer-section-title">委托反馈</div>
        <a-alert
          type="warning"
          show-icon
          style="margin-bottom: 10px"
          message="你正在受托处理此待办"
          description="提交反馈后任务回到委托人，由委托人确认办结；受托意见会单独记录在委托链上。"
        />
        <a-textarea
          v-model:value="resolveContent"
          :rows="4"
          :maxlength="2000"
          show-count
          placeholder="填写受托结果与建议"
        />
      </div>
      <a-alert
        v-else-if="task.canCancelDelegation"
        type="info"
        show-icon
        message="已委托给他人办理"
        :description="`当前受托人：${task.assignee || '-'}。对方提交反馈或你收回后，任务才会回到你的待办。`"
      />

        <!-- 部门沟通流程待办：分发 / 经办 / 汇总由专用面板办理 -->
        <template v-if="task.isDeptSubTask">
          <DeptSubTaskPanel ref="deptSubRef" :task-id="taskId" @done="onDeptSubDone" />
        </template>

        <!-- 沟通待办：我的反馈 -->
        <template v-else-if="task.isCommTask">
          <div class="drawer-section">
            <div class="drawer-section-title">沟通内容</div>
            <div class="comm-bubble">
              <div style="margin-bottom: 8px; color: var(--text-tertiary); font-size: 12px">
                {{ task.commInitiator || '发起人' }} 于 {{ fmt(task.createTime) }} 发起
              </div>
              {{ task.commContent || '（无内容）' }}
            </div>
          </div>

          <div class="drawer-section">
            <div class="drawer-section-title">同轮反馈进度</div>
            <div
              v-for="item in commPeerTree"
              :key="item.id"
              class="comm-target"
              :class="{ 'is-follow': item.depth > 0 }"
              :style="item.depth > 0 ? { marginLeft: `${18 + Math.min(item.depth - 1, 3) * 16}px` } : null"
            >
              <div v-if="item.depth > 0" class="comm-follow-link">
                <span class="comm-follow-arrow">↳</span>
                <span v-if="item.parentName">
                  来自 <b>{{ item.parentName }}</b> 的沟通
                </span>
                <span v-else>来自上一轮沟通</span>
              </div>
              <div class="comm-target-head">
                <div class="comm-target-name">
                  <a-avatar :size="24" style="background: #1677ff">
                    {{ (item.targetName || item.targetId || '?').slice(0, 1) }}
                  </a-avatar>
                  <span>{{ item.targetName || item.targetId }}</span>
                  <a-tag v-if="item.id === task.commRecordId" color="blue">我</a-tag>
                </div>
                <a-space :size="6">
                  <a-tag :color="statusColor(item.status)">
                    {{ statusText(item.status) }}
                  </a-tag>
                  <!-- 上游沟通对象收回：一次取消自己下游的全部未反馈记录（含下级再转交的隔级对象） -->
                  <a-popconfirm
                    v-if="item.status === 'PENDING' && item.canWithdraw"
                    title="收回该对象及其下游全部未反馈沟通？收回后对方待办将被删除"
                    ok-text="收回"
                    cancel-text="取消"
                    @confirm="withdrawPeer(item)"
                  >
                    <a-button size="small" danger type="link">收回</a-button>
                  </a-popconfirm>
                </a-space>
              </div>
              <div class="muted" style="font-size: 12.5px">{{ item.content }}</div>
              <div v-if="item.replyContent" class="comm-reply">
                {{ item.replyContent }}
                <div style="margin-top: 4px; color: var(--text-secondary); font-size: 11px">
                  {{ item.replyTime ? fmt(item.replyTime) : '' }}
                </div>
              </div>
            </div>
          </div>

          <div v-if="task.myCommStatus === 'PENDING'" class="drawer-section">
            <div class="drawer-section-title">我的反馈</div>
            <a-alert
              v-if="!canReply"
              type="warning"
              show-icon
              style="margin-bottom: 10px"
              message="暂时不能提交反馈"
              :description="replyBlockedReason"
            />
            <a-textarea
              v-model:value="replyContent"
              :rows="4"
              :disabled="!canReply"
              :placeholder="canReply
                ? '填写你的核实结果或意见，提交后本条沟通待办将完成'
                : '还有未反馈的沟通，需等对方反馈或由发起人/上游沟通对象收回后才能提交'"
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
          <a-alert
            v-if="task.isAddSignTask"
            type="info"
            show-icon
            style="margin-bottom: 12px"
            message="你正在处理一条加签待办"
            :description="`来源待办：${signSelfRecord ? signSelfRecord.sourceTaskDefKey : '-'}。同意即计入本轮结果；驳回会让整轮立即不通过，其余加签人的待办一并撤销。`"
          />
          <div v-if="!task.isAddSignTask" class="drawer-section">
            <div class="drawer-section-title">沟通状态</div>
            <a-alert
              v-if="isLocked"
              type="warning"
              show-icon
              :message="lockMessage"
              :description="lockDescription"
            />
            <a-alert
              v-else
              class="approve-ok"
              type="success"
              show-icon
              message="可以正常审批"
              description="如需补充信息，可先向相关人员发起沟通。"
            />
          </div>

          <div v-if="commSessions.length" class="drawer-section">
            <div class="drawer-section-title">
              沟通记录
              <span class="comm-session-count">共 {{ commSessions.length }} 次沟通</span>
            </div>

            <div
              v-for="s in commSessions"
              :key="s.sessionId || s.seq"
              class="comm-session"
              :class="{ 'is-active': s.active }"
            >
              <div class="comm-session-head">
                <span class="comm-session-index">第 {{ s.seq }} 次沟通</span>
                <a-tag v-if="s.active" color="processing" style="margin-inline-end: 0">
                  进行中 · 第 {{ s.currentRound }} 轮
                </a-tag>
                <a-tag v-else color="default" style="margin-inline-end: 0">已结束</a-tag>
                <span class="comm-session-time">
                  {{ s.initiator || '发起人' }} 发起 · {{ fmt(s.createTime) }}
                </span>
              </div>
              <div class="comm-bubble" style="margin-bottom: 12px">
                {{ s.initialContent }}
              </div>

              <div
                v-for="item in s.tree"
                :key="item.id"
                class="comm-target"
                :class="{ 'is-follow': item.depth > 0 || item.isFollowUp }"
                :style="item.depth > 0 ? { marginLeft: `${18 + Math.min(item.depth - 1, 3) * 16}px` } : null"
              >
                <div v-if="item.depth > 0 || item.isFollowUp" class="comm-follow-link">
                  <span class="comm-follow-arrow">↳</span>
                  <span v-if="item.parentName">
                    来自
                    <b>{{ item.parentName }}</b>
                    的沟通 · 第 {{ item.depth + 1 }} 轮
                  </span>
                  <span v-else>来自上一轮沟通</span>
                  <span v-if="item.parentContent" class="comm-follow-quote">
                    “{{ item.parentContent }}”
                  </span>
                </div>
                <div class="comm-target-head">
                  <div class="comm-target-name">
                    <a-avatar :size="24" style="background: #722ed1">
                      {{ (item.targetName || item.targetId || '?').slice(0, 1) }}
                    </a-avatar>
                    <span>{{ item.targetName || item.targetId }}</span>
                    <span class="mono muted">{{ item.targetId }}</span>
                    <a-tag v-if="item.depth > 0 || item.isFollowUp" color="gold" style="margin-inline-end: 0">沟通</a-tag>
                  </div>
                  <a-space :size="6">
                    <a-tag :color="statusColor(item.status)">
                      {{ statusText(item.status) }}
                    </a-tag>
                    <a-popconfirm
                      v-if="item.status === 'PENDING' && (item.canWithdrawOne || comm.canWithdraw)"
                      title="收回该沟通及其下游未反馈对象？"
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
                      继续沟通
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
                  <div style="margin-top: 4px; color: var(--text-secondary); font-size: 11px">
                    {{ item.replyUserId }} · {{ fmt(item.replyTime) }}
                  </div>
                </div>
                <div v-else-if="item.status === 'PENDING'" class="comm-reply pending">
                  等待 {{ item.targetName || item.targetId }} 反馈
                </div>
                <div v-else-if="item.status === 'WITHDRAWN'" class="comm-reply withdrawn">
                  已收回<span v-if="item.withdrawTime"> · {{ fmt(item.withdrawTime) }}</span>
                </div>
              </div>

              <div v-if="!s.tree.length" class="muted" style="font-size: 12px">
                本次沟通没有沟通对象记录
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

    <template #footer>
      <a-button @click="close">关闭</a-button>

      <!-- 详情没取到时不渲染任何操作按钮：否则 isCommTask 为空会走到审批分支，
           出现“发起沟通 / 通过审批”，把权限问题伪装成办理界面 -->
      <template v-if="loaded">
        <!-- 部门沟通流程：面板只负责渲染表单，提交动作统一由抽屉页脚触发，
             与「发起沟通 / 通过审批」保持同一位置，用户不需要在面板里找按钮 -->
        <template v-if="task.isDeptSubTask">
          <a-button type="primary" :loading="deptSubSubmitting" @click="submitDeptSub">
            提交
          </a-button>
        </template>
        <template v-else-if="task.isCommTask">
          <a-button
            v-if="task.myCommStatus === 'PENDING' && canForward"
            :loading="commSubmitting"
            @click="openForward"
          >
            发起沟通
          </a-button>
          <a-tooltip :title="replyBlockedReason || ''">
            <a-button
              type="primary"
              :loading="submitting"
              :disabled="task.myCommStatus !== 'PENDING' || !canReply"
              @click="submitReply"
            >
              提交反馈
            </a-button>
          </a-tooltip>
        </template>

        <template v-else>
          <!-- 转办条件由后端 canTransfer 统一计算：当前办理人本人、非沟通待办、不在沟通中 -->
          <a-button v-if="task.canTransfer && !task.isAddSignTask" @click="openTransfer">转办</a-button>
          <a-button v-if="task.canDelegate && !task.isAddSignTask" @click="delegateOpen = true">委托</a-button>
          <template v-if="task.canResolveDelegation">
            <a-button type="primary" :loading="delegationSubmitting" @click="submitResolve">
              提交反馈并交回
            </a-button>
          </template>
          <a-popconfirm
            v-if="task.canCancelDelegation"
            title="收回委托？任务将回到你的待办"
            ok-text="收回"
            cancel-text="取消"
            @confirm="cancelDelegation"
          >
            <a-button danger>收回委托</a-button>
          </a-popconfirm>
          <!-- 加签：前/并/后三种落在不同执行层，由弹层按类型提交；
               加签进行中或已是加签任务时不再显示入口，避免嵌套加签 -->
          <a-dropdown v-if="canAddSign">
            <a-button>加签 ▾</a-button>
            <template #overlay>
              <a-menu @click="onSignMenu">
                <a-menu-item key="PRE">前加签（我先请他审）</a-menu-item>
                <!-- 单实例节点上并加签必然失败，后端按节点是否多实例下发该标记。
                     以前是整项隐藏，用户会以为「功能漏做了」；现在置灰保留，
                     并用尾巴标注 + 悬停说明指出可用替代（前加签/后加签） -->
                <a-menu-item
                  key="PARALLEL"
                  :disabled="!task.supportsParallelSign"
                  :title="parallelSignHint"
                  class="sign-menu-parallel"
                >
                  <span>并加签（同轮一起审）</span>
                  <span v-if="!task.supportsParallelSign" class="sign-menu-note">
                    单实例不可用
                  </span>
                </a-menu-item>
                <a-menu-item key="POST">后加签（我通过后再审）</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
          <!-- 加签任务只保留同意/驳回：转办、传阅、沟通都不该出现在子轮次里 -->
          <template v-if="!task.isAddSignTask">
          <a-button
            :disabled="!canInitiate"
            :title="canInitiate ? '' : '仅当前待办处理人可传阅'"
            @click="openCcHandle('cc')"
          >
            传阅
          </a-button>
          <a-button
            :disabled="!canInitiate"
            :title="canInitiate ? '' : '仅当前待办处理人可发起阅办'"
            @click="openCcHandle('handle')"
          >
            阅办
          </a-button>
          <a-button
            :disabled="!canInitiate"
            :title="canInitiate ? '' : '仅当前待办处理人可发起沟通'"
            @click="openStart"
          >
            发起沟通
          </a-button>
          <!-- 部门沟通：选择部门后由该部门按选定的部门沟通流程内部闭环处理，
               完成后把部门最终意见回填到沟通记录并解锁当前待办 -->
          <a-button
            :disabled="!canInitiate"
            :title="canInitiate ? '' : '仅当前待办处理人可发起部门沟通'"
            @click="deptCommOpen = true"
          >
            部门沟通
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
          </template>
          <!-- 加签任务：只有“同意 / 驳回”，驳回即整轮不通过 -->
          <template v-if="task.isAddSignTask">
            <a-button
              type="primary"
              :loading="submitting"
              :disabled="signSelfRecord && signSelfRecord.status !== 'PENDING'"
              @click="submitAddSign(true)"
            >
              同意
            </a-button>
            <a-button
              danger
              :loading="submitting"
              :disabled="signSelfRecord && signSelfRecord.status !== 'PENDING'"
              @click="submitAddSign(false)"
            >
              驳回（整轮不通过）
            </a-button>
          </template>
          <a-tooltip
            v-else
            :title="task.delegationState === 'PENDING'
              ? '委托中的任务需先由受托人提交反馈或委托人收回'
              : (isLocked ? lockDescription : '')"
          >
            <a-button
              type="primary"
              :loading="submitting"
              :disabled="isLocked || task.delegationState === 'PENDING'"
              @click="submitApprove"
            >
              通过审批
            </a-button>
          </a-tooltip>
        </template>
      </template>
    </template>

    <!-- 发起 / 继续沟通 -->
    <template #extra>
    <a-modal
      v-model:open="commModal.open"
      :title="commModal.title"
      :confirm-loading="commSubmitting"
      ok-text="提交"
      cancel-text="取消"
      @ok="submitComm"
    >
      <a-form layout="vertical" style="margin-top: 8px">
        <a-alert
          v-if="commModal.mode === 'start' && commModal.appendMode"
          type="info"
          show-icon
          style="margin-bottom: 12px"
          message="本次沟通仍在进行中"
          description="已预选当前沟通对象，只需补上遗漏的人员；已在沟通中的人员会自动跳过。"
        />
        <a-alert
          v-if="commModal.mode === 'forward'"
          type="info"
          show-icon
          style="margin-bottom: 12px"
          message="向其他人补充沟通"
          description="新沟通会挂在这条沟通记录下，审批人在沟通记录里能看到它的来源。不能指定发起人或上游沟通对象；你仍可继续填写自己的反馈。"
        />
        <a-form-item label="沟通对象" required>
          <a-select
            v-model:value="commForm.targets"
            mode="multiple"
            placeholder="选择需要反馈的人员或部门"
            :options="commModal.mode === 'forward' ? forwardOptions : targetOptions"
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

    <TransferModal
      v-model:open="transferOpen"
      :task-id="taskId"
      :task-name="task.taskName"
      @done="onTransferDone"
    />

    <DelegateModal
      v-model:open="delegateOpen"
      :task-id="taskId"
      :task-name="task.taskName"
      @done="onDelegateDone"
    />

    <CcHandleModal
      v-model:open="ccHandleOpen"
      :mode="ccHandleMode"
      :task-id="taskId"
      :process-instance-id="task.processInstanceId"
      :task-name="task.taskName"
      @done="load"
    />

    <SignModal
      v-model:open="signOpen"
      :task-id="taskId"
      :task-name="task.taskName"
      :sign-type="signType"
      @done="onSignDone"
    />

    <DeptCommModal
      v-model:open="deptCommOpen"
      :task-id="taskId"
      @submitted="onDeptCommSubmitted"
    />
    </template>
  </DetailDrawer>
</template>

<script setup>
import { computed, defineAsyncComponent, reactive, ref, watch } from 'vue'
import message from 'ant-design-vue/es/message'
import dayjs from 'dayjs'
import { commApi, errorText, getUser, signApi, wbApi } from '../api'
import TransferModal from './TransferModal.vue'
import DeptCommModal from './DeptCommModal.vue'
import CcHandleModal from './CcHandleModal.vue'
import DelegateModal from './DelegateModal.vue'
import DelegationChain from './DelegationChain.vue'
import DeptSubTaskPanel from './DeptSubTaskPanel.vue'
import DetailDrawer from './DetailDrawer.vue'
import DetailSection from './DetailSection.vue'
import TransferChain from './TransferChain.vue'

// 加签弹层只在点「加签」时才用得到，与其他弹层一致按需加载
const SignModal = defineAsyncComponent(() => import('./SignModal.vue'))

const props = defineProps({
  open: { type: Boolean, default: false },
  taskId: { type: String, default: '' }
})
const emit = defineEmits(['update:open', 'refresh'])

const loading = ref(false)
const loaded = ref(false)
// 加载失败的原因：保留下来渲染成错误态，而不是只弹一条提示后留个空抽屉
const loadError = ref('')
const submitting = ref(false)
const task = ref({})
const approveComment = ref('')
const replyContent = ref('')

// ===== 加签 =====
// 加签记录随 taskDetail 一次返回；减签入口只对“本人发起且仍未处理”的记录展示
const currentUser = getUser()
const signOpen = ref(false)
const signType = ref('PRE')
const signRecords = computed(() =>
  Array.isArray(task.value.signRecords) ? task.value.signRecords : []
)
// 当前这条任务如果本身就是加签任务，找到对应记录用于判断是否已处理。
// 并加签扩容任务拿不到回填的 addTaskId（后端按轮次反查后直接下发 selfSignRecord），优先用它。
const signSelfRecord = computed(() =>
  task.value.selfSignRecord
  || signRecords.value.find((r) => r.addTaskId === props.taskId)
  || null
)
const signLocked = computed(() =>
  ['WAIT_PRE_SIGN', 'WAIT_POST_SIGN'].includes(comm.value.bizStatus)
)
// 加签入口：普通审批任务、待办归自己、没有沟通/加签锁定
const canAddSign = computed(() =>
  !!task.value.canAddSign
  && canInitiate.value
  && !signLocked.value
  && comm.value.bizStatus !== 'COMMUNICATING'
)
// 并加签不可用时的说明：讲清原因 + 直接给出替代路径，避免用户以为功能缺失
const parallelSignHint = computed(() => (
  task.value.supportsParallelSign
    ? ''
    : '当前节点是单实例，不能并加签；请改用前加签（我先请他审）或后加签（我通过后再审）'
))

function onSignMenu({ key }) {
  // 并加签在单实例节点上不可用：菜单项已 disabled，这里再兜底一次，
  // 防止禁用态失效时把请求发到服务端再吃一个 400
  if (key === 'PARALLEL' && !task.value.supportsParallelSign) {
    message.info('当前节点是单实例，不能并加签，请改用前加签或后加签')
    return
  }
  signType.value = key
  signOpen.value = true
}

async function onSignDone() {
  await load()
  emit('refresh')
}

function signTypeText(type) {
  if (type === 'PARALLEL') return '并加签'
  if (type === 'POST') return '后加签'
  return '前加签'
}

function signTypeColor(type) {
  if (type === 'PARALLEL') return 'blue'
  if (type === 'POST') return 'gold'
  return 'purple'
}

function signStatusText(status) {
  const map = {
    PENDING: '待处理',
    APPROVED: '已同意',
    REJECTED: '已驳回',
    CANCELLED: '已撤销'
  }
  return map[status] || status || '-'
}

function signStatusColor(status) {
  if (status === 'APPROVED') return 'green'
  if (status === 'REJECTED') return 'red'
  if (status === 'CANCELLED') return 'default'
  return 'processing'
}

async function removeSign(record) {
  try {
    const res = await signApi.remove(record.id)
    if (res && res.error) {
      message.warning(res.message || '减签失败')
      return
    }
    message.success('已减签，可重新加签补人')
    await load()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  }
}

// 转办记录：后端按时间升序返回，最后一条的接收人就是当前办理人。
// 这里不做二次排序，避免与落库顺序不一致。
const transferOpen = ref(false)
// 部门沟通：与“发起沟通”并列的独立入口。部门沟通需要先选部门、再选该部门的
// 部门沟通流程（通用三节点或专属流程），所以不能复用按人员多选的沟通弹窗。
const deptCommOpen = ref(false)
// 传阅/阅办共用弹窗：传阅只送达，阅办要反馈结果，两者都不进审批链
const ccHandleOpen = ref(false)
const ccHandleMode = ref('cc')

function openCcHandle(mode) {
  ccHandleMode.value = mode
  ccHandleOpen.value = true
}
// 部门沟通流程面板：表单状态在子组件里，页脚按钮通过 expose 的 submit() 驱动提交
const deptSubRef = ref(null)
const deptSubSubmitting = ref(false)

async function submitDeptSub() {
  if (!deptSubRef.value) {
    message.warning('部门沟通面板尚未加载，请稍后再试')
    return
  }
  deptSubSubmitting.value = true
  try {
    // 子组件内部会做必填校验并弹提示；返回 false 表示校验未通过
    await deptSubRef.value.submit()
  } finally {
    deptSubSubmitting.value = false
  }
}

function onDeptSubDone() {
  // 子流程节点已完成：当前待办要么流转到部门内部下一节点、要么子流程结束。
  // 两种情况当前用户都已无该任务，关闭抽屉并刷新列表即可。
  close()
  emit('refresh')
}

const transfers = computed(() => (Array.isArray(task.value.transfers) ? task.value.transfers : []))

// 委托：责任仍归委托人，受托人只能提交反馈并交回
const delegateOpen = ref(false)
const delegationSubmitting = ref(false)
const resolveContent = ref('')
const delegations = computed(() => (Array.isArray(task.value.delegations) ? task.value.delegations : []))

function onDelegateDone() {
  // 委托后任务已不归自己，taskDetail 会 403，直接关闭并刷新列表
  delegateOpen.value = false
  close()
  emit('refresh')
}

async function submitResolve() {
  if (!resolveContent.value.trim()) {
    message.warning('请填写受托反馈')
    return
  }
  delegationSubmitting.value = true
  try {
    const res = await wbApi.resolveDelegation(props.taskId, resolveContent.value.trim())
    if (res && res.error) {
      message.warning(res.message || '无法提交反馈')
      return
    }
    message.success('反馈已提交，任务已交回委托人')
    close()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    delegationSubmitting.value = false
  }
}

async function cancelDelegation() {
  try {
    const res = await wbApi.cancelDelegation(props.taskId, '')
    if (res && res.error) {
      message.warning(res.message || '无法收回委托')
      return
    }
    message.success('已收回委托')
    await load()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  }
}

function openTransfer() {
  transferOpen.value = true
}

// 转办成功后本条待办已经不归自己：taskDetail 会按 assignee 返回 403，
// 所以不能再 load()，直接关闭抽屉并刷新列表。
function onTransferDone() {
  transferOpen.value = false
  close()
  emit('refresh')
}

const comm = ref({})
// 沟通待办自身状态：taskDetail 里 commStatus 指向“源待办”，
// 按钮权限需要看这条沟通记录自己的状态（canForwardComm 按当前沟通对象计算）
const commSelf = ref({})
const canForward = computed(() => !!commSelf.value.canForwardComm)
// 提交反馈完成的前置条件（后端 canReply 与 replyCommTask 的校验同源）：
// 自己是这条沟通的接收人、仍待反馈，且没有仍未反馈的下游沟通。
const canReply = computed(() => commSelf.value.canReply !== false)
const replyBlockedReason = computed(() => {
  if (task.value.myCommStatus !== 'PENDING' || canReply.value) return ''
  const who = commSelf.value.replyBlockedBy
  return who
    ? `还有未反馈的沟通：${who}。需等对方反馈，或由发起人/上游沟通对象收回后才能提交本条反馈。`
    : '还有未反馈的沟通，需等对方反馈，或由发起人/上游沟通对象收回后才能提交本条反馈。'
})
const commTasks = computed(() =>
  Array.isArray(comm.value.commTasks) ? comm.value.commTasks : []
)

// 沟通层级：按 parentCommTaskId 把记录还原成「首轮沟通 → 后续沟通 → 再后续」的树，
// 再按时间顺序展开成带层级的扁平列表，供模板缩进渲染（与流程跟踪页保持一致）
function buildCommTree(source) {
  const list = (source || [])
    .slice()
    .sort((a, b) => new Date(a.createTime || 0) - new Date(b.createTime || 0))
  const byId = new Map(list.map((t) => [t.id, t]))
  const children = new Map()
  const roots = []
  // 有 parentCommTaskId 但父记录不在本次返回里的后续沟通：
  // 不能当成首轮沟通（会丢掉层级关系），用后端带回的父级快照单独保留层级。
  const orphans = []
  list.forEach((task) => {
    const parent = task.parentCommTaskId ? byId.get(task.parentCommTaskId) : null
    if (!parent) {
      if (task.parentCommTaskId) orphans.push(task)
      else roots.push(task)
      return
    }
    if (!children.has(parent.id)) children.set(parent.id, [])
    children.get(parent.id).push(task)
  })

  const rows = []
  const walk = (task, depth, parent) => {
    rows.push({
      ...task,
      depth,
      parentName: parent ? parent.targetName || parent.targetId || '' : '',
      parentContent: parent ? parent.content || '' : ''
    })
    ;(children.get(task.id) || []).forEach((child) => walk(child, depth + 1, task))
  }
  roots.forEach((root) => walk(root, 0, null))
  orphans.forEach((task) => {
    rows.push({
      ...task,
      depth: 1,
      parentName: task.parentTargetName || '',
      parentContent: task.parentContent || ''
    })
    ;(children.get(task.id) || []).forEach((child) => walk(child, 2, task))
  })
  return rows
}

// 同一待办可以多次发起沟通，每次发起都是一个独立会话。
// 后端 sessions 返回全部会话（按发起时间升序），这里逐会话建树后分组渲染；
// 旧接口只回单会话时降级成一组，保证兼容。
const commSessions = computed(() => {
  const sessions = Array.isArray(comm.value.sessions) ? comm.value.sessions : []
  if (sessions.length) {
    return sessions.map((s, index) => ({
      sessionId: s.sessionId,
      seq: s.seq || index + 1,
      active: !!s.active,
      status: s.status,
      initiator: s.initiator,
      initialContent: s.initialContent,
      currentRound: s.currentRound || 1,
      createTime: s.createTime,
      tree: buildCommTree(s.commTasks || [])
    }))
  }
  if (!commTasks.value.length) return []
  return [
    {
      sessionId: comm.value.sessionId,
      seq: 1,
      active: !!comm.value.sessionActive,
      status: comm.value.sessionStatus,
      initiator: comm.value.initiator,
      initialContent: comm.value.initialContent,
      currentRound: comm.value.currentRound || 1,
      createTime: comm.value.createTime,
      tree: buildCommTree(commTasks.value)
    }
  ]
})

// 追平层：所有会话的记录合集（保留给需要平铺判断的场景）
const commTree = computed(() => commSessions.value.flatMap((s) => s.tree))

// 沟通反馈界面：同一会话内的记录也按父链展开，来源/层级关系才看得出来
const commPeerTree = computed(() => buildCommTree(task.value.sessionTasks || []))

// 待反馈对象只统计进行中的会话，历史会话的残留状态不参与锁定判断
const pendingCommTargets = computed(() => {
  const active = commSessions.value.find((s) => s.active)
  const list = active ? active.tree : commTree.value
  return list.filter((t) => t.status === 'PENDING')
})

const isLocked = computed(() =>
  comm.value.bizStatus === 'COMMUNICATING' || signLocked.value
)
const canInitiate = computed(() => !!comm.value.canInitiate)
const lockMessage = computed(() => {
  if (signLocked.value) return '当前待办因加签暂时锁定'
  return '当前待办正在沟通中，暂不可审批'
})
const lockDescription = computed(() => {
  if (comm.value.bizStatus === 'WAIT_PRE_SIGN') {
    return '当前待办已发起前加签，需加签轮次结束（通过或驳回）后才能继续审批。'
  }
  if (comm.value.bizStatus === 'WAIT_POST_SIGN') {
    return '当前待办的后加签仍在进行，加签轮次结束后节点才会流转。'
  }
  const pending = pendingCommTargets.value
  if (!pending.length) {
    return '沟通状态正在刷新，请稍后再试。'
  }
  return `还有 ${pending.length} 位对象未反馈：${pending
    .map((t) => t.targetName || t.targetId)
    .join('、')}。需全部反馈或全部收回后才能审批。`
})

// 发起 / 继续沟通
const commModal = reactive({ open: false, mode: 'start', parentId: null, title: '', appendMode: false })
const commForm = reactive({ targets: [], content: '' })
const commSubmitting = ref(false)
const targetOptions = ref([])
const targetNames = ref({})
// 沟通对象类型：人员 USER / 部门 DEPT。部门走独立子流程，id 是部门组ID，
// 提交时必须带上正确类型，否则后端会把它当成一个不存在的账号。
const targetTypes = ref({})

function filterOption(input, option) {
  return (option.label || '').toLowerCase().includes(input.toLowerCase())
}

// 转交时不可选的人：自己、本次沟通发起人、以及上游沟通对象（父/祖父……）。
// 后端 forwardCommunication 会做同样的拒绝，这里只是不让用户白选一遍。
const forwardOptions = computed(() => {
  const excluded = new Set()
  const list = Array.isArray(task.value.sessionTasks) ? task.value.sessionTasks : []
  const byId = new Map(list.map((t) => [t.id, t]))
  const self = byId.get(task.value.commRecordId)
  if (self && self.targetId) excluded.add(self.targetId)
  let cur = self
  const guard = new Set()
  while (cur && cur.parentCommTaskId && !guard.has(cur.id)) {
    guard.add(cur.id)
    const parent = byId.get(cur.parentCommTaskId)
    if (!parent) break
    if (parent.targetId) excluded.add(parent.targetId)
    cur = parent
  }
  if (comm.value.initiator) excluded.add(comm.value.initiator)
  return targetOptions.value.filter((o) => !excluded.has(o.value))
})

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
  // 不清掉上一条任务的数据，切换待办时会把上一条的 isCommTask / myCommStatus
  // 带进新的一条，界面按错的类型渲染
  task.value = {}
  comm.value = {}
  commSelf.value = {}
  loadError.value = ''
  try {
    const data = await wbApi.taskDetail(props.taskId)
    task.value = data
    comm.value = data.commStatus || {}
    commSelf.value = data.commStatusOfSelf || {}
    replyContent.value = ''
    approveComment.value = ''
    loaded.value = true
  } catch (e) {
    // 不弹 toast：错误已经渲染进抽屉，弹窗关掉后仍会留下一个空白抽屉
    loadError.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function ensureTargets() {
  if (targetOptions.value.length) return
  try {
    const [users, departments] = await Promise.all([
      commApi.targets(''),
      commApi.departments('')
    ])
    const userOptions = users.map((u) => ({
      value: u.id,
      label: `${u.name}（${u.id}）`,
      type: 'USER',
      name: u.name
    }))
    // 部门选项单独标注“部门 · N 人”：选部门代表整个部门承接，
    // 部门内任意成员认领后反馈，不是指定某一个人。
    const deptOptions = departments.map((d) => ({
      value: d.id,
      label: `${d.name}（部门 · ${d.memberCount || 0} 人）`,
      type: 'DEPT',
      name: d.name
    }))
    targetOptions.value = [...userOptions, ...deptOptions]
    targetNames.value = Object.fromEntries(
      targetOptions.value.map((o) => [o.value, o.name])
    )
    targetTypes.value = Object.fromEntries(
      targetOptions.value.map((o) => [o.value, o.type])
    )
  } catch (e) {
    message.error(errorText(e))
  }
}

// 部门沟通发起成功后：子流程已启动、当前待办进入沟通锁定状态，
// 必须重新拉一次详情，让「通过审批 / 全部收回」等按钮状态与新沟通记录同步
async function onDeptCommSubmitted() {
  deptCommOpen.value = false
  await load()
  emit('refresh')
}

async function openStart() {
  await ensureTargets()
  // 已有进行中的沟通时，本次提交是“补充对象”：预选当前沟通对象，
  // 用户只需补上漏掉的人，后端会自动跳过已在沟通中的人员。
  const activeSession = (comm.value.sessions || []).find((s) => s.active)
  commModal.mode = 'start'
  commModal.parentId = null
  commModal.appendMode = !!activeSession
  commModal.title = activeSession ? '发起沟通（补充沟通对象）' : '发起沟通'
  commForm.targets = activeSession
    ? [...new Set((activeSession.commTasks || [])
        .filter((t) => t.status !== 'WITHDRAWN')
        .map((t) => t.targetId))]
    : []
  commForm.content = activeSession ? (activeSession.initialContent || '') : ''
  commModal.open = true
}

async function openContinue(item) {
  await ensureTargets()
  commModal.mode = 'continue'
  commModal.parentId = item.id
  commModal.title = `继续沟通：${item.targetName || item.targetId}`
  commForm.targets = [item.targetId]
  commForm.content = ''
  commModal.open = true
}

// 沟通反馈界面：以当前沟通记录为父，向其他人再发起一条沟通
async function openForward() {
  await ensureTargets()
  commModal.mode = 'forward'
  commModal.parentId = task.value.commRecordId
  commModal.appendMode = false
  commModal.title = '发起沟通'
  commForm.targets = []
  commForm.content = ''
  commModal.open = true
}

function buildTargets() {
  return commForm.targets.map((id) => ({
    type: targetTypes.value[id] || 'USER',
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
      const res = await commApi.start({
        taskId: props.taskId,
        content: commForm.content.trim(),
        targets: buildTargets()
      })
      // 业务异常走的是 HTTP 200 + {error:true}，不判这里会把失败提示成“成功”
      if (res && res.error) {
        message.warning(res.message || '无法发起沟通')
        return
      }
      const added = (res && res.addedTargets) || []
      const skipped = (res && res.skippedTargets) || []
      if (res && res.appended) {
        message.success(`已补充沟通对象：${added.join('、')}`
          + (skipped.length ? `（${skipped.join('、')} 已在本次沟通中，已跳过）` : ''))
      } else {
        message.success(added.length ? `沟通已发起：${added.join('、')}` : '沟通已发起')
      }
    } else if (commModal.mode === 'continue') {
      const res = await commApi.continueCommunication({
        parentCommTaskRecordId: commModal.parentId,
        content: commForm.content.trim(),
        targets: buildTargets()
      })
      if (res && res.error) {
        message.warning(res.message || '无法继续沟通')
        return
      }
      message.success('继续沟通已发送')
    } else {
      // forward：沟通对象本人把这条沟通转给他人，同一会话内形成子记录
      const res = await commApi.forward({
        commTaskRecordId: commModal.parentId,
        content: commForm.content.trim(),
        targets: buildTargets()
      })
      if (res && res.error) {
        message.warning(res.message || '无法发起沟通')
        return
      }
      const added = (res && res.addedTargets) || []
      message.success(added.length ? `已发起沟通：${added.join('、')}` : '沟通已发起')
    }
    commModal.open = false
    await load()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    commSubmitting.value = false
  }
}

async function withdrawPeer(item) {
  try {
    // 同轮反馈进度里的记录都来自当前沟通待办所属会话。
    // 后端对“上游收回”的口径是：收回自己下游的全部未反馈记录（含更下级转交），
    // 因此这里传一条 id 即可，返回体里的 withdrawn 是实际收回条数。
    const res = await commApi.withdraw(task.value.sessionId, [item.id])
    if (res && res.error) {
      message.warning(res.message || '收回失败')
      return
    }
    const n = res && typeof res.withdrawn === 'number' ? res.withdrawn : 0
    message.success(n > 1 ? `已收回 ${n} 条下游沟通` : '已收回')
    await load()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  }
}

async function withdrawOne(item) {
  try {
    // 记录可能属于历史会话，收回必须带上它自己的 sessionId
    const res = await commApi.withdraw(item.sessionId || comm.value.sessionId, [item.id])
    if (res && res.error) {
      message.warning(res.message || '收回失败')
      return
    }
    const n = res && typeof res.withdrawn === 'number' ? res.withdrawn : 0
    message.success(n > 1 ? `已收回 ${n} 条下游沟通` : '已收回')
    await load()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  }
}

async function withdrawAll() {
  try {
    const res = await commApi.withdraw(comm.value.sessionId, [])
    if (res && res.error) {
      message.warning(res.message || '收回失败')
      return
    }
    message.success('已全部收回')
    await load()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  }
}

async function submitReply() {
  if (!canReply.value) {
    message.warning(replyBlockedReason.value || '暂时不能提交反馈')
    return
  }
  if (!replyContent.value.trim()) {
    message.warning('请填写反馈内容')
    return
  }
  submitting.value = true
  try {
    const res = await commApi.reply({
      commTaskRecordId: task.value.commRecordId,
      content: replyContent.value.trim()
    })
    // 业务异常是 HTTP 200 + {error:true}，不判会把它显示成“反馈已提交”
    if (res && res.error) {
      message.warning(res.message || '无法提交反馈')
      await load()
      return
    }
    message.success('反馈已提交')
    close()
    emit('refresh')
  } catch (e) {
    message.error(errorText(e))
  } finally {
    submitting.value = false
  }
}

async function submitAddSign(approved) {
  submitting.value = true
  try {
    const res = await signApi.completeAddSignTask(
      props.taskId,
      approved,
      approveComment.value.trim() || null
    )
    if (res && res.error) {
      message.warning(res.message || '无法提交加签意见')
    } else {
      message.success(approved ? '已同意' : '已驳回，整轮不通过')
      close()
      emit('refresh')
    }
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
  },
  // 组件可能由 v-if 在「已打开」状态下才挂载（按需加载），
  // 此时 open 没有从 false 变 true 的过程，必须立即执行一次
  { immediate: true }
)

watch(
  () => props.taskId,
  () => {
    if (props.open) load()
  }
)
</script>
