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
          <div class="wb-logo-text">沟通待办中心</div>
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
            待办中心
            <a-badge v-if="todoCount > 0" :count="todoCount" :overflow-count="99" />
          </span>
        </a-menu-item>
        <a-menu-item key="done">
          <template #icon><CheckCircleOutlined /></template>
          <span class="menu-label">我的已办</span>
        </a-menu-item>
        <a-menu-item key="started">
          <template #icon><SendOutlined /></template>
          <span class="menu-label">我的发起</span>
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
  CheckCircleOutlined,
  InboxOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ReloadOutlined,
  SendOutlined,
  UserOutlined
} from '@ant-design/icons-vue'
import { authApi, getUser, logout, wbApi } from '../api'

const route = useRoute()
const router = useRouter()

const collapsed = ref(false)
const reloadKey = ref(0)
const todoCount = ref(0)
const displayName = ref('')
const user = ref(getUser())

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

function handleLogout() {
  logout()
  router.push({ name: 'login' })
}

async function loadCount() {
  try {
    const res = await wbApi.todo({ page: 1, size: 1, type: 'all' })
    todoCount.value = (res.stats && res.stats.total) || 0
  } catch (e) {
    // 顶栏徽标失败不打断页面
  }
}

onMounted(async () => {
  loadCount()
  try {
    const me = await authApi.me()
    displayName.value = me.displayName
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
