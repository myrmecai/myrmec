// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Headless Supervisor entry point.
 *
 * Reads config from the environment and runs a `HeadlessAgentSupervisor`:
 * register → connect → receive/reconnect until SIGINT/SIGTERM.
 *
 *   MYRMEC_ENGINE_URL        base Engine URL (default http://localhost:8080)
 *   MYRMEC_REGISTRATION_KEY  agent registration key (myr_agent_…)  [required]
 *   MYRMEC_ATTACHMENT_MAX_IMAGE_BYTES  max bytes to inline an image (default 5 MiB)
 *   MYRMEC_WORKSPACE_ROOT    orchestration workspace root (design §17.1;
 *                            default /tmp/myrmec on POSIX, %TEMP%\myrmec
 *                            on Windows)
 *   MYRMEC_OUTBOX_ROOT       orchestration durable outbox root (default
 *                            \u003cworkspaceRoot\u003e/outbox)
 *   MYRMEC_LLM_EXECUTION      'stub' for deterministic E2E, 'real' otherwise
 *   MYRMEC_TOOL_EXECUTION     'stub' for deterministic E2E, 'real' otherwise
 *   MYRMEC_STUB_MODULE       stub handler module path (stub mode only)
 *
 * Dev:   npx tsx src/bin/headless.ts
 * Prod:  node dist/bin/headless.js
 */
import { HeadlessAgentSupervisor } from "../supervisor/index.js";

async function main(): Promise<void> {
  const engineUrl = process.env.MYRMEC_ENGINE_URL ?? "http://localhost:8080";
  const registrationKey = process.env.MYRMEC_REGISTRATION_KEY;

  if (!registrationKey) {
    console.error("MYRMEC_REGISTRATION_KEY is required");
    process.exit(2);
  }

  const maxImageBytesRaw = process.env.MYRMEC_ATTACHMENT_MAX_IMAGE_BYTES;
  const maxImageBytes =
    maxImageBytesRaw !== undefined && maxImageBytesRaw.trim() !== ""
      ? Number.parseInt(maxImageBytesRaw, 10)
      : undefined;
  if (maxImageBytes !== undefined && (!Number.isFinite(maxImageBytes) || maxImageBytes <= 0)) {
    console.error("MYRMEC_ATTACHMENT_MAX_IMAGE_BYTES must be a positive integer");
    process.exit(2);
  }

  const supervisor = new HeadlessAgentSupervisor({
    engineUrl,
    registrationKey,
    ...(maxImageBytes !== undefined ? { maxImageBytes } : {}),
  });

  const shutdown = (signal: string): void => {
    console.info(`Received ${signal}, shutting down…`);
    void supervisor.stop(`${signal} received`).then(() => process.exit(0));
  };
  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));

  await supervisor.start();
  // Keep the process alive; the socket close handler drives reconnect.
  await new Promise(() => {});
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
