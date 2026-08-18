---
name: project-structure-analysis
description: 分析代码库项目结构与模块划分，输出架构概览。当用户要求了解代码库、项目结构、模块职责、架构概览、分层关系时使用。
---

# 项目结构分析

## When to Use

用户需要快速了解一个不熟悉的代码库：目录结构、模块职责、分层关系、核心依赖。

## 工作流

1. 用 shell/file 工具读取根目录清单（`ls` 或 listDirectory），识别构建文件（pom.xml / build.gradle / package.json）确定技术栈
2. 逐层分析 src 目录：按包名推断模块/领域划分，标注每层（domain / application / infrastructure / interfaces）职责
3. 定位关键入口（Application 启动类、Controller、路由注册）与核心配置文件
4. 输出结构化概览：
   - 技术栈与构建方式
   - 模块/子项目划分与依赖关系（可用 mermaid 描述）
   - 分层架构与核心包职责
   - 关键扩展点（SPI 接口、插件注册表、配置文件）

## 要点

- 优先读构建文件和包结构，不要逐文件读源码
- 模块多时按「入口 → 核心域 → 基础设施」三层归纳，每层列出代表类即可
- 如已有技术方案/README 章节，先读它们再补充细节
