---
name: markdown-diagramming
description: mermaid 图表编写规范：流程图/时序图/类图/状态图语法要点、旧版兼容性避坑与渲染验证。当文档需配架构图/时序图/类图/状态图，或 mermaid 图渲染失败时使用。
---

# Mermaid 图表规范

## When to Use

在文档中绘制或修复 mermaid 图（架构图、时序图、类图、状态图）；排查图渲染失败。

## 图类型选择

| 表达内容 | 图类型 |
|---------|--------|
| 架构/流程/决策分支 | `flowchart TD`（subgraph 分组、菱形判断） |
| 对象交互顺序 | `sequenceDiagram` |
| 类/实体/领域模型 | `classDiagram` |
| 状态机 | `stateDiagram-v2` |
| 甘特/饼图等 | 按需使用对应类型 |

## 语法要点

- **flowchart**：节点别名用大写字母或单词（`A["描述"]`）；分支用 `{判断}`；分组用 `subgraph 名`；连线标注用 `--是-->`
- **sequenceDiagram**：`participant 别名 as 显示名`；`->>+`/`-->>` 表示返回；`Note over A,B: 说明`
- **classDiagram**：`class 类名 {` + 字段/方法（`+方法名(参数) 返回类型`）；关系 `--|>` 继承、`*--` 组合、`o--` 聚合；`note for 类名 "说明"`
- **stateDiagram-v2**：`[*] --> 初始`、`状态 --> 状态 : 触发条件`、`状态 --> [*]`

## 旧版兼容性避坑（重要）

- **`class X <<注解>> {` 带类体的写法在 v10.x 解析失败**（报 `got 'ANNOTATION_START'`）。改为：`%% X <<注解>>` 注释行 + `class X {` 类体分离写法
- `namespace`、`note for`、方法返回类型、静态方法 `$`、属性默认值、泛型 `~`、多重性在 v10.6 均兼容
- 节点文本内的 `<br/>` 可换行；含 `<>` 等特殊字符时用引号包裹

## 渲染验证

写完图后验证语法（尤其 classDiagram）：
1. 支持 mermaid 的编辑器直接渲染
2. 或用项目 /tmp/mermaid-check 下的 puppeteer 脚本双版本验证（mermaid v11 与 v10.6）

## 要点

- 图前一句引导语（图表达什么），图后 1-2 句要点说明
- 每图只表达一件事，复杂关系拆多图
- 节点文本简洁，长文本拆成多行节点或加注释
