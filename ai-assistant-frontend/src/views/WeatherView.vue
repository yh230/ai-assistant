<template>
  <div class="weather-view">
    <!-- 搜索区域 -->
    <div class="search-section">
      <h1 class="page-title">天气查询</h1>
      <p class="page-subtitle">输入城市名称，查询实时天气和未来5天预报</p>
      <div class="search-box">
        <svg class="search-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8"/>
          <line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <input
          v-model="city"
          class="search-input"
          placeholder="输入城市名，如：北京、上海、深圳..."
          @keydown.enter="searchWeather"
        />
        <button class="search-btn" :disabled="!city.trim() || loading" @click="searchWeather">
          查询天气
        </button>
      </div>

      <!-- 快捷城市 -->
      <div class="quick-cities">
        <span class="quick-label">快捷查询：</span>
        <span
          v-for="c in quickCities"
          :key="c"
          class="quick-city"
          @click="quickSearch(c)"
        >{{ c }}</span>
      </div>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="loading-state">
      <div class="loader"></div>
      <p>正在查询天气数据...</p>
    </div>

    <!-- 查询结果 -->
    <div v-if="weather && weather.success && !loading" class="weather-result">
      <!-- 当前天气卡片 -->
      <div class="current-weather">
        <div class="current-main">
          <div class="weather-icon-large">{{ getWeatherIcon(weather.weatherCode) }}</div>
          <div class="temp-info">
            <span class="current-temp">{{ Math.round(weather.currentTemperature) }}°</span>
            <span class="weather-desc">{{ weather.weatherDescription }}</span>
          </div>
        </div>
        <div class="current-meta">
          <h2 class="city-name">{{ weather.city }}</h2>
          <p class="country-name">{{ weather.country }}</p>
          <p class="coords">{{ weather.latitude?.toFixed(2) }}°, {{ weather.longitude?.toFixed(2) }}°</p>
        </div>
        <div class="current-details">
          <div class="detail-item">
            <span class="detail-label">体感温度</span>
            <span class="detail-value">{{ Math.round(weather.apparentTemperature) }}°C</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">相对湿度</span>
            <span class="detail-value">{{ weather.humidity }}%</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">降水量</span>
            <span class="detail-value">{{ weather.precipitation }} mm</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">风速</span>
            <span class="detail-value">{{ Math.round(weather.windSpeed) }} km/h {{ weather.windDirection }}</span>
          </div>
        </div>
      </div>

      <!-- 未来预报 -->
      <div v-if="weather.dailyForecasts && weather.dailyForecasts.length" class="forecast-section">
        <h3 class="forecast-title">未来 5 天预报</h3>
        <div class="forecast-list">
          <div
            v-for="(day, index) in weather.dailyForecasts"
            :key="index"
            class="forecast-card"
          >
            <span class="forecast-date">{{ formatForecastDate(day.date, index) }}</span>
            <span class="forecast-icon">{{ getWeatherIcon(day.weatherCode) }}</span>
            <span class="forecast-desc">{{ day.description }}</span>
            <div class="forecast-temps">
              <span class="temp-max">{{ Math.round(day.maxTemperature) }}°</span>
              <span class="temp-min">{{ Math.round(day.minTemperature) }}°</span>
            </div>
            <div class="forecast-precip">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/>
              </svg>
              {{ day.precipitationProbability }}%
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 错误提示 -->
    <div v-if="weather && !weather.success && !loading" class="error-state">
      <span class="error-icon">🔍</span>
      <p>{{ weather.error || '查询失败，请检查城市名称后重试' }}</p>
    </div>

    <!-- 空状态 -->
    <div v-if="!weather && !loading" class="empty-state">
      <div class="empty-icon">🌤️</div>
      <p>输入城市名开始查询天气</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { getWeather } from '../api/weather'

const city = ref('')
const weather = ref(null)
const loading = ref(false)

const quickCities = ['北京', '上海', '深圳', '广州', '杭州', '成都', '武汉', '南京']

async function searchWeather() {
  const c = city.value.trim()
  if (!c) return

  loading.value = true
  weather.value = null

  try {
    const data = await getWeather(c)
    weather.value = data
  } catch (e) {
    weather.value = {
      success: false,
      city: c,
      error: e.message || '查询失败，请稍后重试'
    }
  } finally {
    loading.value = false
  }
}

function quickSearch(c) {
  city.value = c
  searchWeather()
}

