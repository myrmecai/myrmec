// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Real `worker_thread` entry for an Agent worker.
 *
 * Boots one {@link AgentWorker} bound to `parentPort`: inbound control frames
 * arrive as messages and are dispatched; the worker's outbound frames are
 * posted back to the Supervisor (§9.7). Config arrives via `workerData` as
 * plain data. The worker resolves its own model per task/turn from the engine
 * descriptor — live adapters cannot cross the thread boundary.
 *
 * Dev:   loaded via the tsx bootstrap in resolveWorkerEntry.
 * Prod:  node dist/worker/agent-worker-entry.js (as a worker).
 */
import { parentPort, workerData } from "node:worker_threads";
import { AgentWorker } from "./agentWorker.js";
import { EngineHttpClient } from "../transport/httpClient.js";
import {
  createChatModelFactory,
  createSessionToolFactory,
} from "../executor/providers.js";
import type { AgentWorkerConfig, WorkerInbound } from "./agentWorkerProtocol.js";

if (!parentPort) {
  throw new Error("agent-worker-entry must run as a worker_thread");
}

const port = parentPort;
const config = (workerData ?? {}) as AgentWorkerConfig;

// Optionally construct an HTTP client if engine credentials are available.
let httpClient: EngineHttpClient | undefined;
if (config.engineUrl && config.agentAccessToken) {
  httpClient = new EngineHttpClient({
    engineUrl: config.engineUrl,
    registrationKey: "", // Not used for subsequent calls; we have an access token
  });
}

// Construct execution provider factories from config.
// Async because the stub implementation is loaded via dynamic import().
const chatModelFactory = await createChatModelFactory({
  mode: config.chatModelMode ?? "real",
  stubModulePath: config.stubModulePath,
});
const sessionToolFactory = await createSessionToolFactory({
  mode: config.sessionToolMode ?? "real",
  stubModulePath: config.stubModulePath,
});

const worker = new AgentWorker({
  post: (message) => port.postMessage(message),
  httpClient,
  agentAccessToken: config.agentAccessToken,
  chatModelFactory,
  sessionToolFactory,
  ...(config.maxIterations !== undefined
    ? { maxIterations: config.maxIterations }
    : {}),
  ...(config.maxImageBytes !== undefined
    ? { maxImageBytes: config.maxImageBytes }
    : {}),
  ...(config.workspaceRoot !== undefined
    ? { workspaceRoot: config.workspaceRoot }
    : {}),
  ...(config.outboxRoot !== undefined
    ? { outboxRoot: config.outboxRoot }
    : {}),
});

port.on("message", async (message: WorkerInbound) => {
  await worker.handle(message);
});
