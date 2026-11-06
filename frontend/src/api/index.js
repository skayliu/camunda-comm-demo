import axios from 'axios'

const AUTH_KEY = 'wb-auth'
const USER_KEY = 'wb-user'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.request.use((config) => {
  const auth = localStorage.getItem(AUTH_KEY)
  if (auth) {
    config.headers.Authorization = `Basic ${auth}`
  }
  return config
})

http.interceptors.response.use(
  (res) => res.data,
  (error) => {
    const status = error.response ? error.response.status : 0
    if (status === 401 && !window.location.hash.startsWith('#/login')) {
      localStorage.removeItem(AUTH_KEY)
      localStorage.removeItem(USER_KEY)
      window.location.hash = '#/login'
    }
    return Promise.reject(error)
  }
)

function encodeBasic(user, password) {
  // btoa 只接受 latin1，先转义保证中文账号/密码不抛异常
  return btoa(unescape(encodeURIComponent(`${user}:${password}`)))
}

export function isLoggedIn() {
  return !!localStorage.getItem(AUTH_KEY)
}

export function getUser() {
  return localStorage.getItem(USER_KEY) || ''
}

export function logout() {
  localStorage.removeItem(AUTH_KEY)
  localStorage.removeItem(USER_KEY)
}

export function errorText(error) {
  if (!error) return '未知错误'
  const data = error.response && error.response.data
  if (data && typeof data === 'object' && data.message) return data.message
  if (typeof data === 'string' && data) return data
  if (error.response && error.response.status) {
    const map = {
      400: '请求参数有误',
      401: '登录已失效，请重新登录',
      403: '没有权限执行该操作',
      404: '请求的资源不存在',
      500: '服务端处理失败'
    }
    return map[error.response.status] || `请求失败（${error.response.status}）`
  }
  return error.message || '网络异常，请稍后重试'
}

export const authApi = {
  async login(user, password) {
    localStorage.setItem(AUTH_KEY, encodeBasic(user, password))
    try {
      const me = await http.get('/wb/me')
      localStorage.setItem(USER_KEY, me.userId)
      return me
    } catch (e) {
      logout()
      throw e
    }
  },
  me() {
    return http.get('/wb/me')
  }
}

export const wbApi = {
  todo(params) {
    return http.get('/wb/todo', { params })
  },
  done(params) {
    return http.get('/wb/done', { params })
  },
  started(params) {
    return http.get('/wb/started', { params })
  },
  taskDetail(taskId) {
    return http.get(`/wb/task/${taskId}`)
  },
  /** 转办待办：把任务交给另一个人办理 */
  transfer(taskId, payload) {
    return http.post(`/wb/task/${taskId}/transfer`, payload)
  },
  /** 单条待办的转办链 */
  transfers(taskId) {
    return http.get(`/wb/task/${taskId}/transfers`)
  },
  /** 某个流程实例的全部转办记录（我的发起 / 已办详情共用） */
  processTransfers(processInstanceId) {
    return http.get(`/wb/process/${processInstanceId}/transfers`)
  },
  /** 流程跟踪：BPMN 节点几何 + 节点执行状态与明细 */
  trace(processInstanceId) {
    return http.get(`/wb/trace/${processInstanceId}`)
  },
  /** 发起委托：责任仍在我，受托人只能提交反馈 */
  delegate(taskId, payload) {
    return http.post(`/wb/task/${taskId}/delegate`, payload)
  },
  /** 受托人提交代办意见并交回（resolveTask） */
  resolveDelegation(taskId, comment) {
    return http.post(`/wb/task/${taskId}/delegate/resolve`, { comment })
  },
  /** 委托人收回委托 */
  cancelDelegation(taskId, reason) {
    return http.post(`/wb/task/${taskId}/delegate/cancel`, { reason })
  },
  /** 某个流程实例的全部委托记录（我的发起 / 流程跟踪共用） */
  processDelegations(processInstanceId) {
    return http.get(`/wb/process/${processInstanceId}/delegations`)
  },
  /** 我委托出去的（含已交回 / 已收回，taskActive 表示任务是否仍在运行） */
  delegationsOut() {
    return http.get('/wb/delegations/out')
  },
  /** 我受托的（含已交回 / 被收回） */
  delegationsIn() {
    return http.get('/wb/delegations/in')
  },
  /** 委托中条数（对方未交回且任务仍在运行），用于待办中心角标 */
  pendingDelegationCount() {
    return http.get('/wb/delegations/pending-count')
  }
}

export const agentApi = {
  /** 我的代理规则 */
  rules() {
    return http.get('/agent/rules')
  },
  /** 管理员视图：全部规则 */
  allRules() {
    return http.get('/agent/rules/all')
  },
  save(payload) {
    return http.post('/agent/rules', payload)
  },
  /** 停用规则；cancelActive=true 时同时收回该规则下委托中的任务 */
  disable(id, cancelActive = false) {
    return http.post(`/agent/rules/${id}/disable`, { cancelActive })
  },
  enable(id) {
    return http.post(`/agent/rules/${id}/enable`)
  }
}

export const commApi = {
  start(payload) {
    return http.post('/comm/start', payload)
  },
  status(taskId) {
    return http.get(`/comm/status/${taskId}`)
  },
  reply(payload) {
    return http.post('/comm/reply', payload)
  },
  withdraw(sessionId, commTaskIds) {
    return http.post(`/comm/withdraw/${sessionId}`, { commTaskIds })
  },
  continueCommunication(payload) {
    return http.post('/comm/continue', payload)
  },
  /** 沟通待办里再次发起沟通：当前沟通对象转交他人补充核实 */
  forward(payload) {
    return http.post('/comm/forward', payload)
  },
  complete(taskId, comment) {
    return http.post(`/comm/demo/complete/${taskId}`, { comment })
  },
  startProcess() {
    return http.post('/comm/demo/start')
  },
  targets(keyword) {
    return http.get('/comm/targets', { params: { keyword } })
  }
}

export default http
