// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { test, expect } from "../fixtures"
import { E2E_ADMIN } from "../helpers/api"

/**
 * #30 — citation chips + source side-panel preview.
 *
 * Approach mirrors approval-card.spec: arrange project + conversation via the
 * API, then use Playwright route interception to inject a synthetic ASSISTANT
 * message whose payloadJson carries a citations array, and to mock the
 * chunk-context endpoint the side panel calls. This proves the UI chips +
 * panel + error handling without depending on a live agent + knowledge base.
 *
 * Routes are matched with RegExp (not globs) so the interception also covers
 * the "?limit=" query string the transcript loader appends.
 *
 * NOTE: Citation chips and the source preview side-panel are not yet
 * implemented in the UI. These tests are skipped until the feature is built.
 */
test.describe("#30 citation chips + source panel", () => {
  const kbId = "33333333-3333-3333-3333-333333333333"
  const chunkId = "44444444-4444-4444-4444-444444444444"
  const sourceId = "55555555-5555-5555-5555-555555555555"

  function assistantWithCitation(conversationId: string) {
    return {
      id: "66666666-6666-6666-6666-666666666666",
      conversationId,
      sequenceNo: 1,
      role: "ASSISTANT",
      content: "Per the onboarding guide, you reset your password from settings.",
      authorUserId: null,
      authorAgentId: null,
      modelCode: null,
      tokenCount: null,
      toolCallId: null,
      parentMessageId: null,
      payloadJson: JSON.stringify({
        citations: [
          {
            chunkId,
            sourceId,
            sourceName: "Onboarding Guide",
            locator: "https://docs.example.com/onboarding#reset",
            score: 0.91,
            knowledgeBaseId: kbId,
          },
        ],
      }),
      approvalStatus: null,
      approverId: null,
      expiresAt: null,
      pinned: false,
      feedbackRating: null,
      feedbackReason: null,
      feedbackBy: null,
      feedbackAt: null,
      superseded: false,
      createdAt: new Date().toISOString(),
    }
  }

  test.skip("opens the source panel from a citation chip", async ({ api, adminPage }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    const project = await api.request<{ id: string }>("POST", "/projects", {
      name: `cite-ok-${Date.now()}`,
    })
    const conversation = await api.request<{ id: string }>(
      "POST",
      "/conversations",
      { projectId: project.id, title: "citation spec" },
    )

    await adminPage.route(
      new RegExp(`/conversations/${conversation.id}/messages`),
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify([assistantWithCitation(conversation.id)]),
        })
      },
    )

    await adminPage.route(
      new RegExp(
        `/projects/${project.id}/knowledge-bases/${kbId}/chunks/${chunkId}/context`,
      ),
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            chunkId,
            sourceId,
            sourceName: "Onboarding Guide",
            locator: "https://docs.example.com/onboarding#reset",
            passage: "To reset your password, open Settings and choose Security.",
            before: ["Welcome to the platform."],
            after: ["You will receive a confirmation email."],
          }),
        })
      },
    )

    await adminPage.goto(`/projects/${project.id}/chat`)

    const citations = adminPage.getByRole("group", { name: "Citations" })
    await expect(citations).toBeVisible()
    const chip = adminPage.getByRole("button", { name: "Citation: Onboarding Guide" })
    await expect(chip).toBeVisible()

    await chip.click()

    const panel = adminPage.getByRole("complementary", { name: "Source preview" })
    await expect(panel).toBeVisible()
    await expect(adminPage.getByTestId("source-panel-passage")).toContainText(
      "open Settings and choose Security",
    )
    await expect(adminPage.getByRole("link", { name: "Open source" })).toBeVisible()

    await adminPage.getByRole("button", { name: "Close preview" }).click()
    await expect(panel).toHaveCount(0)
  })

  test.skip("shows an alert when the source is unavailable", async ({ api, adminPage }) => {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    const project = await api.request<{ id: string }>("POST", "/projects", {
      name: `cite-403-${Date.now()}`,
    })
    const conversation = await api.request<{ id: string }>(
      "POST",
      "/conversations",
      { projectId: project.id, title: "citation 403 spec" },
    )

    await adminPage.route(
      new RegExp(`/conversations/${conversation.id}/messages`),
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify([assistantWithCitation(conversation.id)]),
        })
      },
    )

    await adminPage.route(
      new RegExp(
        `/projects/${project.id}/knowledge-bases/${kbId}/chunks/${chunkId}/context`,
      ),
      async (route) => {
        await route.fulfill({
          status: 403,
          contentType: "application/json",
          body: JSON.stringify({
            errorCode: "FORBIDDEN",
            message: "Insufficient permissions.",
            details: null,
          }),
        })
      },
    )

    await adminPage.goto(`/projects/${project.id}/chat`)

    await adminPage.getByRole("button", { name: "Citation: Onboarding Guide" }).click()

    const panel = adminPage.getByRole("complementary", { name: "Source preview" })
    await expect(panel).toBeVisible()
    await expect(adminPage.getByRole("alert")).toContainText("Source unavailable")
  })
})