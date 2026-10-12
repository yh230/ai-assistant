import { ref } from 'vue'
import { getInfo } from '../api/auth'

const TOKEN_KEY = 'ai_token'
const USER_KEY = 'ai_user'

function parseUser() {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

// 模块级单例状态（项目未引入 Pinia，使用 vue 的 ref 作为轻量全局 store）
const token = ref(localStorage.getItem(TOKEN_KEY) || '')
const userInfo = ref(parseUser())
// 本次会话是否已通过认证（避免每次路由切换都重复拉 /api/getInfo 探活）
const authed = ref(false)

function setToken(t) {
  token.value = t || ''
  if (t) localStorage.setItem(TOKEN_KEY, t)
  else localStorage.removeItem(TOKEN_KEY)
}

function setUser(u) {
  userInfo.value = u || null
  if (u) localStorage.setItem(USER_KEY, JSON.stringify(u))
  else localStorage.removeItem(USER_KEY)
}

function setAuthed(v) {
  authed.value = !!v
}

function clear() {
  setToken('')
  setUser(null)
  setAuthed(false)
}

// 登录后拉取当前用户信息（token 已写入 localStorage，拦截器会自动带上）
async function loadUserInfo() {
  const info = await getInfo()
  setUser(info)
  setAuthed(true)
  return info
}

export function useAuth() {
  return {
    token,
    userInfo,
    authed,
    setToken,
    setUser,
    setAuthed,
    clear,
    loadUserInfo
  }
}
