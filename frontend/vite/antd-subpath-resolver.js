// ant-design-vue 编译期按 SFC 注入用的 resolver。
//
// 为什么不用 unplugin-vue-components 自带的 AntDesignVueResolver：
// 它生成的 import 统一指向聚合入口 `ant-design-vue/es`，所有页面因此共享同一个
// module，分包时必然被提升成一个巨大的公共 chunk，首屏照样下载整个组件库。
// 这里把组件名映射到各自子路径（ant-design-vue/es/tag、.../table），
// 让未在某个页面出现的组件根本不出现在该页的依赖图里。
//
// 子组件（a-menu-item 等）走子路径的具名导出：
//   menu/index.js        -> export { MenuItem, MenuDivider }
//   input/index.js       -> export { Textarea, InputSearch, InputPassword }
//   descriptions/index.js-> export { DescriptionsItem }
//   tabs/index.js        -> export { TabPane }
//   radio/index.js       -> export { RadioGroup }
//   select/index.js      -> export { SelectOption }
//   form/index.js        -> export { FormItem }
//   layout/index.js      -> export { LayoutSider, LayoutHeader, LayoutContent, LayoutFooter }
const COMPONENTS = {
  Alert: ['alert'],
  Avatar: ['avatar'],
  Badge: ['badge'],
  Button: ['button'],
  Card: ['card'],
  Col: ['grid', 'Col'],
  Checkbox: ['checkbox'],
  ConfigProvider: ['config-provider'],
  CheckboxGroup: ['checkbox', 'CheckboxGroup'],
  DatePicker: ['date-picker'],
  Descriptions: ['descriptions'],
  DescriptionsItem: ['descriptions', 'DescriptionsItem'],
  Divider: ['divider'],
  Drawer: ['drawer'],
  Dropdown: ['dropdown'],
  Empty: ['empty'],
  Form: ['form'],
  FormItem: ['form', 'FormItem'],
  Row: ['grid', 'Row'],
  Input: ['input'],
  InputNumber: ['input-number'],
  InputSearch: ['input', 'InputSearch'],
  InputPassword: ['input', 'InputPassword'],
  Textarea: ['input', 'Textarea'],
  Layout: ['layout'],
  LayoutHeader: ['layout', 'LayoutHeader'],
  LayoutFooter: ['layout', 'LayoutFooter'],
  LayoutSider: ['layout', 'LayoutSider'],
  LayoutContent: ['layout', 'LayoutContent'],
  Menu: ['menu'],
  MenuItem: ['menu', 'MenuItem'],
  MenuDivider: ['menu', 'MenuDivider'],
  Modal: ['modal'],
  Popconfirm: ['popconfirm'],
  Popover: ['popover'],
  Progress: ['progress'],
  Radio: ['radio'],
  RadioGroup: ['radio', 'RadioGroup'],
  Result: ['result'],
  Segmented: ['segmented'],
  Select: ['select'],
  SelectOption: ['select', 'SelectOption'],
  Space: ['space'],
  Spin: ['spin'],
  Switch: ['switch'],
  Table: ['table'],
  TableColumn: ['table', 'TableColumn'],
  Tabs: ['tabs'],
  TabPane: ['tabs', 'TabPane'],
  Tag: ['tag'],
  Tooltip: ['tooltip']
}

export function AntdSubpathResolver(options = {}) {
  const prefix = options.prefix || 'A'
  return {
    type: 'component',
    resolve(name) {
      if (!name.startsWith(prefix)) return
      const key = name.slice(prefix.length)
      const entry = COMPONENTS[key]
      if (!entry) return
      const [dir, named] = entry
      const from = `ant-design-vue/es/${dir}`
      // 有 named 时生成 `import { named as X } from ...`，
      // 否则生成默认导入 `import X from ...`（各组件 index.js 均有 default 导出）。
      return named ? { from, name: named } : { from }
    }
  }
}
