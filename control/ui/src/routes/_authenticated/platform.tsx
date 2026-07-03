// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { createFileRoute, redirect, Outlet } from '@tanstack/react-router'

export const Route = createFileRoute('/_authenticated/platform')({
  beforeLoad: ({ context }) => {
    if (!context.auth.isAuthenticated) return
    if (!context.auth.isPlatformAdmin) {
      throw redirect({ to: '/dashboard' })
    }
  },
  component: () => <Outlet />,
})