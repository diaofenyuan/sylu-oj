import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './style.css'
import { Icon, addCollection } from '@iconify/vue'
import icons from './assets/icons.json'

// 图标随应用加载，校园网或离线环境也能完整显示操作入口。
addCollection(icons)

const app = createApp(App)
app.component('Icon', Icon)
app.use(router)
app.mount('#app')
