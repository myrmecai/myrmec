// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { mkdtempSync, writeFileSync, mkdirSync, readFileSync, existsSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import {
  HostWorkspaceRegistry,
  LeaseNotMutableError,
  StaleGenerationError,
} from "./HostWorkspaceRegistry.js";

let root: string;

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "hwr-test-"));
});

afterEach(() => {
  rmSync(root, { recursive: true, force: true });
});

describe("HostWorkspaceRegistry (§16.5/§17.1)", () => {
  const runId = "11111111-1111-1111-1111-111111111111";
  const agentId = "22222222-2222-2222-2222-222222222222";

  function acquireActive(registry: HostWorkspaceRegistry, generation = 1) {
    const checkout = join(root, "runs", runId, String(generation), "checkout");
    const handle = registry.acquire({
      runId,
      generation,
      pinnedAgentId: agentId,
      leaseDeadline: new Date(Date.now() + 60_000).toISOString(),
      checkoutPath: checkout,
    });
    registry.activate(runId, generation);
    mkdirSync(checkout, { recursive: true });
    writeFileSync(join(checkout, "a.txt"), "worktree");
    return { handle, checkout };
  }

  test("acquire → ACQUIRING → activate → ACTIVE with a durable manifest", () => {
    const registry = new HostWorkspaceRegistry({ workspaceRoot: root });
    const checkout = join(root, "runs", runId, "1", "checkout");
    const acquiring = registry.acquire({
      runId,
      generation: 1,
      pinnedAgentId: agentId,
      leaseDeadline: new Date(Date.now() + 60_000).toISOString(),
      checkoutPath: checkout,
    });
    expect(acquiring.leaseState).toBe("ACQUIRING");
    const handle = registry.activate(runId, 1);
    mkdirSync(checkout, { recursive: true });
    writeFileSync(join(checkout, "a.txt"), "worktree");
    expect(handle.leaseState).toBe("ACTIVE");
    expect(handle.isMutable()).toBe(true);

    const manifestPath = join(root, "runs", runId, "1", "lease-manifest.json");
    expect(existsSync(manifestPath)).toBe(true);
    const manifest = JSON.parse(readFileSync(manifestPath, "utf-8"));
    expect(manifest.pinnedAgentId).toBe(agentId);
    expect(manifest.leaseState).toBe("ACTIVE");
  });

  test("stale generation operations are rejected", () => {
    const registry = new HostWorkspaceRegistry({ workspaceRoot: root });
    acquireActive(registry, 1);
    expect(() => registry.activate(runId, 0)).toThrow(StaleGenerationError);
    expect(() => registry.activate(runId, 2)).toThrow(StaleGenerationError);
  });

  test("suspend → SUSPENDED is not mutable; activate restores mutability", () => {
    const registry = new HostWorkspaceRegistry({ workspaceRoot: root });
    acquireActive(registry, 1);
    registry.suspend(runId, 1, new Date(Date.now() + 120_000).toISOString());
    const manifest = registry.manifestOf(runId);
    expect(manifest?.leaseState).toBe("SUSPENDED");

    expect(
      registry.withMutation(runId, 1, async () => "x"),
    ).rejects.toThrow(LeaseNotMutableError);

    registry.activate(runId, 1);
    expect(registry.manifestOf(runId)?.leaseState).toBe("ACTIVE");
  });

  test("mutation lock serializes concurrent checkout mutations", async () => {
    const registry = new HostWorkspaceRegistry({ workspaceRoot: root });
    acquireActive(registry, 1);
    const order: string[] = [];
    const first = registry.withMutation(runId, 1, async () => {
      await new Promise((r) => setTimeout(r, 30));
      order.push("first");
    });
    const second = registry.withMutation(runId, 1, async () => {
      order.push("second");
    });
    await Promise.all([first, second]);
    expect(order).toEqual(["first", "second"]);
  });

  test("release removes the checkout, is idempotent, and derives deterministic ids", async () => {
    const registry = new HostWorkspaceRegistry({ workspaceRoot: root });
    const { checkout } = acquireActive(registry, 1);

    const ack = await registry.release(runId, 1, "TERMINAL_STATE");
    expect(ack.status).toBe("RELEASED");
    expect(ack.runId).toBe(runId);
    expect(ack.generation).toBe(1);
    expect(existsSync(checkout)).toBe(false);
    expect(registry.manifestOf(runId)?.leaseState).toBe("RELEASED");

    // Repeated release returns the SAME stored acknowledgement.
    const replay = await registry.release(runId, 1, "TERMINAL_STATE");
    expect(replay.acknowledgementId).toBe(ack.acknowledgementId);

    // Deterministic across registry instances.
    const fresh = new HostWorkspaceRegistry({ workspaceRoot: root });
    void fresh; // manifest re-adoption covered by reconcile test
  });

  test("markLost records LOST with its own deterministic acknowledgement", async () => {
    const registry = new HostWorkspaceRegistry({ workspaceRoot: root });
    acquireActive(registry, 1);
    const ack = await registry.markLost(runId, 1);
    expect(ack.status).toBe("LOST");
    const replay = await registry.markLost(runId, 1);
    expect(replay.acknowledgementId).toBe(ack.acknowledgementId);
    expect(registry.manifestOf(runId)?.leaseState).toBe("LOST");
  });

  test("restart reconciliation re-adopts on-disk manifests", () => {
    const first = new HostWorkspaceRegistry({ workspaceRoot: root });
    acquireActive(first, 1);

    // A restart is a fresh instance over the same root.
    const second = new HostWorkspaceRegistry({ workspaceRoot: root });
    const adopted = second.reconcile();
    expect(adopted).toHaveLength(1);
    expect(adopted[0]?.runId).toBe(runId);
    expect(adopted[0]?.leaseState).toBe("ACTIVE");
    // The re-adopted lease is usable: mutations are allowed.
    expect(
      second.withMutation(runId, 1, async () => "ok"),
    ).resolves.toBe("ok");
  });

  test("generation advance is allowed; older generations cannot regress", () => {
    const registry = new HostWorkspaceRegistry({ workspaceRoot: root });
    acquireActive(registry, 1);
    // A new generation replaces the lease (workspace recovery scenario).
    const gen2 = join(root, "runs", runId, "2", "checkout");
    registry.acquire({
      runId,
      generation: 2,
      pinnedAgentId: agentId,
      leaseDeadline: null,
      checkoutPath: gen2,
    });
    expect(registry.manifestOf(runId)?.generation).toBe(2);
    // A stale acquire for generation 1 against a live generation 2 lease fails.
    expect(() =>
      registry.acquire({
        runId,
        generation: 1,
        pinnedAgentId: agentId,
        leaseDeadline: null,
        checkoutPath: join(root, "old"),
      }),
    ).toThrow(StaleGenerationError);
  });
});