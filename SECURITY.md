# Security Policy

## Reporting a vulnerability

If you believe you've found a security vulnerability in Myrmec, please report it privately
rather than opening a public issue. Open an issue titled "Security contact request" with no
technical detail, and maintainers will follow up with a private channel — or check the
repository's contact information for a direct email if one is listed.

Please include:

- A description of the vulnerability and its potential impact.
- Steps to reproduce (a minimal example, if possible).
- The version/commit you tested against.

We'll acknowledge reports and aim to keep you updated as we investigate and fix.

## Supported versions

Myrmec is pre-1.0. Security fixes land on the `main` branch; there is no separate long-term
support branch yet.

## Security posture (high level)

Myrmec is designed for regulated/enterprise deployment. At a high level:

- Agents execute inside sandboxed containers with scoped tool access — no arbitrary network
  egress, no cross-project secret access by default.
- Credentials are encrypted at rest (AES-256-GCM) and never handed directly to agents; the
  engine brokers all model/provider calls.
- Every privileged action (role grants, secret CRUD, quota changes, knowledge source changes)
  is recorded in an append-only audit log.
- Human-in-the-loop approval gates are available for destructive/high-risk tool calls.
- Role-based access control is enforced at system, group, and project scope.

For deployment/hardening guidance beyond this policy, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Disclosure policy

We ask that you give us a reasonable window to investigate and patch before any public
disclosure. We'll coordinate a disclosure timeline with you once a fix is available.
