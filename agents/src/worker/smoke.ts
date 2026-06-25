// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * worker_threads smoke test (Model B verification).
 *
 * Confirms the two properties the whole concurrency model rests on:
 *   1. message-passing parent ↔ worker works (isolated V8 heap, REQ-A-080);
 *   2. a worker crash is CONTAINED — the parent observes the `error`/`exit`
 *      and keeps running (REQ-A-085).
 *
 * Run in dev:   npx tsx src/worker/smoke.ts
 * Run compiled: node dist/worker/smoke.js
 * Exit code 0 = both properties hold.
 */
import { Worker } from "node:worker_threads";
import { resolveWorkerSource } from "./resolveWorkerEntry.js";

async function main(): Promise<void> {
  const { spec, options } = resolveWorkerSource(import.meta.url, "smoke-worker");
  const worker = new Worker(spec, options);

  let gotReply = false;
  let gotError = false;

  const done = new Promise<void>((resolve) => {
    worker.on("message", (msg) => {
      gotReply = true;
      console.log("[parent] reply from worker:", msg);
    });

    // The crash surfaces here, NOT as an uncaught exception in the parent.
    worker.on("error", (err: Error) => {
      gotError = true;
      console.log("[parent] worker crashed (contained):", err.message);
    });

    worker.on("exit", (code) => {
      console.log(`[parent] worker exited with code ${code}`);
      resolve();
    });
  });

  worker.postMessage({ ping: "hello" });
  await done;

  // The parent is still alive here — that is the point of the test.
  if (gotReply && gotError) {
    console.log("[parent] PASS — reply received AND crash contained.");
    process.exit(0);
  } else {
    console.error(
      `[parent] FAIL — gotReply=${gotReply} gotError=${gotError}`,
    );
    process.exit(1);
  }
}

main().catch((err) => {
  console.error("[parent] unexpected:", err);
  process.exit(1);
});
