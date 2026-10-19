import { createAuthHttp } from './http'

/**
 * 员工端（管理端）HTTP 客户端
 * 统一由 createAuthHttp 生成：401 静默续期、跨 tab 串行刷新、登出同步。
 */
const auth = createAuthHttp({
  tokenKey: 'ai_token',
  refreshKey: 'ai_refresh_token',
  userKey: 'ai_user',
  loginPath: '/login'
})

const api = auth.http

export default api
export const refreshAccessToken = auth.refreshAccessToken
export const forceToLogin = auth.forceLogin
