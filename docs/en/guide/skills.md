---
title: Skills System (Skill)
parent: User Guide (EN)
nav_order: 8
---

# Skills System (Skill)

> For users: without writing any code, drop in a directory to give the Agent new capabilities.

## 1. What Is a Skill

- [ ] Open standard: `skills/<name>/SKILL.md` (YAML frontmatter + Markdown instructions)
- [ ] Progressive disclosure: L1 skill list → L2 `use_skill` loads the full text on demand → L3 `$SKILL_DIR` resources

## 2. Directory Structure & Three-Level Loading

- [ ] Run directory (`user.dir/skills` or `agent.skills-dir`) → install directory (`~/.mwb-ai-claw/skills`) → classpath built-in
- [ ] Any non-empty external directory takes over the entire skill set

## 3. Writing SKILL.md

- [ ] frontmatter spec: `name` (must match the directory name) / `description` (trigger signal)
- [ ] Body ≤ 500 lines; long text / scripts go into `resources/`, referenced with `$SKILL_DIR`
- [ ] Built-in skill examples (`start/src/main/resources/skills/`, 10 in total): code-review, git-workflow, database-design, ddd-modeling, unit-test-writing, doc-writing-guide, doc-review, project-structure-analysis, markdown-diagramming, web-research

## 4. Usage & Validation

- [ ] Restart → log "[n] skills loaded"
- [ ] Trigger: the LLM automatically calls `use_skill` when it matches the `description` scenario
- [ ] Startup validation: missing name/description, name inconsistent with directory, duplicates → startup error
- [ ] Skill execution still goes through the tool sandbox, no privilege escalation

---

See also: [Configuration](configuration.md) ｜ [Hierarchical Memory Design](../design/memory-model.md)
