import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { AntdSubpathResolver } from './vite/antd-subpath-resolver.js'

// 构建产物直接输出到 Spring Boot 静态目录，部署后与后端同源，
// 无需额外配置 CORS / 反向代理。
export default defineConfig({
  plugins: [
    vue(),
    // 编译期按 SFC 收集模板里的 a-xxx 标签，只注入该文件真正用到的组件。
    // dirs 置空：本地组件（TaskDrawer、ProcessTraceModal 等）一律显式 import，
    // 不交给自动扫描，避免同一组件被重复注册、也避免误改本地组件的引入方式。
    Components({
      dts: false,
      dirs: [],
      resolvers: [
        AntdSubpathResolver({
          // 只处理 ant-* 的标签；图标全部显式 import，不参与自动解析
          prefix: 'A'
        })
      ]
    })
  ],
  base: '/app/',
  build: {
    // Vite 8 默认目标已提到 Chrome 111 / Safari 16.4。
    // 内网仍有 Win7、国产双核或旧 CEF 内核，这里显式降到 chrome87 兜底；
    // 注意 plugin-legacy 在 Vite 8 下不再支持 ES5，只能靠 target 控制。
    target: 'chrome87',
    outDir: '../src/main/resources/static/app',
    emptyOutDir: true
    // 不自定义 codeSplitting 分组：手工分组（尤其是把 @ant-design/icons-vue
    // 单独拆出）会产生 chunk 级循环依赖，运行时读到未初始化的色板对象而白屏。
    // 交给 Rolldown 按模块图自动分块，保证求值顺序正确。
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
})
