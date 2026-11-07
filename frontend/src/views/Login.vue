<template>
  <div class="login-page">
    <div class="login-brand">
      <div>
        <div class="wb-logo" style="padding: 0; height: auto">
          <div class="wb-logo-mark">协</div>
          <div>
            <div class="wb-logo-text">沟通待办中心</div>
            <div class="wb-logo-sub">Process Collaboration</div>
          </div>
        </div>
        <h1 class="login-brand-title" style="margin-top: 48px">
          流程在走，沟通同步
        </h1>
        <p class="login-brand-desc">
          待办、审批、沟通反馈收在一处。审批节点可直接向相关人员发起沟通，
          反馈齐全后方可继续流转，全过程可追溯。
        </p>
      </div>
      <div class="login-features">
        <div class="login-feature">
          <span class="login-feature-dot"></span>
          <span>待办集中处理：审批待办与沟通待办统一入口</span>
        </div>
        <div class="login-feature">
          <span class="login-feature-dot"></span>
          <span>沟通不打断流程：旁路任务独立存在，不影响主流程流转</span>
        </div>
        <div class="login-feature">
          <span class="login-feature-dot"></span>
          <span>已办与发起全程留痕：谁处理、何时处理、意见为何</span>
        </div>
      </div>
    </div>

    <div class="login-panel">
      <div class="login-card">
        <h2>登录</h2>
        <p class="login-sub">使用流程账号登录，进入你的待办中心</p>

        <a-form layout="vertical" @submit.prevent="submit">
          <a-form-item label="账号" :validate-status="error ? 'error' : ''">
            <a-input
              v-model:value="form.user"
              size="large"
              placeholder="请输入账号"
              allow-clear
            >
              <template #prefix><UserOutlined /></template>
            </a-input>
          </a-form-item>
          <a-form-item label="密码" :validate-status="error ? 'error' : ''">
            <a-input-password
              v-model:value="form.password"
              size="large"
              placeholder="请输入密码"
            >
              <template #prefix><LockOutlined /></template>
            </a-input-password>
          </a-form-item>
          <a-alert
            v-if="notice"
            type="warning"
            :message="notice"
            show-icon
            style="margin-bottom: 16px"
          />
          <a-alert
            v-if="error"
            type="error"
            :message="error"
            show-icon
            style="margin-bottom: 16px"
          />
          <a-button
            type="primary"
            size="large"
            block
            :loading="loading"
            html-type="submit"
          >
            登录
          </a-button>
        </a-form>

        <div class="login-tip">
          演示账号（密码均为 <code>demo</code>）：<br />
          <a-space wrap :size="6" style="margin-top: 6px">
            <a-tag
              v-for="name in demoUsers"
              :key="name"
              style="cursor: pointer; margin: 0"
              :color="form.user === name ? 'blue' : undefined"
              @click="fill(name)"
            >
              {{ name }}
            </a-tag>
          </a-space>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LockOutlined, UserOutlined } from '@ant-design/icons-vue'
import { authApi, errorText } from '../api'

const route = useRoute()
const router = useRouter()

const demoUsers = ['applicant', 'manager', 'finance', 'alice', 'bob', 'carol']
const form = reactive({ user: 'manager', password: 'demo' })
const loading = ref(false)
const error = ref('')

/**
 * 被其它设备顶下线时的提示。
 * 由 axios 401 拦截器写入 sessionStorage，这里读完即删，避免下次刷新还挂着。
 */
const notice = ref('')
try {
  notice.value = sessionStorage.getItem('wb-kicked') || ''
  sessionStorage.removeItem('wb-kicked')
} catch (e) {
  notice.value = ''
}

function fill(name) {
  notice.value = ''
  form.user = name
  form.password = 'demo'
  error.value = ''
}

async function submit() {
  error.value = ''
  notice.value = ''
  if (!form.user || !form.password) {
    error.value = '请输入账号与密码'
    return
  }
  loading.value = true
  try {
    await authApi.login(form.user.trim(), form.password)
    const redirect = route.query.redirect
    router.replace(redirect || '/todo')
  } catch (e) {
    error.value =
      e && e.response && e.response.status === 401
        ? '账号或密码错误'
        : errorText(e)
  } finally {
    loading.value = false
  }
}
</script>
