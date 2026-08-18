---
name: git-workflow
description: 规范化 Git 提交流程：查看变更、分析改动、撰写规范 commit message、提交并推送。当用户要求提交代码、commit、push、查看 git 状态时使用。
---

# Git 工作流

## When to Use

用户要求提交代码 / push / 查看变更，或需要规范化的 git 操作流程。

## 工作流

1. **查看现状**（并行执行）：
   - `git status`：未跟踪与改动文件（不要用 -uall）
   - `git diff`：已跟踪文件的变更内容
   - `git log --oneline -5`：近期提交风格，保持一致
2. **分析并起草**：按变更性质选择动词——add（新功能）/ fix（修复）/ update（增强）/ refactor（重构）/ docs（文档）；提交信息聚焦「为什么」而非「做了什么」，1-2 句
3. **暂存**：按文件名精确添加（避免 `git add .` 混入 .env 等敏感文件）
4. **提交**：使用 heredoc 传 message，如
   ```bash
   git commit -m "$(cat <<'EOF'
   feat: 增加 LLM 意图选择器
   EOF
   )"
   ```
5. **推送**：无上游用 `git push -u origin <branch>`，有则 `git push`；避免 force push

## 要点

- 除非用户明确要求，不自动提交（先询问）；禁止提交含密钥的 .env / credentials
- 推送到 main/master 前提示用户确认
- 提交后 `git status` 确认成功，返回 merge request / PR 链接（如有）
