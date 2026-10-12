import api from './index'

/**
 * 查询城市天气
 */
export function getWeather(city) {
  return api.get('/weather', { params: { city } })
}
