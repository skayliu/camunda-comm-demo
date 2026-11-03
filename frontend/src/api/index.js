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
