import { createApp } from 'vue'
import 'ant-design-vue/dist/reset.css'
import './styles/global.css'
import App from './App.vue'
import router from './router'

// ant-design-vue 组件不再在这里全量注册：
// 模板里的 a-xxx 由 unplugin-vue-components 在编译期按 SFC 注入（见 vite.config.js），
// 这样登录页不会为待办页的 Table / DatePicker 付出下载体积。
// 命令式 API（message）仍从 ant-design-vue/es/message 显式引入。
createApp(App).use(router).mount('#app')
