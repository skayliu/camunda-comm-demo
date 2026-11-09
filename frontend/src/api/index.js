import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000, withCredentials: true })

// 旧版本把 Base64(user:password) 存在 localStorage 的 wb-auth 里。
// 升级到会话登录后统一清掉，避免“看着像还登录着”的残留标记。
try {
  localStorage.removeItem('wb-auth')
  localStorage.removeItem('wb-user')
} catch (e) {
  // 隐私模式下 localStorage 可能不可用，忽略即可
}

// 登录态只放在内存里：会话是否有效由服务端回答，前端不再自己编一个标记
let currentUser = ''
// 是否为 camunda-admin：只用于决定菜单/路由是否显示，接口侧会独立校验
let currentAdmin = false
let authChecked = null

// ===== CSRF =====
// 会话登录后身份凭据是浏览器自动携带的 Cookie，跨站页面也能顺带发出，
// 所以写操作必须带 CSRF 令牌。服务端把令牌写在 XSRF-TOKEN cookie 里（非 HttpOnly），
// 前端把它放进 X-XSRF-TOKEN 请求头，两边一致才放行。
const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'
const UNSAFE_METHODS = ['post', 'put', 'patch', 'delete']

function readCookie(name) {
  try {
    const prefix = name + '='
    const hit = document.cookie.split('; ').find((item) => item.startsWith(prefix))
    return hit ? decodeURIComponent(hit.slice(prefix.length)) : ''
  } catch (e) {
    return ''
  }
}

// 令牌只放内存：接口返回优先，读不到再退回 cookie。
// 刷新页面后内存清空，ensureCsrf 会再取一次，不需要持久化。
let csrfToken = ''
let csrfPending = null

export function ensureCsrf(force = false) {
  if (!force && csrfToken) return Promise.resolve(csrfToken)
  if (!force && csrfPending) return csrfPending
  csrfPending = http
    .get('/auth/csrf')
    .then((data) => {
      csrfToken = (data && data.token) || readCookie(CSRF_COOKIE)
      return csrfToken
    })
    .catch(() => {
      csrfToken = readCookie(CSRF_COOKIE)
      return csrfToken
    })
    .finally(() => {
      csrfPending = null
    })
  return csrfPending
}

http.interceptors.request.use(async (config) => {
  const method = (config.method || 'get').toLowerCase()
  if (UNSAFE_METHODS.includes(method)) {
    const token = await ensureCsrf()
    if (token) {
      config.headers[CSRF_HEADER] = token
    }
  }
  return config
})

http.interceptors.response.use(
  (res) => res.data,
  async (error) => {
    const status = error.response ? error.response.status : 0
    const data = error.response && error.response.data
    const config = error.config

    // 令牌缺失或失效（cookie 被清、页面放太久、切换用户）：重取一次再重试原请求。
    // 只重试一次，避免服务端持续拒绝时打成死循环。
    if (status === 403 && data && data.code === 'CSRF_INVALID' && config && !config.__csrfRetried) {
      config.__csrfRetried = true
      try {
        await ensureCsrf(true)
        return await http(config)
      } catch (retryError) {
        return Promise.reject(retryError)
      }
    }

    // 登录态探测（/auth/me）的 401 是“尚未登录”的预期结果，不是故障：
    // 打上 __silent 供统一错误提示跳过，调用方按返回值处理即可。
    // 注意这只能挡住提示与跳转，挡不住浏览器 Console 里的那条网络日志——
    // 那条只有“根本不发这个请求”才能消除，所以登录页守卫不再探测登录态。
    if (status === 401 && config && String(config.url || '').includes('/auth/me')) {
      error.__silent = true
    }

    if (status === 401 && !window.location.hash.startsWith('#/login')) {
      currentUser = ''
      authChecked = null
      // 单账号单会话：新设备登录后旧会话会收到“已在其它设备登录”。
      // 这不是密码错误，登录页要能说清原因，否则用户会以为是自己输错了。
      const message = error.response && error.response.data && error.response.data.message
      if (message && message.includes('其它设备')) {
        try {
          sessionStorage.setItem('wb-kicked', message)
        } catch (e) {
          // 隐私模式下 sessionStorage 可能不可用，提示丢失不影响跳转
        }
      }
      window.location.hash = '#/login'
    }
    return Promise.reject(error)
  }
)

