import assert from "node:assert/strict";
import { describe, it } from "node:test";

import type { LifecycleFence } from "@sstlfsj/fibra-client-api";
import { ClientLifecycleRuntime, ClientRuntimeError } from "../src/runtime.js";

const fence = (targetRevision: string, lifecycleOperationId: string): LifecycleFence => ({
  session: { hostInstanceId: "host-1", clientExecutionId: "client-1" },
  targetRevision,
  runtimeInstanceId: "runtime-1",
  lifecycleOperationId,
});

describe("ClientLifecycleRuntime", () => {
  it("serializes prepare, activate, drain and stop for one runtime instance", async () => {
    const calls: string[] = [];
    const runtime = new ClientLifecycleRuntime({
      prepare: async () => { calls.push("prepare"); },
      activate: async () => { calls.push("activate"); },
      drain: async () => { calls.push("drain"); },
      stop: async () => { calls.push("stop"); },
    });

    await Promise.all([
      runtime.execute("prepare", fence("1", "prepare")),
      runtime.execute("activate", fence("1", "activate")),
      runtime.execute("drain", fence("1", "drain")),
      runtime.execute("stop", fence("1", "stop")),
    ]);

    assert.deepEqual(calls, ["prepare", "activate", "drain", "stop"]);
  });

  it("returns the same pending command promise for an idempotent operation", async () => {
    let release: (() => void) | undefined;
    let prepareCalls = 0;
    const prepare = () => new Promise<void>((resolve) => { prepareCalls += 1; release = resolve; });
    const runtime = new ClientLifecycleRuntime({ prepare });
    const command = fence("1", "prepare-1");

    const first = runtime.execute("prepare", command);
    const second = runtime.execute("prepare", command);
    assert.equal(second, first);
    await Promise.resolve();
    release?.();
    await first;
    assert.equal(prepareCalls, 1);
  });

  it("rejects a late response from a different lifecycle operation", async () => {
    const runtime = new ClientLifecycleRuntime({ prepare: async () => undefined });
    const first = fence("1", "operation-1");
    const second = fence("2", "operation-2");

    runtime.begin(first);
    runtime.begin(second);

    assert.throws(() => runtime.accept(first), ClientRuntimeError);
    assert.doesNotThrow(() => runtime.accept(second));
    assert.throws(() => runtime.accept(second), ClientRuntimeError);
  });

  it("does not let a late A acknowledgement confirm a later A", async () => {
    const runtime = new ClientLifecycleRuntime({ prepare: async () => undefined });
    const firstA = fence("1", "a-first");
    const b = { ...fence("2", "b"), runtimeInstanceId: "runtime-b" };
    const secondA = fence("3", "a-second");

    runtime.begin(firstA);
    runtime.begin(b);
    runtime.begin(secondA);

    assert.throws(() => runtime.accept(firstA), ClientRuntimeError);
    assert.doesNotThrow(() => runtime.accept(secondA));
  });
});
