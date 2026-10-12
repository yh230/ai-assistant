import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './assets/style.css'
import './utils/message' // 必须在业务模块之前，拦截 window.alert

const app = createApp(App)
app.use(router)
app.mount('#app')