// 天气图标映射
function getWeatherIcon(code) {
  if (code === 0) return '☀️'
  if (code <= 2) return '🌤️'
  if (code === 3) return '☁️'
  if (code === 45 || code === 48) return '🌫️'
  if (code >= 51 && code <= 57) return '🌦️'
  if (code >= 61 && code <= 67) return '🌧️'
  if (code >= 71 && code <= 77) return '❄️'
  if (code >= 80 && code <= 82) return '🌧️'
  if (code >= 85 && code <= 86) return '🌨️'
  if (code >= 95) return '⛈️'
  return '🌡️'
}

// 格式化预报日期
function formatForecastDate(dateStr, index) {
  if (index === 0) return '今天'
  if (index === 1) return '明天'
  const d = new Date(dateStr)
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return weekdays[d.getDay()]
}
</script>

<style scoped>
.weather-view {
  flex: 1;
  overflow-y: auto;
  padding: 32px;
}

/* 搜索区域 */
.search-section {
  text-align: center;
  margin-bottom: 32px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
}

.page-subtitle {
  color: var(--text-secondary);
  margin-top: 8px;
  font-size: 14px;
}

.search-box {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 560px;
  margin: 20px auto 0;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  padding: 6px 6px 6px 16px;
  box-shadow: var(--shadow-sm);
  transition: var(--transition);
}

.search-box:focus-within {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-bg);
}

.search-icon {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.search-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 15px;
  padding: 10px 0;
  color: var(--text-primary);
}

.search-btn {
  padding: 10px 24px;
  background: var(--primary);
  color: white;
  border-radius: 12px;
  font-size: 14px;
  transition: var(--transition);
}

.search-btn:hover:not(:disabled) {
  background: var(--primary-dark);
}

.search-btn:disabled {
  background: var(--text-tertiary);
  cursor: not-allowed;
}

.quick-cities {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 16px;
  flex-wrap: wrap;
}

.quick-label {
  color: var(--text-tertiary);
  font-size: 13px;
}

.quick-city {
  padding: 4px 12px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: var(--transition);
}

.quick-city:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-bg);
}

/* 加载状态 */
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 60px;
  color: var(--text-secondary);
}

.loader {
  width: 40px;
  height: 40px;
  border: 3px solid var(--border-color);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 天气结果 */
.weather-result {
  max-width: 800px;
  margin: 0 auto;
}

/* 当前天气 */
.current-weather {
  background: linear-gradient(135deg, #6366f1, #818cf8);
  border-radius: 20px;
  padding: 32px;
  color: white;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 24px;
  align-items: center;
  box-shadow: var(--shadow-lg);
}

.current-main {
  display: flex;
  align-items: center;
  gap: 16px;
}

.weather-icon-large {
  font-size: 56px;
}

.temp-info {
  display: flex;
  flex-direction: column;
}

.current-temp {
  font-size: 56px;
  font-weight: 300;
  line-height: 1;
}

.weather-desc {
  font-size: 16px;
  opacity: 0.9;
  margin-top: 4px;
}

.current-meta {
  text-align: center;
}

.city-name {
  font-size: 22px;
  font-weight: 600;
}

.country-name {
  opacity: 0.8;
  font-size: 14px;
  margin-top: 4px;
}

.coords {
  opacity: 0.6;
  font-size: 12px;
  margin-top: 4px;
}

.current-details {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-label {
  font-size: 12px;
  opacity: 0.7;
}

.detail-value {
  font-size: 16px;
  font-weight: 500;
}

/* 预报区域 */
.forecast-section {
  margin-top: 24px;
}

.forecast-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 12px;
}

.forecast-list {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
}

.forecast-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  transition: var(--transition);
}

.forecast-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow);
  border-color: var(--primary-light);
}

.forecast-date {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}

.forecast-icon {
  font-size: 32px;
}

.forecast-desc {
  font-size: 12px;
  color: var(--text-secondary);
}

.forecast-temps {
  display: flex;
  gap: 8px;
  align-items: center;
}

.temp-max {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.temp-min {
  font-size: 14px;
  color: var(--text-tertiary);
}

.forecast-precip {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--info);
}

/* 错误状态 */
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 60px;
  color: var(--text-secondary);
}

.error-icon {
  font-size: 48px;
}

/* 空状态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 60px;
  color: var(--text-tertiary);
}

.empty-icon {
  font-size: 64px;
}

/* 响应式 */
@media (max-width: 768px) {
  .current-weather {
    grid-template-columns: 1fr;
    text-align: center;
    gap: 16px;
  }

  .current-main {
    justify-content: center;
  }

  .current-details {
    grid-template-columns: 1fr 1fr;
  }

  .forecast-list {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
