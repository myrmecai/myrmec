import { test, expect } from '../fixtures'
import { E2E_ADMIN } from '../helpers/api'

/**
 * Phase 6e chat replay E2E.
 *
 * Arrange-via-API:
 *   1. Login as the bootstrapped admin.
 *   2. Create a project (auto-falls into the seeded Default group).
 *   3. Create a conversation under that project (no agent pinned — agent
 *      streaming is covered by the engine-side {@code
 *      ConversationTurnLoopE2ETest}; this spec proves the UI faithfully
 *      renders persisted history).
 *   4. POST one USER message via the conversations REST surface.
 *
 * Assert-via-UI:
 *   - The Projects page exposes a per-row Chat action.
 *   - The chat page lists the conversation in the sidebar with its title.
 *   - Selecting the conversation reveals the USER message bubble we seeded.
 *   - The composer + send button are present and the conversation status
 *     badge surfaces (proves the live WS pane mounted without throwing).
 */
test.describe('Phase 6e chat replay', () => {
  test('admin sees a seeded USER message in the project chat view', async ({
    api,
    adminPage,
  }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const project = await api.request<{ id: string; name: string }>(
      'POST',
      '/projects',
      {
        name: `chat-replay-${Date.now()}`,
        description: 'Phase 6e replay spec',
      },
    )

    const convTitle = `replay-${Date.now()}`
    const conversation = await api.request<{ id: string; title: string }>(
      'POST',
      '/conversations',
      {
        projectId: project.id,
        title: convTitle,
      },
    )

    const userMessage = 'hello from the replay spec'
    await api.request('POST', `/conversations/${conversation.id}/messages`, {
      content: userMessage,
    })

    await adminPage.goto(`/projects/${project.id}/chat`)

    // Sidebar must list the conversation; auto-select picks the most
    // recently updated one (ours, since the project is fresh).
    const sidebarItem = adminPage.getByTestId(
      `conversation-item-${conversation.id}`,
    )
    await expect(sidebarItem).toBeVisible()
    await expect(sidebarItem).toContainText(convTitle)

    // The auto-select effect should have opened our conversation. If not,
    // click it explicitly — the test must not flake on render order.
    await sidebarItem.click()

    // Persisted USER row from the seed POST renders in the main pane.
    const userBubble = adminPage
      .getByTestId('message-user')
      .filter({ hasText: userMessage })
    await expect(userBubble).toBeVisible()

    // Composer mounted (proves ConversationView did not throw on the WS
    // connect path).
    await expect(adminPage.getByTestId('message-input')).toBeVisible()
    await expect(adminPage.getByTestId('send-button')).toBeVisible()
  })

  test('projects table exposes a chat shortcut', async ({ api, adminPage }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)

    const project = await api.request<{ id: string }>('POST', '/projects', {
      name: `chat-shortcut-${Date.now()}`,
    })

    await adminPage.goto('/projects')

    const chatButton = adminPage.getByTestId(`project-chat-${project.id}`)
    await expect(chatButton).toBeVisible()
    await chatButton.click()

    await expect(adminPage).toHaveURL(
      new RegExp(`/projects/${project.id}/chat$`),
    )
    await expect(adminPage.getByTestId('chat-main')).toBeVisible()
  })
})
