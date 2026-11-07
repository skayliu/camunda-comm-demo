import { createRouter, createWebHashHistory } from 'vue-router'
import { ensureAuth } from '../api'

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
router.beforeEach(async (to) => {
  const loggedIn = await ensureAuth()
  if (!to.meta.public && !loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && loggedIn) {
    return { name: 'todo' }
  }
  return true
})

export default router
