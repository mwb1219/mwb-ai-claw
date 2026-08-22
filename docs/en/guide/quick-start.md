---
title: Quick Start
parent: User Guide (EN)
nav_order: 1
---

# Quick Start

> For first-time users: get through "install → configure → first chat with an Agent" in 3 minutes.
> For full configuration, see [configuration.md](configuration.md); for installation details, see [install.md](install.md).

## 1. Environment Requirements

| Mode | Requirement | Corresponding Method |
| --- | --- | --- |
| Source build | JDK 8+, Maven 3.6+ | Method 1 / 3 |
| Binary distribution | JDK 8+ only (no Maven / source needed) | Method 2 |

## 2. Choose a Way to Run

### Method 1: Source Build + Shell Mode (recommended for a first try)

```bash
# 1. Build (compile + package the start executable jar)
mvn package -pl start -am -DskipTests

# 2. Prepare the key: copy the template and fill in your LLM API Key
cp .env.example .env
#    Edit .env, at minimum fill in DEFAULT_API_KEY=sk-xxx (default model deepseek-chat)

# 3. Start the Shell terminal (interactive REPL)
java -jar start/target/start-*.jar --spring.profiles.active=shell
```

Once in the interactive interface, just type a question to chat, for example:

```text
> Hi, introduce yourself
```

### Method 2: Download the Binary Distribution (recommended for beginners / no-source environments)

**macOS / Linux**:

```bash
# 1. Download the latest package from GitHub Releases (no Maven / source needed, only JDK 8+)
#    Download page: https://github.com/mwb1219/mwb-ai-claw/releases
#    Asset: mwb-ai-claw-<version>-bin.tar.gz (current v1.0.0, about 26MB)

# 2. Extract and install as a global command (the package already contains a prebuilt jar; install.sh installs directly, no mvn needed)
tar -xzf mwb-ai-claw-1.0.0-bin.tar.gz
cd mwb-ai-claw-1.0.0-bin
./install.sh

# 3. Edit ~/.mwb-ai-claw/.env to fill in DEFAULT_API_KEY, then start the Agent Shell
mwb-ai-claw
```

**Windows (PowerShell)**:

```powershell
# 1. Download mwb-ai-claw-1.0.0-bin.tar.gz (Windows 10+ has a built-in tar to extract, or use 7-Zip)

# 2. After extracting, run the install script from the package root directory (-ExecutionPolicy Bypass bypasses execution policy restrictions)
tar -xzf mwb-ai-claw-1.0.0-bin.tar.gz
cd mwb-ai-claw-1.0.0-bin
powershell -ExecutionPolicy Bypass -File .\install.ps1

# 3. Edit %USERPROFILE%\.mwb-ai-claw\.env to fill in DEFAULT_API_KEY, then start the Agent Shell
mwb-ai-claw
```

> Uninstall (macOS / Linux): `./install.sh --uninstall`; uninstall (Windows): `.\install.ps1 -Uninstall`.
> To repackage yourself: `./tools/package.sh` → `dist/mwb-ai-claw-<version>-bin.tar.gz`.

### Method 3: Source Build + Install as a Global Command (like `claude`)

```bash
./tools/install.sh          # In source mode, automatically runs mvn package to build and installs to ~/.mwb-ai-claw (symlinked into PATH)
mwb-ai-claw                 # Enter the Agent Shell from any directory
```

> Requires the source code plus JDK 8+ and Maven 3.6+.
> After the first install, edit `~/.mwb-ai-claw/.env` to fill in `DEFAULT_API_KEY`.
> Supports argument passthrough, e.g. `mwb-ai-claw --agent.orchestration=todo-delegate`.
> Uninstall: `./tools/install.sh --uninstall`; the install root can be overridden with the environment variable `MWB_AI_CLAW_HOME` (default `~/.mwb-ai-claw`).

#### tools/ Script Quick Reference (commonly used in source environments)

| Script | Purpose |
| --- | --- |
| `install.sh` / `install.ps1` | Install as a global command. Source mode automatically runs `mvn package` to build; after extracting the binary package, `install.sh` uses the jar inside the package directly, no mvn needed |
| `package.sh` / `package.ps1` | Build the binary distribution: `dist/mwb-ai-claw-<version>-bin.tar.gz` (`--skip-build` reuses an already-built jar) |
| `setup.sh` / `setup.ps1` | Maintainer one-click "build + package + install" full-flow verification (`--skip-build` skips the Maven build) |
| `smoke.sh` | E2E smoke test: 5 scenarios (echo / fail / tool / anthropic / gemini), automatically starts a mock LLM to verify |
| `mock_llm.py` | The mock LLM server that pairs with smoke.sh (`python3 tools/mock_llm.py --port 19996 --mode echo`) |
| `ci.sh` | Full CI: `mvn clean test` + packaging (consistent with the GitHub Actions flow) |

> For full configuration instructions, see [CONFIG-GUIDE.md](https://github.com/mwb1219/mwb-ai-claw/blob/master/CONFIG-GUIDE.md) (keys / Agents / orchestration / MCP / skills).

## 3. Try Web Mode (optional)

```bash
java -jar start/target/start-*.jar --spring.profiles.active=web
# Open http://localhost:8080 in the browser
```

Web mode provides: REST chat, SSE streaming, WebSocket, session management, and a front-end console.

## 4. What the First Chat Does

1. Read config: `.env` (run directory → install directory) → environment variables → built-in defaults
2. Start the Spring context and assemble: Agent registry, orchestration, tools (built-in + MCP), memory, skills
3. The request is handed to the Agent by the default `routing` orchestration, which runs the ReAct loop calling the LLM

## 5. FAQ

| Symptom | Handling |
| --- | --- |
| `401 / Unauthorized` | `DEFAULT_API_KEY` not filled in or wrongly set in `.env` |
| `Connection refused` (3306) | `DB_*` variables configured in `.env` but MySQL is not running; the default H2 needs no database, comment out `DB_*` |
| Port in use | Change the port in Web mode: `java -jar start-*.jar --server.port=9090` |
| Want to switch models | Set `DEFAULT_MODEL` / `DEFAULT_BASE_URL` in `.env` (any OpenAI-compatible service) |

---

See also: [Install & Run](install.md) ｜ [Configuration](configuration.md) ｜ [Shell Mode](shell-usage.md)