/**
 * 确认登录态：GET /api/auth/me。
 *
 * 刷新页面后内存态丢失，但 JSESSIONID 还在，所以要以服务端结果为准。
 * 结果缓存在 authChecked 中，避免每次路由跳转都打一次接口。
 */
export function ensureAuth() {
  if (!authChecked) {
    authChecked = http
      .get('/auth/me')
      .then((me) => {
        currentUser = me.userId
        currentAdmin = !!me.camundaAdmin
        return true
      })
      .catch(() => {
        currentUser = ''
        currentAdmin = false
        authChecked = null
        return false
      })
  }
  return authChecked
}

export function isLoggedIn() {
  return !!currentUser
}

export function getUser() {
  return currentUser
}

/** 当前用户是否属于 camunda-admin（控制台管理功能的可见性依据） */
export function isCamundaAdmin() {
  return currentAdmin
}

export async function logout() {
  try {
    await http.post('/auth/logout')
  } catch (e) {
    // 会话可能已过期，退出本身应当幂等，继续清本地状态
  }
  currentUser = ''
  currentAdmin = false
  authChecked = null
  // 令牌本身留在 cookie 里（服务端也没清），内存这份丢掉即可，
  // 下次登录前 ensureCsrf 会重新取
  csrfToken = ''
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
    const me = await http.post('/auth/login', { userId: user, password })
    currentUser = me.userId
    currentAdmin = !!me.camundaAdmin
    authChecked = Promise.resolve(true)
    return me
  },
  me() {
    return http.get('/auth/me')
  },
  logout
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
  /** 受托人提交受托意见并交回（resolveTask） */
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
  /** 我的委托（含已交回 / 已收回，taskActive 表示任务是否仍在运行） */
  delegationsOut() {
    return http.get('/wb/delegations/out')
  },
  /** 我的受托（含已交回 / 被收回） */
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
  /** 沟通待办里再次发起沟通：当前沟通对象向其他人再发起一条沟通 */
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
  },
  /** 可选沟通部门：部门沟通走独立子流程，前端据此以 type=DEPT 提交 */
  departments(keyword) {
    return http.get('/comm/departments', { params: { keyword } })
  },
  /** 某部门可选的部门沟通流程（通用三节点 + 部门专属） */
  subprocesses(deptId) {
    return http.get('/comm/subprocesses', { params: { deptId } })
  }
}

/**
 * 控制台 · 部门沟通流程登记维护（仅 camunda-admin）。
 *
 * 服务端每个接口都会独立校验组归属，这里的调用不会因为前端藏了入口而变成"安全"，
 * 但能让非管理员少看到一次 403。
 */
export const adminSubProcessApi = {
  /** 全部登记记录（含停用），附带引擎版本与部署状态 */
  list(keyword) {
    return http.get('/admin/subprocess-defs', { params: { keyword } })
  },
  /** 归属部门候选（DEPARTMENT 类型的组 + 成员数） */
  departments(keyword) {
    return http.get('/admin/subprocess-defs/departments', { params: { keyword } })
  },
  /** id 为空新增，否则更新 */
  save(payload) {
    return http.post('/admin/subprocess-defs', payload)
  },
  setStatus(id, status) {
    return http.post(`/admin/subprocess-defs/${id}/status`, { status })
  },
  remove(id) {
    return http.delete(`/admin/subprocess-defs/${id}`)
  },
  /** 从引擎全量对齐一次（部署钩子漏掉时的兜底） */
  sync() {
    return http.post('/admin/subprocess-defs/sync')
  },
  /**
   * 流程图查看：取引擎中该 key 部署产出的 BPMN XML。
   * 不传 version 取最新版本；传 version 可核对指定历史版本。
   */
  bpmnXml(processKey, version) {
    return http.get(`/admin/subprocess-defs/${encodeURIComponent(processKey)}/bpmn-xml`, {
      params: version ? { version } : {}
    })
  }
}

