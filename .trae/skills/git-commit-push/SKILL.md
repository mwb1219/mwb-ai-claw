---
name: "git-commit-push"
description: "Stage all changes, auto-generate or parse a commit message from user input, commit, and push to remote. Invoke when user asks to commit/push code, submit changes, or says '提交代码'."
---

# Git Commit & Push

Combines `git add`, `git commit`, and `git push` into a single automated workflow.

## Workflow

### Step 1: Check current state

Run these commands in parallel:

```bash
git status
git diff --stat
git log --oneline -5
```

### Step 2: Identify the commit message

**Parse commit message from the user's input text** using these priority rules:

1. **Explicit markers** — Extract text after markers like:
   - `commit message:` / `提交信息：` / `message:`
   - Text wrapped in quotes: `"Fix login bug"` or `'Add user module'`
   - Text after `--message=` or `-m `

2. **Natural language extraction** — If the user says "提交代码，修复了登录bug"，extract the intent after the comma: `修复登录bug`

3. **Auto-generate from diff** — If no message can be parsed, analyze `git diff --stat` and generate a concise Chinese commit message (no more than 50 chars) summarizing the nature of changes.

**Message format requirements:**
- Use `--date=short` format, preference for concise message
- First line ≤ 50 characters
- Use Chinese if the user's input language is Chinese
- Start with a verb or action word (e.g., `fix:`, `feat:`, `refactor:`, `chore:`)

### Step 3: Stage files

```bash
git add -A
```

### Step 4: Commit

```bash
git commit -m "$(cat <<'EOF'
<commit message>
EOF
)"
```

### Step 5: Push

```bash
git push
```

If the branch has no upstream tracking, use:

```bash
git push -u origin <branch>
```

### Step 6: Verify

```bash
git status
```

## Git Safety Rules

- NEVER run `git push --force` or `git push --force-with-lease` unless the user explicitly requests it
- NEVER push to `main`/`master` — warn the user if they request it
- NEVER commit files containing secrets (`.env`, `credentials.json`, etc.) — warn and skip them
- NEVER run destructive commands (`reset --hard`, `checkout .`, `clean -f`, `branch -D`) unless explicitly requested

## Output

After completion, report to the user:
- The parsed/generated commit message
- The branch name
- The push result (remote URL or error)
