// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Helper to confirm a custom React AlertDialog (dialogService) in E2E tests.
 *
 * The UI uses a custom AlertDialog (data-testid="confirm-dialog") instead of
 * native confirm() dialogs. Tests must click the confirm button inside the
 * AlertDialog rather than using page.on('dialog', ...).
 */
import type { Page } from '@playwright/test'

/**
 * Click the confirm button in the custom AlertDialog.
 *
 * Usage:
 *   await row.getByRole('button', { name: 'Delete' }).click()
 *   await confirmDialog(page, 'Delete')
 *
 * @param page - The Playwright page
 * @param confirmLabel - The label of the confirm button (e.g. 'Delete', 'Archive')
 */
export async function confirmDialog(
  page: Page,
  confirmLabel: string = 'Delete',
): Promise<void> {
  const dialog = page.getByTestId('confirm-dialog')
  await dialog.waitFor({ state: 'visible', timeout: 5_000 })
  await dialog.getByRole('button', { name: confirmLabel }).click()
  await dialog.waitFor({ state: 'detached', timeout: 5_000 })
}