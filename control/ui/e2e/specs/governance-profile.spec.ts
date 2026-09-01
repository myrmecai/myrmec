import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'
import { resetGovernanceProfile } from '../helpers/governance'

/**
 * UC-KM-01 — Governance Profile compare matrix and Set As Default.
 *
 * Verifies the compare matrix renders all three built-in profiles,
 * the current default (STANDARD) is highlighted with a checkmark,
 * feature groups (AI Context, Budget) are displayed, and the
 * Set As Default flow changes the active profile.
 */
test.describe('governance profile compare matrix', () => {
  test.beforeEach(async ({ api }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    // Reset to STANDARD before each test to avoid state leak from other specs
    // (e.g. instruction-assets sets FLEXIBLE in its beforeEach).
    await resetGovernanceProfile(api)
  })

  test('compare matrix renders all three profiles with feature groups', async ({
    adminPage,
  }) => {
    await adminPage.goto('/platform/ai-context/governance-profile')

    // Page heading
    await expect(
      adminPage.getByRole('heading', { name: 'Governance Profiles' }),
    ).toBeVisible()

    // Three profile column headers: Strict, Standard, Flexible
    await expect(
      adminPage.getByText('Strict', { exact: true }).first(),
    ).toBeVisible()
    await expect(
      adminPage.getByText('Standard', { exact: true }).first(),
    ).toBeVisible()
    await expect(
      adminPage.getByText('Flexible', { exact: true }).first(),
    ).toBeVisible()

    // Feature group headers
    await expect(
      adminPage.getByText('AI Context', { exact: true }).first(),
    ).toBeVisible()
    await expect(
      adminPage.getByText('Budget', { exact: true }).first(),
    ).toBeVisible()

    // Feature descriptions should be present
    await expect(
      adminPage.getByText('Allowed instruction source types').first(),
    ).toBeVisible()
    await expect(
      adminPage.getByText('Enforce token budget caps').first(),
    ).toBeVisible()
  })

  test('current default profile (Standard) has a checkmark and disabled button', async ({
    adminPage,
  }) => {
    await adminPage.goto('/platform/ai-context/governance-profile')

    // The Standard column should have a "Current Default" disabled button
    const standardButton = adminPage.getByRole('button', {
      name: 'Current Default',
    })
    await expect(standardButton).toBeVisible()
    await expect(standardButton).toBeDisabled()

    // The Strict and Flexible columns should have enabled "Set As Default" buttons
    const setDefaultButtons = adminPage.getByRole('button', {
      name: 'Set As Default',
    })
    await expect(setDefaultButtons).toHaveCount(2)
  })

  test('Set As Default changes the active profile to Strict', async ({
    adminPage,
    api,
  }) => {
    await adminPage.goto('/platform/ai-context/governance-profile')

    // Click "Set As Default" on the Strict column
    const strictSetButton = adminPage.getByRole('button', {
      name: 'Set As Default',
    }).first()
    await strictSetButton.click()

    // Confirmation dialog should appear
    await expect(
      adminPage.getByRole('heading', { name: 'Change Governance Profile' }),
    ).toBeVisible()
    await expect(
      adminPage.getByText('Current profile:'),
    ).toBeVisible()
    await expect(
      adminPage.getByText('New profile:'),
    ).toBeVisible()

    // Confirm the change
    await adminPage.getByRole('button', { name: 'Confirm' }).click()

    // Wait for the page to update — Strict should now show "Current Default"
    await expect(
      adminPage.getByRole('button', { name: 'Current Default' }),
    ).toBeVisible({ timeout: 10_000 })
    await expect(
      adminPage.getByRole('button', { name: 'Current Default' }),
    ).toBeDisabled()

    // Verify via API that the setting was updated
    const profile = await api.request('GET', '/admin/governance-profiles/current')
    expect(profile).toHaveProperty('code', 'STRICT')

    // Reset to STANDARD for other tests
    await api.request('POST', '/admin/governance-profiles/STANDARD/set-default')
  })
})