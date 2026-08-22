---
title: Install & Run
parent: User Guide (EN)
nav_order: 2
---

# Install & Run

> For users: detailed explanation of the three installation methods (source / install script / binary distribution) and run modes.
> For a quick start, see [quick-start.md](quick-start.md).

## 1. Source Build

- [ ] Environment: JDK 8+, Maven 3.6+
- [ ] Full build: `mvn clean package -DskipTests`
- [ ] Common build: `mvn package -pl start -am -DskipTests` (start executable jar only)

## 2. Install Script (tools/install.sh / install.ps1)

- [ ] One-click install as the global command `mwb-ai-claw`
- [ ] Install layout: `~/.mwb-ai-claw/{lib,bin,config,skills,.env}`
- [ ] Uninstall: `./tools/install.sh --uninstall`

## 3. Binary Distribution (recommended: download directly)

- [ ] Download: GitHub Releases page → select the latest version → download `mwb-ai-claw-<version>-bin.tar.gz`
      https://github.com/mwb1219/mwb-ai-claw/releases
- [ ] Install (macOS / Linux): extract → `cd mwb-ai-claw-<version>-bin` → `./install.sh`
- [ ] Install (Windows): extract → run `powershell -ExecutionPolicy Bypass -File .\install.ps1` from the package root directory
- [ ] Configure: edit `~/.mwb-ai-claw/.env` (Windows: `%USERPROFILE%\.mwb-ai-claw\.env`) to fill in `DEFAULT_API_KEY`
- [ ] Start: `mwb-ai-claw` enters Shell mode directly (terminal REPL)
- [ ] Optional: package locally with `./tools/package.sh` → `dist/mwb-ai-claw-<version>-bin.tar.gz` (for intranet / offline distribution)

## 4. Run Modes

- [ ] Shell mode: `--spring.profiles.active=shell` (terminal REPL)
      > After installing the distribution, running `mwb-ai-claw` directly enters Shell mode, no arguments needed
- [ ] Web mode: `--spring.profiles.active=web` (REST / WS / front-end)
- [ ] Embedded: `ClawRuntime` (see [embedding.md](embedding.md))
- [ ] Common startup arguments (`--server.port`, `--agent.orchestration=...`, etc.)

## 5. Upgrade & Data

- [ ] Upgrade: re-run the install script to overwrite the old version
- [ ] Data location: run directory `.agent/` (sessions / memory), preserved across restarts

---

See also: [Quick Start](quick-start.md) ｜ [Configuration](configuration.md) ｜ [Shell Mode](shell-usage.md)
