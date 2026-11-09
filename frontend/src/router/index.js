import { createRouter, createWebHashHistory } from 'vue-router'
import { ensureAuth, isCamundaAdmin, isLoggedIn } from '../api'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('../layouts/BasicLayout.vue'),
    redirect: '/todo',
    children: [
      {
        path: 'todo',
        name: 'todo',
        component: () => import('../views/TodoCenter.vue'),
        meta: { title: '待办中心' }
      },
      {
        path: 'done',
        name: 'done',
        component: () => import('../views/DoneList.vue'),
        meta: { title: '我的已办' }
      },
      {
        path: 'started',
        name: 'started',
        component: () => import('../views/StartedList.vue'),
        meta: { title: '我的发起' }
      },
      {
        path: 'cc',
        name: 'cc',
        component: () => import('../views/CcList.vue'),
        meta: { title: '我的待阅' }
      },
      {
        // 发送人视角：我传阅给了谁、对方读了没有、哪条还没送达可以撤回。
        // 与“我的待阅”共用同一张业务表，接口是 /api/cc/sent（发送人取认证上下文）。
        path: 'cc-sent',
        name: 'cc-sent',
        component: () => import('../views/CcSentList.vue'),
        meta: { title: '我的传阅' }
      },
      {
        path: 'handle',
        name: 'handle',
        component: () => import('../views/HandleList.vue'),
        meta: { title: '我的承办' }
      },
      {
        path: 'agent',
        name: 'agent',
        component: () => import('../views/AgentRules.vue'),
        meta: { title: '代理设置' }
      },
      {
        path: 'delegation',
        name: 'delegation',
        component: () => import('../views/DelegationList.vue'),
        meta: { title: '委托记录' }
      },
      {
        // 传阅 / 承办统计：含“谁传阅给谁、谁逾期未办”的全局数据，
        // 与登记页一样只对 camunda-admin 开放，接口侧 /api/report/** 会再校验一次。
        path: 'cc-handle-report',
        name: 'cc-handle-report',
        component: () => import('../views/CcHandleReport.vue'),
        meta: { title: '传阅与承办统计', adminOnly: true }
      },
      {
        // 控制台管理功能：只对 camunda-admin 开放。
        // 前端守卫只是减少误入，接口侧 /api/admin/** 每个方法都会再校验一次。
        path: 'subprocess-defs',
        name: 'subprocess-defs',
        component: () => import('../views/SubProcessDefs.vue'),
        meta: { title: '部门沟通流程登记', adminOnly: true }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/todo' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 登录态由服务端会话决定，守卫必须等 /api/auth/me 的结果，
// 不能再用“localStorage 里有没有某个键”来判断——那个键谁都能手动写上。
//
// 但公开路由（登录页）不能再去探测：未登录时 /api/auth/me 必然 401，
// 浏览器会把这条 401 作为网络错误记进 Console，看起来像系统故障，
// 实际上只是“还没登录”这一正常状态。公开页只需要知道“本次会话内是否已确认登录”：
// 已确认（isLoggedIn）就送回待办，否则直接放行，不再多发一次必败的请求。
// 副作用：已登录状态下手动打开 /app/login 会看到登录表单（刷新后内存态清空，
// 无法在零请求的前提下得知会话仍有效）。正常入口 /app 不受影响，仍会探测并直达待办。
router.beforeEach(async (to) => {
  if (to.meta.public) {
    if (to.name === 'login' && isLoggedIn()) {
      return { name: 'todo' }
    }
    return true
  }
  const loggedIn = await ensureAuth()
  if (!loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 管理页需要 camunda-admin：ensureAuth 已把成员身份取回内存。
  // 非管理员直接送回待办，不展示一个点进去必然 403 的页面。
  if (to.meta.adminOnly && !isCamundaAdmin()) {
    return { name: 'todo' }
  }
  return true
})

export default router
