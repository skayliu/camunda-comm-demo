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
        <div class="wb-logo-mark">审</div>
        <div v-if="!collapsed">
          <div class="wb-logo-text">流程审批中心</div>
          <div class="wb-logo-sub">Process Collaboration</div>
        </div>
      </div>
      <a-menu
        :selected-keys="[activeKey]"
        mode="inline"
        :theme="isDark ? 'dark' : 'light'"
        @click="onMenuClick"
      >
        <a-menu-item key="todo">
          <template #icon><InboxOutlined /></template>
          <span class="menu-label">
            <span>待办中心</span>
            <span class="menu-badges">
              <a-badge v-if="todoCount > 0" class="menu-badge" :count="todoCount" :overflow-count="99" />
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
            <a-badge v-if="ccUnread > 0" class="menu-badge" :count="ccUnread" :overflow-count="99" />
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
            <a-badge v-if="handlePending > 0" class="menu-badge" :count="handlePending" :overflow-count="99" />
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
          <!-- 收缩/展开图标在左、路径文字在右：图标是操作入口，先于导航信息 -->
          <button
            class="wb-icon-btn wb-collapse-btn"
            type="button"
            :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
            :aria-expanded="!collapsed"
            @click="collapsed = !collapsed"
          >
            <MenuUnfoldOutlined v-if="collapsed" />
            <MenuFoldOutlined v-else />
          </button>
          <nav class="wb-crumb" aria-label="面包屑导航">
            <span class="crumb-home">工作台</span>
            <span class="crumb-sep" aria-hidden="true">/</span>
            <span class="crumb-current" aria-current="page">{{ currentTitle }}</span>
          </nav>
        </div>
        <div class="wb-header-right">
          <a-tooltip title="刷新当前页">
            <button class="wb-icon-btn" type="button" aria-label="刷新当前页" @click="reload">
              <ReloadOutlined />
            </button>
          </a-tooltip>

          <!-- 主题切换：三档，当前档位用勾选图标标出，不只依赖颜色 -->
          <a-dropdown placement="bottomRight" :trigger="['click']">
            <button
              class="wb-icon-btn"
              type="button"
              aria-label="切换主题外观"
              :aria-pressed="isDark ? 'true' : 'false'"
              :title="`外观：${currentThemeLabel}`"
            >
              <BgColorsOutlined />
            </button>
            <template #overlay>
              <a-menu :selected-keys="[themeMode]" class="theme-menu" @click="onThemeClick">
                <a-menu-item
                  v-for="opt in themeOptions"
                  :key="opt.key"
                  :class="{ 'is-current': themeMode === opt.key }"
                >
                  <span class="theme-option">
                    <span class="theme-menu-title">
                      <span class="theme-menu-label">{{ opt.label }}</span>
                      <span class="theme-menu-hint">{{ opt.hint }}</span>
                    </span>
                    <CheckOutlined v-if="themeMode === opt.key" class="theme-check" />
                  </span>
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>

          <a-dropdown placement="bottomRight">
            <div class="wb-user">
              <a-avatar :size="32" style="background: var(--brand)">
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
  BgColorsOutlined,
  CheckCircleOutlined,
  CheckOutlined,
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
import { useTheme } from '../composables/useTheme'

const route = useRoute()
const router = useRouter()

const { mode: themeMode, isDark, options: themeOptions, setMode } = useTheme()
const currentThemeLabel = computed(
  () => (themeOptions.find((opt) => opt.key === themeMode.value) || {}).label || ''
)

function onThemeClick({ key }) {
  setMode(key)
}

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
  /* 文字在左、角标贴右：原先紧跟文字会在右侧留下大片空白，
     推到菜单项右端（仍留 4px 内边距）后，与菜单名的距离由菜单宽度自适应 */
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.menu-badges {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  margin-left: auto;
  /* 右留白交给菜单项自身的 padding-right（antd 16px），这里不再叠加，
     角标才能真正贴到菜单项右端 */
  padding-right: 0;
}

/* 菜单角标改为流式布局：antd 默认把它绝对定位并向右上偏移 50%，
   在 220px 的侧栏里正好压在菜单项的右内边距上，看起来像被裁掉一角 */
:deep(.menu-label .ant-badge) {
  position: static;
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  line-height: 1;
}

:deep(.menu-label .ant-badge .ant-badge-count) {
  position: static;
  transform: none;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  font-size: 11px;
  font-weight: 600;
  line-height: 16px;
  border-radius: var(--radius-pill);
  background: var(--badge-count-bg);
  color: var(--badge-count-text);
  border: 1px solid var(--badge-count-border);
  box-shadow: none;
}

/* 选中项本身是品牌蓝底，角标改用白色描边圈出边界，保证在蓝底上依然可辨 */
:deep(.ant-menu-item-selected .menu-label .ant-badge .ant-badge-count) {
  border-color: rgba(255, 255, 255, 0.55);
}

/* 委托中：紫色小胶囊，与红色的待办数量区分，避免两个数字混在一起 */
.menu-delegating {
  padding: 0 6px;
  font-size: 11px;
  line-height: 16px;
  color: var(--purple);
  white-space: nowrap;
  background: var(--purple-soft);
  border-radius: 8px;
}

.wb-crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 13.5px;
  color: var(--text-tertiary);
}

.crumb-home {
  white-space: nowrap;
}

.crumb-sep {
  color: var(--text-disabled);
}

.wb-crumb .crumb-current {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 折叠按钮位于顶栏最左，面包屑紧随其后（间距由 .wb-header-left 的 gap 控制） */
.wb-collapse-btn {
  flex: 0 0 auto;
  color: var(--text-tertiary);
}

@media (max-width: 768px) {
  .wb-crumb .crumb-home,
  .wb-crumb .crumb-sep {
    display: none;
  }
}
</style>
