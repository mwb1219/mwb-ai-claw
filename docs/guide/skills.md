# 技能系统（Skill）

> 面向使用者：无需写代码，放一个目录即可让 Agent 获得新能力。

## 1. 什么是 Skill

- [ ] 开放标准：`skills/<name>/SKILL.md`（YAML frontmatter + Markdown 指令）
- [ ] 渐进式披露：L1 技能清单 → L2 `use_skill` 按需加载全文 → L3 `$SKILL_DIR` 资源

## 2. 目录结构与三级加载

- [ ] 运行目录（`user.dir/skills` 或 `agent.skills-dir`）→ 安装目录（`~/.mwb-ai-claw/skills`）→ classpath 内置
- [ ] 任一外部目录非空即完全接管技能集

## 3. 编写 SKILL.md

- [ ] frontmatter 规范：`name`（必须与目录名一致）/ `description`（触发信号）
- [ ] 正文 ≤ 500 行，长文/脚本拆入 `resources/`，用 `$SKILL_DIR` 引用
- [ ] 内置技能示例（`start/src/main/resources/skills/`）：code-review、git-workflow、database-design 等

## 4. 使用与校验

- [ ] 重启 → 日志「已加载技能 [n]」
- [ ] 触发：LLM 匹配 `description` 场景时自动调用 `use_skill`
- [ ] 启动校验：name/description 缺失、name 与目录不一致、重复 → 启动报错
- [ ] 技能执行仍走工具沙箱，无特权提升

---

相关：[配置详解](configuration.md) ｜ [记忆系统](web-usage.md)
