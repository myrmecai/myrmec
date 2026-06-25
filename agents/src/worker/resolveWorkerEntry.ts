// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Resolves the constructor arguments for a worker_thread entry, bridging the
 * dev (tsx, `.ts` source) and prod (`tsc` output, `.js`) seam.
 *
 * Why this exists: `new Worker(spec)` needs something the worker runtime can
 * load. In prod that is the compiled `.js` module. In dev the source is `.ts`,
 * and tsx's loader is NOT inherited by worker threads — the documented
 * `execArgv: ["--import", "tsx"]` flag does not register the loader inside a
 * worker on Node 22. The reliable approach is an `eval` bootstrap that calls
 * tsx's programmatic `register()` inside the worker and then dynamically
 * imports the real `.ts` entry. Centralizing this keeps the dev/prod seam out
 * of the worker lifecycle code (§9.6.7: don't let interactive/dev concerns
 * leak).
 */
import { fileURLToPath, pathToFileURL } from "node:url";
import type { WorkerOptions } from "node:worker_threads";

/** Constructor arguments for `new Worker(spec, options)`. */
export interface WorkerSource {
  /** Module specifier or, in dev, the bootstrap source (with `eval: true`). */
  spec: string | URL;
  /** Worker options to merge (sets `eval: true` in dev). */
  options: WorkerOptions;
}

/**
 * @param metaUrl   the caller's `import.meta.url`
 * @param baseName  entry file base name without extension, e.g. "smoke-worker"
 * @returns args to pass to the `Worker` constructor for dev or prod
 */
export function resolveWorkerSource(metaUrl: string, baseName: string): WorkerSource {
  const callerPath = fileURLToPath(metaUrl);
  const runningFromSource = callerPath.endsWith(".ts");
  const sep = callerPath.includes("\\") ? "\\" : "/";
  const dir = callerPath.slice(0, callerPath.lastIndexOf(sep) + 1);

  if (!runningFromSource) {
    return { spec: pathToFileURL(`${dir}${baseName}.js`), options: {} };
  }

  // Dev: register tsx inside the worker, then import the .ts entry.
  const targetUrl = pathToFileURL(`${dir}${baseName}.ts`).href;
  const bootstrap =
    `import { register } from "tsx/esm/api";\n` +
    `register();\n` +
    `await import(${JSON.stringify(targetUrl)});\n`;
  return { spec: bootstrap, options: { eval: true } };
}
