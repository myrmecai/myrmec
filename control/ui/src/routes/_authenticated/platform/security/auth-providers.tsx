// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { AuthProvidersList } from '@/features/platform/security/auth-providers/AuthProvidersList'

export const Route = createFileRoute('/_authenticated/platform/security/auth-providers')({
  component: AuthProvidersList,
})