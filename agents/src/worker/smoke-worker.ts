// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Smoke-test worker body. Receives one message, replies, then deliberately
 * throws to simulate a crashing Agent isolate. The parent must survive
 * (REQ-A-085: a worker crash is contained to its own isolate).
 */
import { parentPort } from "node:worker_threads";

if (!parentPort) {
  throw new Error("smoke-worker must be run as a worker_thread");
}

parentPort.on("message", (msg: unknown) => {
  // 1. Prove message-passing works: echo back.
  parentPort!.postMessage({ echo: msg, pid: process.pid });

  // 2. Simulate a crash on the next tick — AFTER the reply is sent — so the
  //    parent observes both a successful reply and an isolated failure.
  setTimeout(() => {
    throw new Error("intentional worker crash (smoke test)");
  }, 10);
});
