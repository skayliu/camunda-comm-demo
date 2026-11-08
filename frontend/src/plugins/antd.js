// 【已废弃，请勿使用】
//
// 这里原本是 ant-design-vue 的全局按需注册入口（28 个组件在此 app.use）。
// 现在改为编译期按 SFC 注入：
//   - 插件配置：frontend/vite.config.js 中的 Components()
//   - 组件映射：frontend/vite/antd-subpath-resolver.js 的 COMPONENTS
//   - 入口注册：frontend/src/main.js 已不再 use 任何组件库插件
//
// 保留此文件仅因它仍被 git 跟踪，下次提交时请直接删除。
// 不要重新 import 它，否则入口会再次静态依赖整个组件库。
export {}
