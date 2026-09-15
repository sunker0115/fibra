import assert from "node:assert/strict";
import { describe, it } from "node:test";

import type { LifecycleFence } from "../src/protocol.js";
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

  it("rejects illegal lifecycle transitions", async () => {
    const runtime = new ClientLifecycleRuntime({ activate: async () => undefined });

    await assert.rejects(runtime.execute("activate", fence("1", "activate")), (error: unknown) => {
      assert(error instanceof ClientRuntimeError);
      assert.equal(error.code, "ILLEGAL_LIFECYCLE_TRANSITION");
      return true;
    });
  });

  it("does not block distinct runtime instances behind one pending handler", async () => {
    let release: (() => void) | undefined;
    const calls: string[] = [];
    const runtime = new ClientLifecycleRuntime({
      prepare: (command) => command.runtimeInstanceId === "runtime-a"
        ? new Promise<void>((resolve) => { release = resolve; })
        : void calls.push(command.runtimeInstanceId),
    });
    const first = runtime.execute("prepare", { ...fence("1", "a"), runtimeInstanceId: "runtime-a" });
    const second = runtime.execute("prepare", { ...fence("1", "b"), runtimeInstanceId: "runtime-b" });

    await second;
    assert.deepEqual(calls, ["runtime-b"]);
    release?.();
    await first;
  });

  it("tracks execute fences and uses collision-free identity tuples", async () => {
    const runtime = new ClientLifecycleRuntime({ prepare: async () => undefined });
    const first: LifecycleFence = {
      session: { hostInstanceId: "a", clientExecutionId: "b\u0000c" },
      targetRevision: "1", runtimeInstanceId: "runtime", lifecycleOperationId: "one",
    };
    const second: LifecycleFence = {
      session: { hostInstanceId: "a\u0000b", clientExecutionId: "c" },
      targetRevision: "1", runtimeInstanceId: "runtime", lifecycleOperationId: "two",
    };

    await runtime.execute("prepare", first);
    runtime.begin(second);
    assert.doesNotThrow(() => runtime.accept(first));
    assert.doesNotThrow(() => runtime.accept(second));
    assert.throws(() => runtime.accept(first), ClientRuntimeError);
  });
});
