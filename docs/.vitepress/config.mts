import { defineConfig } from 'vitepress'

// 站点部署在 https://mwb1219.github.io/mwb-ai-claw/，因此 base 需与仓库名一致
export default defineConfig({
  base: '/mwb-ai-claw/',
  lang: 'zh-CN',
  title: 'mwb-ai-claw',
  description:
    'AI Agent framework in Java (DDD + Spring Boot). ReAct loop, MCP, layered memory, multi-agent orchestration.',

  // 多语言：中文（root）与英文（/en/）各维护一套独立导航。
  // 不设置顶部 nav，导航统一收敛到左侧树形侧边栏；右上角保留语言切换按钮。
  locales: {
    root: {
      label: '简体中文',
      lang: 'zh-CN',
      title: 'mwb-ai-claw',
      description: '基于 Java（DDD + Spring Boot）的 AI Agent 框架。',
      themeConfig: {
        sidebar: [
          {
            text: '使用指南',
            link: '/guide/',
            collapsed: false,
            items: [
              { text: '快速开始', link: '/guide/quick-start.html' },
              { text: '安装与运行', link: '/guide/install.html' },
              { text: '配置详解', link: '/guide/configuration.html' },
              { text: 'Shell 模式使用', link: '/guide/shell-usage.html' },
              { text: 'Web 模式使用', link: '/guide/web-usage.html' },
              { text: '嵌入式集成（ClawRuntime）', link: '/guide/embedding.html' },
              { text: '服务端集成（Spring Boot Starter）', link: '/guide/server-integration.html' },
              { text: 'Agent 注册表与编排配置', link: '/guide/agents-config.html' },
              { text: '技能系统（Skill）', link: '/guide/skills.html' },
              { text: 'MCP 工具接入', link: '/guide/mcp.html' },
            ],
          },
          {
            text: '设计概要',
            link: '/design/',
            collapsed: false,
            items: [
              { text: '总体架构', link: '/design/architecture.html' },
              { text: 'ReAct 推理循环', link: '/design/core-loop.html' },
              { text: '多 Agent 编排', link: '/design/collaboration.html' },
              { text: '分层记忆模型', link: '/design/memory-model.html' },
              { text: 'RAG 检索增强', link: '/design/rag.html' },
              { text: '存储与多租户', link: '/design/storage-multitenancy.html' },
              { text: '安全模型', link: '/design/security.html' },
              { text: '可观测性与韧性', link: '/design/observability.html' },
              { text: '扩展能力设计', link: '/design/extensibility.html' },
            ],
          },
          {
            text: '速查参考',
            link: '/reference/',
            collapsed: false,
            items: [
              { text: 'REST API 速查', link: '/reference/rest-api.html' },
              { text: 'WebSocket 事件协议', link: '/reference/websocket.html' },
              { text: '全部配置项速查', link: '/reference/config-full.html' },
              { text: 'Shell 斜杠命令速查', link: '/reference/shell-commands.html' },
            ],
          },
        ],
        socialLinks: [{ icon: 'github', link: 'https://github.com/mwb1219/mwb-ai-claw' }],
      },
    },
    en: {
      label: 'English',
      lang: 'en-US',
      link: '/en/',
      title: 'mwb-ai-claw',
      description: 'AI Agent framework in Java (DDD + Spring Boot).',
      themeConfig: {
        sidebar: [
          {
            text: 'User Guide',
            link: '/en/guide/',
            collapsed: false,
            items: [
              { text: 'Quick Start', link: '/en/guide/quick-start.html' },
              { text: 'Install & Run', link: '/en/guide/install.html' },
              { text: 'Configuration', link: '/en/guide/configuration.html' },
              { text: 'Shell Mode Usage', link: '/en/guide/shell-usage.html' },
              { text: 'Web Mode Usage', link: '/en/guide/web-usage.html' },
              { text: 'Embedding Integration (ClawRuntime)', link: '/en/guide/embedding.html' },
              { text: 'Server Integration (Spring Boot Starter)', link: '/en/guide/server-integration.html' },
              { text: 'Agents & Orchestrations Configuration', link: '/en/guide/agents-config.html' },
              { text: 'Skills System (Skill)', link: '/en/guide/skills.html' },
              { text: 'MCP Tools Integration', link: '/en/guide/mcp.html' },
            ],
          },
          {
            text: 'Design Overview',
            link: '/en/design/',
            collapsed: false,
            items: [
              { text: 'Overall Architecture', link: '/en/design/architecture.html' },
              { text: 'ReAct Reasoning Loop', link: '/en/design/core-loop.html' },
              { text: 'Multi-Agent Orchestration', link: '/en/design/collaboration.html' },
              { text: 'Layered Memory Model', link: '/en/design/memory-model.html' },
              { text: 'Storage & Multi-Tenancy', link: '/en/design/storage-multitenancy.html' },
              { text: 'Security Model', link: '/en/design/security.html' },
              { text: 'Observability & Resilience', link: '/en/design/observability.html' },
              { text: 'Extensibility Design', link: '/en/design/extensibility.html' },
            ],
          },
          {
            text: 'Quick Reference',
            link: '/en/reference/',
            collapsed: false,
            items: [
              { text: 'REST API Reference', link: '/en/reference/rest-api.html' },
              { text: 'WebSocket Event Protocol', link: '/en/reference/websocket.html' },
              { text: 'Full Configuration Reference', link: '/en/reference/config-full.html' },
              { text: 'Shell Slash Commands Reference', link: '/en/reference/shell-commands.html' },
            ],
          },
        ],
        socialLinks: [{ icon: 'github', link: 'https://github.com/mwb1219/mwb-ai-claw' }],
      },
    },
  },
})
