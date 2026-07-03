// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { SecretsList } from '@/features/platform/security/secrets/SecretsList'

export const Route = createFileRoute('/_authenticated/platform/security/secrets')({
  component: SecretsList,
})