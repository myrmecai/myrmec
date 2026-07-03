// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { createFileRoute } from '@tanstack/react-router'
import { MyWorkPage } from '@/features/mywork/MyWorkPage'

export const Route = createFileRoute('/_authenticated/my-work')({
  component: MyWorkPage,
})
