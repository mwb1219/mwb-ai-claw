# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| 1.x | ✅ |
| < 1.0 (development) | ❌ |

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.**

Report them privately:

1. Use GitHub's private vulnerability reporting once the repository is public
   (**Security → Report a vulnerability**), or
2. Contact the maintainer directly at mawenbin1219@users.noreply.github.com.

Please include:

- Affected version(s) / commit hash
- Steps to reproduce
- Description of the impact

We will acknowledge receipt and work with you to verify and fix the issue, and
will coordinate a release before public disclosure.

## Security notes

- The shell tool is sandboxed with approval/allowlist controls — see
  [docs/design/security.md](docs/design/security.md) for the security model.
- Never commit `.env` or real API keys. The repository is scanned for leaked
  secrets before release (gitleaks / `git log -S`); a leaked key is treated as
  compromised and must be revoked and rotated.
