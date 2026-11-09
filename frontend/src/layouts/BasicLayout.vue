<template>
  <a-layout class="wb-layout">
    <a-layout-sider
      v-model:collapsed="collapsed"
      collapsible
      :width="220"
      :collapsed-width="64"
      :trigger="null"
      class="wb-sider"
    >
      <div class="wb-logo">
        <div class="wb-logo-mark">协</div>
        <div v-if="!collapsed">
          <div class="wb-logo-text">流程审批中心</div>
          <div class="wb-logo-sub">Process Collaboration</div>
        </div>
      </div>
      <a-menu
        :selected-keys="[activeKey]"
        mode="inline"
        theme="dark"
        @click="onMenuClick"
      >
        <a-menu-item key="todo">
          <template #icon><InboxOutlined /></template>
          <span class="menu-label">
            <span>待办中心</span>
            <span class="menu-badges">
              <a-badge v-if="todoCount > 0" :count="todoCount" :overflow-count="99" />
              <a-tooltip
                v-if="delegatingCount > 0"
                :title="`已委托给他人、等待对方反馈的任务 ${delegatingCount} 条`"
                placement="right"
              >
                <span class="menu-delegating">{{ delegatingCount }} 委托中</span>
              </a-tooltip>
            </span>
          </span>
        </a-menu-item>
        <a-menu-item key="done">
          <template #icon><CheckCircleOutlined /></template>
          <span class="menu-label">我的已办</span>
        </a-menu-item>
        <a-menu-item key="delegation">
          <template #icon><SwapOutlined /></template>
          <span class="menu-label">委托记录</span>
        </a-menu-item>
        <a-menu-item key="cc">
          <template #icon><MailOutlined /></template>
          <span class="menu-label">
            <span>我的待阅</span>
            <a-badge v-if="ccUnread > 0" :count="ccUnread" :overflow-count="99" />
          </span>
        </a-menu-item>
        <a-menu-item key="cc-sent">
          <template #icon><ExportOutlined /></template>
          <span class="menu-label">我的传阅</span>
        </a-menu-item>
        <a-menu-item key="handle">
          <template #icon><SolutionOutlined /></template>
          <span class="menu-label">
            <span>我的承办</span>
            <a-badge v-if="handlePending > 0" :count="handlePending" :overflow-count="99" />
          </span>
        </a-menu-item>
        <a-menu-item key="started">
          <template #icon><SendOutlined /></template>
          <span class="menu-label">我的发起</span>
        </a-menu-item>
        <a-menu-item key="agent">
          <template #icon><UserSwitchOutlined /></template>
          <span class="menu-label">代理设置</span>
        </a-menu-item>
        <a-menu-divider v-if="isAdmin" />
        <a-menu-item v-if="isAdmin" key="cc-handle-report">
          <template #icon><BarChartOutlined /></template>
          <span class="menu-label">传阅与承办统计</span>
        </a-menu-item>
        <a-menu-item v-if="isAdmin" key="subprocess-defs">
          <template #icon><ApartmentOutlined /></template>
          <span class="menu-label">部门沟通流程登记</span>
        </a-menu-item>
      </a-menu>
    </a-layout-sider>

    <a-layout>
      <a-layout-header class="wb-header">
        <div class="wb-header-left">
          <MenuUnfoldOutlined
            v-if="collapsed"
            class="collapse-btn"
            @click="collapsed = false"
          />
          <MenuFoldOutlined v-else class="collapse-btn" @click="collapsed = true" />
          <span>工作台</span>
          <span>/</span>
          <span class="crumb-current">{{ currentTitle }}</span>
        </div>
        <div class="wb-header-right">
          <a-tooltip title="刷新当前页">
            <ReloadOutlined class="header-action" @click="reload" />
          </a-tooltip>
          <a-dropdown placement="bottomRight">
            <div class="wb-user">
              <a-avatar :size="32" style="background: #1677ff">
                {{ avatarText }}
              </a-avatar>
              <div class="wb-user-meta">
                <div class="wb-user-name">{{ displayName || user }}</div>
                <div class="wb-user-role">{{ user }}</div>
              </div>
            </div>
            <template #overlay>
              <a-menu>
                <a-menu-item key="profile" disabled>
                  <UserOutlined /> 账号：{{ user }}
                </a-menu-item>
                <a-menu-divider />
                <a-menu-item key="logout" @click="handleLogout">
                  <LogoutOutlined /> 退出登录
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </a-layout-header>

      <a-layout-content class="wb-content">
        <router-view v-slot="{ Component }">
          <component :is="Component" :key="reloadKey" @refresh-count="loadCount" />
        </router-view>
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ApartmentOutlined,
  BarChartOutlined,
  CheckCircleOutlined,
  ExportOutlined,
  InboxOutlined,
  LogoutOutlined,
  MailOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ReloadOutlined,
  SendOutlined,
  SolutionOutlined,
  SwapOutlined,
  UserOutlined,
  UserSwitchOutlined
} from '@ant-design/icons-vue'
import { authApi, ccApi, getUser, handleApi, isCamundaAdmin, logout, wbApi } from '../api'

