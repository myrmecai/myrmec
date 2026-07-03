// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
import { test, expect } from "../fixtures"
import { E2E_ADMIN } from "../helpers/api"
import type { ApiClient } from "../helpers/api"
import type { Page, Route } from "@playwright/test"

/**
 * #103-C — promote a clean attachment into a project knowledge base.
 *
 * Mirrors citation-panel.spec: arrange a real project + conversation via the
 * API, then use Playwright route interception to inject a synthetic USER
 * message + its CLEAN attachment, list a knowledge base, and mock the promote
 * endpoint. This proves the overflow menu -> dialog -> success / duplicate
 * flow without depending on a live agent or KB ingestion pipeline.
 */
test.describe("#103-C promote attachment to knowledge base", () => {
  const messageId = "77777777-7777-7777-7777-777777777777"
  const attachmentId = "88888888-8888-8888-8888-888888888888"
  const kbId = "99999999-9999-9999-9999-999999999999"
  const sourceId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

  function userMessage(conversationId: string) {
    return {
      id: messageId,
      conversationId,
      sequenceNo: 1,
      role: "USER",
      content: "Here are the onboarding notes.",
      authorUserId: null,
      authorAgentId: null,
      modelCode: null,
      tokenCount: null,
      toolCallId: null,
      parentMessageId: null,
      payloadJson: null,
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

  function cleanAttachment(conversationId: string) {
    return {
      id: attachmentId,
      conversationId,
      messageId,
      uploadedBy: null,
      filename: "onboarding.txt",
      mediaType: "text/plain",
      sizeBytes: 1024,
      sha256: null,
      scanStatus: "CLEAN",
      scanThreat: null,
      createdAt: new Date().toISOString(),
    }
  }

  function knowledgeBase(projectId: string) {
    return {
      id: kbId,
      name: "Docs KB",
      description: null,
      scope: "PROJECT",
      projectId,
      providerId: "builtin",
      status: "ACTIVE",
      classification: null,
      allowAssistantBinding: true,
      sourceCount: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
  }

  async function arrange(api: ApiClient, adminPage: Page, slug: string) {
    await api.login(E2E_ADMIN.email, E2E_ADMIN.password)
    const project = await api.request<{ id: string }>("POST", "/projects", {
      name: `promote-${slug}-${Date.now()}`,
    })
    const conversation = await api.request<{ id: string }>("POST", "/conversations", {
      projectId: project.id,
      title: "promote spec",
    })

    await adminPage.route(
      new RegExp(`/conversations/${conversation.id}/messages`),
      async (route: Route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify([userMessage(conversation.id)]),
        })
      },
    )
    await adminPage.route(
      new RegExp(`/conversations/${conversation.id}/attachments`),
      async (route: Route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify([cleanAttachment(conversation.id)]),
        })
      },
    )
    await adminPage.route(
      new RegExp(`/projects/${project.id}/knowledge-bases$`),
      async (route: Route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify([knowledgeBase(project.id)]),
        })
      },
    )
    return { project, conversation }
  }

  test("promotes a clean attachment and announces success", async ({ api, adminPage }) => {
    const { project, conversation } = await arrange(api, adminPage, "ok")

    await adminPage.route(
      new RegExp(
        `/projects/${project.id}/conversations/${conversation.id}/attachments/${attachmentId}/promote`,
      ),
      async (route: Route) => {
        await route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify({ knowledgeSourceId: sourceId, status: "INGESTING" }),
        })
      },
    )

    await adminPage.goto(`/projects/${project.id}/chat`)

    await adminPage.getByTestId("attachment-actions").click()
    await adminPage
      .getByRole("menuitem", { name: "Promote to knowledge base" })
      .click()

    const dialog = adminPage.getByRole("dialog", { name: "Promote to knowledge base" })
    await expect(dialog).toBeVisible()

    await adminPage.getByRole("combobox", { name: "Knowledge base" }).click()
    await adminPage.getByRole("option", { name: "Docs KB" }).click()
    await adminPage.getByRole("button", { name: "Promote" }).click()

    await expect(adminPage.getByRole("status")).toContainText("Added to Docs KB")
    await expect(
      adminPage.getByRole("img", { name: "Promoted to knowledge base" }),
    ).toBeVisible()
  })

  test("shows a duplicate alert when already promoted", async ({ api, adminPage }) => {
    const { project, conversation } = await arrange(api, adminPage, "dup")

    await adminPage.route(
      new RegExp(
        `/projects/${project.id}/conversations/${conversation.id}/attachments/${attachmentId}/promote`,
      ),
      async (route: Route) => {
        await route.fulfill({
          status: 409,
          contentType: "application/json",
          body: JSON.stringify({
            errorCode: "DUPLICATE_CODE",
            message: "Already added.",
            details: null,
          }),
        })
      },
    )

    await adminPage.goto(`/projects/${project.id}/chat`)

    await adminPage.getByTestId("attachment-actions").click()
    await adminPage
      .getByRole("menuitem", { name: "Promote to knowledge base" })
      .click()

    await adminPage.getByRole("combobox", { name: "Knowledge base" }).click()
    await adminPage.getByRole("option", { name: "Docs KB" }).click()
    await adminPage.getByRole("button", { name: "Promote" }).click()

    await expect(adminPage.getByRole("alert")).toContainText(
      "Already added to this knowledge base",
    )
  })
})