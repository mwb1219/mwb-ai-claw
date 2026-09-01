# Contributing to mwb-ai-claw

Thank you for considering contributing to mwb-ai-claw! We welcome bug reports, feature requests, documentation improvements, and code contributions.

## Quick links

- Documentation: [docs/README.md](docs/README.md)

- License: [Apache-2.0](LICENSE)

## Development setup

Prerequisites: JDK 8+, Maven 3.6+.

```bash
mvn clean install                          # build all modules
mvn package -pl start -am -DskipTests      # build only the runnable start module
```

## Project layout

- `mwb-ai-claw-client` — public client-facing interfaces (API contract)

- `mwb-ai-claw-adapter` — adapters (Web / REST / WebSocket / Shell entry points)

- `mwb-ai-claw-app` — application layer (use-case orchestration, `ClawRuntime`)

- `mwb-ai-claw-domain` — domain layer (Agent / tool / memory core models)

- `mwb-ai-claw-infrastructure` — infrastructure (LLM clients, storage, tool sandbox, MCP)

- `mwb-ai-claw-spring-boot-starter` — Spring Boot auto-configuration

- `example-embed` / `example-web` / `start` — samples and the runnable launcher

Follow the COLA (DDD) layering: keep dependency arrows pointing inward (adapter → app → domain ← infrastructure).

## Before you submit

1. Run the full CI pipeline locally (compile + tests + package):

   ```bash
   bash tools/ci.sh
   ```

2. For runtime/entry-point changes, also run the smoke test:

   ```bash
   bash tools/smoke.sh
   ```

3. Keep commits focused and use conventional commit messages
   (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, …).

4. Never commit real secrets: check for `.env`, `sk-`, API keys, and private files before pushing.

## Pull request process

1. Fork the repository and create a branch: `git checkout -b feat/my-feature`.
2. Make your changes and verify with `bash tools/ci.sh`.
3. Open a PR against `master` using the [pull request template](.github/PULL_REQUEST_TEMPLATE.md).
4. A maintainer will review; keep the conversation focused and address feedback.

## Reporting issues

- Bugs: use the [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml).

- Feature ideas: use the [feature request template](.github/ISSUE_TEMPLATE/feature_request.yml).

