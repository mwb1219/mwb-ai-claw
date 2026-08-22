---
title: 安装与运行
parent: 使用指南
nav_order: 2
---

# 安装与运行

> 面向使用者：三种安装方式（源码 / 安装脚本 / 二进制分发包）与运行模式详解。
> 快速上手见 [quick-start.md](quick-start.md)。

## 1. 源码构建

- [ ] 环境：JDK 8+、Maven 3.6+
- [ ] 全量构建：`mvn clean package -DskipTests`
- [ ] 常用构建：`mvn package -pl start -am -DskipTests`（仅 start 可执行 jar）

## 2. 安装脚本（tools/install.sh / install.ps1）

- [ ] 一键安装为全局命令 `mwb-ai-claw`
- [ ] 安装布局：`~/.mwb-ai-claw/{lib,bin,config,skills,.env}`
- [ ] 卸载：`./tools/install.sh --uninstall`

## 3. 二进制分发包（推荐：直接下载）

- [ ] 下载：GitHub Releases 页 → 选择最新版本 → 下载 `mwb-ai-claw-<version>-bin.tar.gz`
      https://github.com/mwb1219/mwb-ai-claw/releases
- [ ] 安装：解压 → `cd mwb-ai-claw-<version>-bin` → `./install.sh`（一键安装为全局命令 `mwb-ai-claw`）
- [ ] 配置：编辑 `~/.mwb-ai-claw/.env` 填入 `DEFAULT_API_KEY`
- [ ] 启动：`mwb-ai-claw` 直接进入 Shell 模式（终端 REPL）
- [ ] 可选：本地打包 `./tools/package.sh` → `dist/mwb-ai-claw-<version>-bin.tar.gz`（供内网/离线环境分发）

## 4. 运行模式

- [ ] Shell 模式：`--spring.profiles.active=shell`（终端 REPL）
      > 分发包安装后直接运行 `mwb-ai-claw` 即进入 Shell 模式，无需指定参数
- [ ] Web 模式：`--spring.profiles.active=web`（REST / WS / 前端）
- [ ] 嵌入式：`ClawRuntime`（见 [embedding.md](embedding.md)）
- [ ] 常用启动参数（`--server.port`、`--agent.orchestration=...` 等）

## 5. 升级与数据

- [ ] 升级：重跑安装脚本覆盖旧版本
- [ ] 数据位置：运行目录 `.agent/`（会话/记忆），跨重启保留

---

相关：[快速开始](quick-start.md) ｜ [配置详解](configuration.md) ｜ [Shell 模式](shell-usage.md)
