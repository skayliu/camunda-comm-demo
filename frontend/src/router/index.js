import { createRouter, createWebHashHistory } from 'vue-router'
import { isLoggedIn } from '../api'

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

router.beforeEach((to) => {
  if (!to.meta.public && !isLoggedIn()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && isLoggedIn()) {
    return { name: 'todo' }
  }
  return true
})

export default router
