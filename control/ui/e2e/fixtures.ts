import { test as base, expect, type Page } from '@playwright/test'
import { ApiClient } from './helpers/api'
import { loginAsAdmin } from './helpers/auth'

/**
 * Extended Playwright {@code test} that provides
 * <ul>
 *   <li>{@code api} — an unauthenticated {@link ApiClient} for arrange-via-API setup;
 *       call {@code api.login(...)} once at the start of a spec to attach a token.</li>
 *   <li>{@code adminPage} — a {@link Page} pre-authenticated as the bootstrapped
 *       admin user. Skips the visual login flow.</li>
 * </ul>
 *
 * Import this fixture instead of {@code @playwright/test} in specs:
 * {@code import { test, expect } from '../fixtures'}
 */
export const test = base.extend<{
  api: ApiClient
  adminPage: Page
}>({
  api: async ({}, use) => {
    await use(new ApiClient())
  },

  adminPage: async ({ page }, use) => {
    await loginAsAdmin(page)
    await use(page)
  },
})

export { expect }