const route = useRoute()
const router = useRouter()

const collapsed = ref(false)
const reloadKey = ref(0)
const todoCount = ref(0)
// 我委托出去、对方还没交回的条数：显示在待办中心菜单上，一眼看到有多少活压在别人手里
const delegatingCount = ref(0)
// 未读传阅 / 待承办：两个独立角标，避免与审批待办混成一个数字
const ccUnread = ref(0)
const handlePending = ref(0)
const displayName = ref('')
const user = ref(getUser())
// 控制台菜单的可见性：来自 /auth/me 的 camundaAdmin，接口侧仍会独立校验
const isAdmin = ref(isCamundaAdmin())

const activeKey = computed(() => route.name)
const currentTitle = computed(() => (route.meta && route.meta.title) || '待办中心')
const avatarText = computed(() => {
  const name = displayName.value || user.value || '?'
  return name.slice(0, 1).toUpperCase()
})

function onMenuClick({ key }) {
  router.push({ name: key })
}

function reload() {
  reloadKey.value += 1
  loadCount()
}

async function handleLogout() {
  // 服务端销毁会话（session.invalidate），而不是仅清前端标记
  await logout()
  await router.replace({ name: 'login' })
  // 整页重载：丢掉组件状态、角标与在途请求，避免退回到“看起来还登录着”
  window.location.reload()
}

async function loadCount() {
  // 两个数各自独立：任一接口失败都不能把另一个角标清空
  const [todoRes, delegationRes, ccRes, handleRes] = await Promise.allSettled([
    wbApi.todo({ page: 1, size: 1, type: 'all' }),
    wbApi.pendingDelegationCount(),
    ccApi.unreadCount(),
    handleApi.pendingCount()
  ])
  if (todoRes.status === 'fulfilled') {
    todoCount.value = (todoRes.value.stats && todoRes.value.stats.total) || 0
  }
  if (delegationRes.status === 'fulfilled') {
    delegatingCount.value = (delegationRes.value && delegationRes.value.count) || 0
  }
  if (ccRes.status === 'fulfilled') {
    ccUnread.value = (ccRes.value && ccRes.value.count) || 0
  }
  if (handleRes.status === 'fulfilled') {
    handlePending.value = (handleRes.value && handleRes.value.count) || 0
  }
}

onMounted(async () => {
  loadCount()
  try {
    const me = await authApi.me()
    displayName.value = me.displayName
    // 刷新后内存态丢失，这里按服务端结果重新设置，避免管理员刷新就看不见菜单
    isAdmin.value = !!me.camundaAdmin
  } catch (e) {
    // 401 已由拦截器处理
  }
})

watch(() => route.name, () => loadCount())
</script>

<style scoped>
.menu-label {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.menu-badges {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 委托中：紫色小胶囊，与红色的待办数量区分，避免两个数字混在一起 */
.menu-delegating {
  padding: 0 6px;
  font-size: 11px;
  line-height: 16px;
  color: #d3adf7;
  white-space: nowrap;
  background: rgba(146, 84, 222, 0.3);
  border-radius: 8px;
}

.collapse-btn,
.header-action {
  font-size: 16px;
  color: rgba(0, 0, 0, 0.65);
  cursor: pointer;
  transition: color 0.2s;
}

.collapse-btn:hover,
.header-action:hover {
  color: #1677ff;
}

@media (max-width: 768px) {
  .wb-header-left > span:nth-child(2),
  .wb-header-left > span:nth-child(3) {
    display: none;
  }
}
</style>