/** 部门沟通流程的待办办理 */
export const deptSubApi = {
  detail(taskId) {
    return http.get(`/comm/dept-sub/${taskId}`)
  },
  /** 文书分发：选本部门经办人与意见规则 */
  dispatch(taskId, payload) {
    return http.post(`/comm/dept-sub/${taskId}/dispatch`, payload)
  },
  /** 通用流程：经办人提交意见 */
  opinion(taskId, payload) {
    return http.post(`/comm/dept-sub/${taskId}/opinion`, payload)
  },
  /** 专属流程：中间岗位节点提交意见 */
  nodeOpinion(taskId, payload) {
    return http.post(`/comm/dept-sub/${taskId}/node-opinion`, payload)
  },
  /** 汇总节点：提交 / 退回补充 / 重新分发 */
  summary(taskId, payload) {
    return http.post(`/comm/dept-sub/${taskId}/summary`, payload)
  }
}

/**
 * 传阅：只送达、不待办、不阻塞流程。
 * 传阅人只有“查看/标已读”，没有任何审批操作入口。
 */
export const ccApi = {
  add(payload) {
    return http.post('/cc/add', payload)
  },
  mine(params) {
    return http.get('/cc/mine', { params })
  },
  /** 我的传阅：status = ALL / UNREAD / READ / REVOKED */
  sent(params) {
    return http.get('/cc/sent', { params })
  },
  /** 我的传阅 · 按流程聚合：一眼看清某流程传阅给谁、谁还没读 */
  sentGrouped(params) {
    return http.get('/cc/sent/grouped', { params })
  },
  unreadCount() {
    return http.get('/cc/unread-count')
  },
  markRead(id) {
    return http.post(`/cc/${id}/read`)
  },
  batchMarkRead(ids) {
    return http.post('/cc/read-batch', { ids })
  },
  revoke(id) {
    return http.post(`/cc/${id}/revoke`)
  },
  /** 按流程批量撤回：只撤本人发出、对方未读的记录 */
  revokeByProcess(processInstanceId) {
    return http.post(`/cc/process/${processInstanceId}/revoke`)
  },
  processRecords(processInstanceId) {
    return http.get(`/cc/process/${processInstanceId}`)
  },
  targets(keyword) {
    return http.get('/cc/targets', { params: { keyword } })
  }
}

/**
 * 阅办（承办）：有办理时限与结果，但不进审批待办、不阻塞主流程。
 */
export const handleApi = {
  add(payload) {
    return http.post('/handle', payload)
  },
  mine(params) {
    return http.get('/handle/mine', { params })
  },
  /** 我发出的阅办：status = ALL / PENDING / HANDLING / OVERDUE / DONE / REVOKED */
  sent(params) {
    return http.get('/handle/sent', { params })
  },
  pendingCount() {
    return http.get('/handle/pending-count')
  },
  start(id) {
    return http.post(`/handle/${id}/start`)
  },
  submit(id, result) {
    return http.post(`/handle/${id}/submit`, { result })
  },
  revoke(id) {
    return http.post(`/handle/${id}/revoke`)
  },
  processRecords(processInstanceId) {
    return http.get(`/handle/process/${processInstanceId}`)
  },
  users(keyword) {
    return http.get('/handle/users', { params: { keyword } })
  }
}

/**
 * 传阅 / 承办统计报表（仅 camunda-admin）。
 * 服务端只读聚合，不会改传阅已读状态，也不会动承办进度。
 */
export const reportApi = {
  /** days=0 表示全部历史 */
  ccHandle(params) {
    return http.get('/report/cc-handle', { params })
  }
}

export default http
