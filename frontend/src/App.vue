<template>
  <a-config-provider :theme="themeConfig">
    <router-view />
  </a-config-provider>
</template>

<script setup>
import { computed } from 'vue'
import antdTheme from 'ant-design-vue/es/theme'
import { useTheme } from './composables/useTheme'

// 主题由 useTheme 统一管理：这里只负责把它翻译成 antd 的 design token。
// CSS 侧的颜色见 styles/tokens.css，两边的取值刻意保持一致。
const { isDark } = useTheme()

const FONT_FAMILY =
  "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"

const themeConfig = computed(() => {
  const dark = isDark.value
  return {
    algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
      colorPrimary: dark ? '#4096ff' : '#1677ff',
      colorInfo: dark ? '#4096ff' : '#1677ff',
      colorSuccess: dark ? '#3ecf8e' : '#12a150',
      colorWarning: dark ? '#f5b04c' : '#d97b06',
      colorError: dark ? '#ff7b83' : '#e0313f',
      colorTextBase: dark ? '#e9eff7' : '#101828',
      colorText: dark ? '#c4cfdd' : '#344054',
      colorTextSecondary: dark ? '#8e9bad' : '#667085',
      colorTextTertiary: dark ? '#7d8898' : '#6f7b8c',
      colorBgLayout: dark ? '#0b0f15' : '#f1f4f9',
      colorBgContainer: dark ? '#151c25' : '#ffffff',
      colorBgElevated: dark ? '#1a222d' : '#ffffff',
      colorBgSpotlight: dark ? '#26303d' : 'rgba(16, 24, 40, 0.88)',
      colorBorder: dark ? '#37455a' : '#d3dae5',
      colorBorderSecondary: dark ? '#263140' : '#e9edf3',
      colorSplit: dark ? 'rgba(255, 255, 255, 0.08)' : 'rgba(16, 24, 40, 0.06)',
      colorFillAlter: dark ? 'rgba(255, 255, 255, 0.04)' : 'rgba(16, 24, 40, 0.025)',
      colorFillSecondary: dark ? 'rgba(255, 255, 255, 0.08)' : 'rgba(16, 24, 40, 0.04)',
      fontFamily: FONT_FAMILY,
      fontSize: 14,
      borderRadius: 8,
      borderRadiusLG: 10,
      borderRadiusSM: 6,
      controlHeight: 34,
      controlHeightLG: 40,
      boxShadow: dark
        ? '0 6px 18px rgba(0, 0, 0, 0.42), 0 2px 6px rgba(0, 0, 0, 0.28)'
        : '0 6px 16px rgba(16, 24, 40, 0.08), 0 2px 4px rgba(16, 24, 40, 0.04)',
      boxShadowSecondary: dark
        ? '0 18px 44px rgba(0, 0, 0, 0.55), 0 6px 14px rgba(0, 0, 0, 0.35)'
        : '0 16px 40px rgba(16, 24, 40, 0.14), 0 4px 10px rgba(16, 24, 40, 0.06)',
      wireframe: false
    },
    components: {
      Layout: {
        bodyBg: 'transparent',
        // 显式给出具体色值：若留 transparent，antd 会退回默认的 #001529 深色顶栏，
        // 浅色模式下就会变成「上方一块深色、面包屑深字压深底」。
        headerBg: dark ? '#151c25' : '#ffffff',
        siderBg: dark ? '#070c13' : '#ffffff',
        triggerBg: 'transparent'
      },
      Menu: {
        darkItemBg: 'transparent',
        darkSubMenuItemBg: 'transparent',
        itemBorderRadius: 8,
        itemMarginInline: 10,
        itemHeight: 42
      },
      Table: {
        headerBg: dark ? '#1a222d' : '#f7f9fc',
        headerColor: dark ? '#c4cfdd' : '#475467',
        headerSplitColor: 'transparent',
        rowHoverBg: dark ? 'rgba(255, 255, 255, 0.045)' : '#f7faff',
        borderColor: dark ? '#263140' : '#eef1f5',
        cellPaddingBlock: 12,
        cellPaddingInline: 16
      },
      Card: {
        paddingLG: 20
      },
      Modal: {
        borderRadiusLG: 14
      },
      Drawer: {
        paddingLG: 20
      },
      Segmented: {
        itemSelectedBg: dark ? '#1a222d' : '#ffffff',
        trackBg: dark ? 'rgba(255, 255, 255, 0.05)' : '#eef1f5'
      },
      Input: {
        activeShadow: `0 0 0 3px ${dark ? 'rgba(64, 150, 255, 0.24)' : 'rgba(22, 119, 255, 0.16)'}`
      }
    }
  }
})
</script>
