---
title: Authentication
description: User and agent authentication model.
---

Myrmec uses principal-specific authentication.

## User principals

- Email/password or OIDC broker login
- JWT with role claims
- System-wide and project-scoped roles

## Agent principals

- Registration key exchange for tokens
- WebSocket authenticated with access token
- Periodic refresh using refresh token

## Security posture

- Short-lived access tokens
- Refresh tokens with bounded lifetime
- Encrypted credential storage
