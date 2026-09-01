import { test as base, expect, type Page } from '@playwright/test'
import { ApiClient } from './helpers/api'
import { loginAsAdmin, loginAs } from './helpers/auth'

/**
 * Extended Playwright {@code test} that provides
 * <ul>
 *   <li>{@code api} — an unauthenticated {@link ApiClient} for arrange-via-API setup;
 *       call {@code api.login(...)} once at the start of a spec to attach a token.</li>
 *   <li>{@code adminPage} — a {@link Page} pre-authenticated as the bootstrapped
 *       admin user. Skips the visual login flow.</li>
 *   <li>{@code ownerPage} / {@code editorPage} / {@code viewerPage} — pages
 *       pre-authenticated as project-scoped users. These reuse the same
 *       underlying {@code page} fixture (sequential re-login), not separate
 *       browser contexts. Tests that need concurrent sessions should use
 *       {@code browser.newContext()} explicitly.</li>
 * </ul>
 *
 * Import this fixture instead of {@code @playwright/test} in specs:
 * {@code import { test, expect } from '../fixtures'}
 */
export const test = base.extend<{
  api: ApiClient
  adminPage: Page
  ownerPage: Page
  editorPage: Page
  viewerPage: Page
  approverPage: Page
  auditorPage: Page
  secondPage: Page
}>({
  api: async ({}, use) => {
    await use(new ApiClient())
  },

  adminPage: async ({ page }, use) => {
    await loginAsAdmin(page)
    await use(page)
  },

  ownerPage: async ({ page }, use) => {
    await use(page)
  },

  editorPage: async ({ page }, use) => {
    await use(page)
  },

  viewerPage: async ({ page }, use) => {
    await use(page)
  },

  approverPage: async ({ page }, use) => {
    await use(page)
  },

  auditorPage: async ({ page }, use) => {
    await use(page)
  },

  /**
   * A second Page in a separate browser context, for tests that need two
   * concurrent authenticated sessions (e.g. streaming/fanout). The test
   * must call `loginAs(secondPage, email, password)` before navigating.
   */
  secondPage: async ({ browser }, use) => {
    const context = await browser.newContext()
    const page = await context.newPage()
    await use(page)
    await context.close()
  },
})

export { expect }
