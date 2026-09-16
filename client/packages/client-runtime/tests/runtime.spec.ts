import assert from "node:assert/strict";
import { describe, it } from "node:test";

import type { Assignment, ClientResourceRequest, LifecycleFence, SessionFence } from "@sstlfsj/fibra-client-api";
import { ClientRuntimeError, ClientSessionRuntime } from "../src/runtime.js";

const session: SessionFence = { hostInstanceId: "host-1", clientExecutionId: "client-1" };
const digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
const assignment = (runtimeInstanceId = "runtime-1"): Assignment => ({
  pluginId: "plugin-1", facetId: "client", runtimeInstanceId, executionTarget: "client:web",
  entryModule: "index.js", payloadDigest: digest, requiredCapabilities: [],
  resources: [{ path: "index.js", digest, byteLength: "0" }],
});
const fence = (phase: string, runtimeInstanceId = "runtime-1", targetRevision = "5"): LifecycleFence => ({
  session, targetRevision, runtimeInstanceId, lifecycleOperationId: `${phase}-${runtimeInstanceId}`,
});

describe("ClientSessionRuntime", () => {
  it("authorizes actors only from snapshots and commits the complete lifecycle after handlers succeed", async () => {
    const calls: string[] = [];
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({
      prepare: async () => { calls.push("prepare"); }, activate: async () => { calls.push("activate"); },
      drain: async () => { calls.push("drain"); }, stop: async () => { calls.push("stop"); },
    }) });
    await assert.rejects(runtime.execute("prepare", fence("prepare")), ClientRuntimeError);
    await assert.rejects(runtime.execute("prepare", { ...fence("old-session"), session: { hostInstanceId: "old", clientExecutionId: "client-1" } }), ClientRuntimeError);
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment()], contributions: [] });
    await assert.rejects(runtime.execute("prepare", fence("old-revision", "runtime-1", "4")), ClientRuntimeError);
    for (const phase of ["prepare", "activate", "drain", "stop"] as const) await runtime.execute(phase, fence(phase));
    assert.deepEqual(calls, ["prepare", "activate", "drain", "stop"]);
    assert.equal(runtime.phaseOf("runtime-1"), "STOPPED");
  });

  it("shares duplicate operations, rejects a conflicting replay, and allows independent actors to run concurrently", async () => {
    let release: (() => void) | undefined;
    const started: string[] = [];
    const runtime = new ClientSessionRuntime({ session, createModule: async (item) => ({ prepare: () => item.runtimeInstanceId === "runtime-1"
      ? new Promise<void>((resolve) => { started.push(item.runtimeInstanceId); release = resolve; })
      : void started.push(item.runtimeInstanceId) }) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment(), assignment("runtime-2")], contributions: [] });
    const first = runtime.execute("prepare", fence("same"));
    assert.equal(runtime.execute("prepare", fence("same")), first);
    await assert.rejects(runtime.execute("activate", fence("same")), ClientRuntimeError);
    await runtime.execute("prepare", fence("other", "runtime-2"));
    assert.deepEqual(started, ["runtime-1", "runtime-2"]);
    release?.();
    await first;
  });

  it("fails terminally, skips queued phases, but stop still runs and revocation retires the actor", async () => {
    const calls: string[] = [];
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({
      prepare: async () => { calls.push("prepare"); throw new Error("prepare failed"); },
      activate: async () => { calls.push("activate"); }, stop: async () => { calls.push("stop"); },
    }) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment()], contributions: [] });
    const prepare = runtime.execute("prepare", fence("prepare"));
    const activate = runtime.execute("activate", fence("activate"));
    await assert.rejects(prepare, /prepare failed/);
    await assert.rejects(activate, ClientRuntimeError);
    await runtime.execute("stop", fence("stop"));
    assert.deepEqual(calls, ["prepare", "stop"]);
    await runtime.applySnapshot({ session, viewRevision: "1", targetRevision: "6", assignments: [], contributions: [] });
    assert.equal(runtime.actorCount, 0);
  });

  it("keeps actor and ledger storage bounded across repeated revocations and detach", async () => {
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({}) });
    for (let index = 1; index <= 100; index += 1) {
      const revision = String(index);
      await runtime.applySnapshot({ session, viewRevision: revision, targetRevision: revision, assignments: [assignment(`runtime-${index}`)], contributions: [] });
      for (const phase of ["prepare", "activate", "drain", "stop"] as const) {
        await runtime.execute(phase, fence(`${phase}-${index}`, `runtime-${index}`, revision));
      }
      await runtime.applySnapshot({ session, viewRevision: `${index}-revoke`, targetRevision: revision, assignments: [], contributions: [] });
    }
    await runtime.detach(session);
    assert.equal(runtime.actorCount, 0);
    assert.equal(runtime.operationCount, 0);
  });

  it("binds resource requests to the session assignment and keeps the cache through instance stop", async () => {
    let requests: ClientResourceRequest[] = [];
    const runtime = new ClientSessionRuntime({
      session,
      verifiedResourceLoader: { loadVerified: async (request) => {
        requests.push(request);
        return { bytes: new Uint8Array(), byteLength: request.descriptor.byteLength };
      } },
      createModule: async () => ({ prepare: async (context) => { await context.resources.load("index.js"); } }),
    });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment(), assignment("runtime-2")], contributions: [] });
    await runtime.execute("prepare", fence("prepare"));
    await runtime.execute("activate", fence("activate"));
    await runtime.execute("drain", fence("drain"));
    await runtime.execute("stop", fence("stop"));
    await runtime.execute("prepare", fence("prepare-2", "runtime-2"));
    assert.equal(requests.length, 1);
    assert.deepEqual(requests[0], { session, targetRevision: "5", runtimeInstanceId: "runtime-1", descriptor: assignment().resources[0] });
    await runtime.detach(session);
  });

  it("serializes overlapping detach calls and returns their one terminal promise", async () => {
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({
      prepare: async (context) => { context.scope.effect(() => undefined); },
    }) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment()], contributions: [] });
    await runtime.execute("prepare", fence("prepare"));
    const first = runtime.detach(session);
    const second = runtime.detach(session);
    assert.equal(second, first);
    await first;
  });

  it("rejects an atomically invalid snapshot without changing existing actor authorization", async () => {
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({}) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment()], contributions: [] });
    await assert.rejects(runtime.applySnapshot({
      session, viewRevision: "1", targetRevision: "6",
      assignments: [assignment(), { ...assignment("runtime-2"), entryModule: "missing.js" }], contributions: [],
    }), ClientRuntimeError);
    await runtime.execute("prepare", fence("prepare"));
  });

  it("rejects an old stop queued before a newer snapshot revision", async () => {
    let release: (() => void) | undefined;
    let stopped = 0;
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({
      prepare: () => new Promise<void>((resolve) => { release = resolve; }), stop: () => { stopped += 1; },
    }) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment()], contributions: [] });
    const prepare = runtime.execute("prepare", fence("prepare"));
    const activate = runtime.execute("activate", fence("activate"));
    const drain = runtime.execute("drain", fence("drain"));
    const stop = runtime.execute("stop", fence("stop"));
    const update = runtime.applySnapshot({ session, viewRevision: "1", targetRevision: "6", assignments: [assignment()], contributions: [] });
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    release?.();
    await assert.rejects(prepare, ClientRuntimeError);
    await assert.rejects(activate, ClientRuntimeError);
    await assert.rejects(drain, ClientRuntimeError);
    await update;
    await assert.rejects(stop, ClientRuntimeError);
    assert.equal(stopped, 0);
  });

  it("does not rerun stop after STOPPED or during retirement", async () => {
    let stopCalls = 0;
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({ stop: () => { stopCalls += 1; } }) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment()], contributions: [] });
    for (const phase of ["prepare", "activate", "drain", "stop"] as const) await runtime.execute(phase, fence(phase));
    await assert.rejects(runtime.execute("stop", fence("new-stop")), ClientRuntimeError);
    assert.equal(stopCalls, 1);
    assert.equal(runtime.operationCount, 4);
    await runtime.detach(session);
    assert.equal(stopCalls, 1);
  });

  it("rejects a second stop after failed prepare while replaying the accepted stop", async () => {
    let stopCalls = 0;
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({ prepare: () => { throw new Error("prepare"); }, stop: () => { stopCalls += 1; } }) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment()], contributions: [] });
    await assert.rejects(runtime.execute("prepare", fence("prepare")));
    const stop = runtime.execute("stop", fence("stop"));
    assert.equal(runtime.execute("stop", fence("stop")), stop);
    await stop;
    await assert.rejects(runtime.execute("stop", fence("other-stop")), ClientRuntimeError);
    assert.equal(stopCalls, 1);
  });

  it("preserves a failed stop cleanup for detach without rerunning it", async () => {
    let stopCalls = 0;
    const failure = new Error("stop cleanup");
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({ stop: () => { stopCalls += 1; throw failure; } }) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment()], contributions: [] });
    await runtime.execute("prepare", fence("prepare"));
    for (const phase of ["prepare", "activate", "drain"] as const) await runtime.execute(phase, fence(phase));
    await assert.rejects(runtime.execute("stop", fence("stop")), /stop cleanup/);
    await assert.rejects(runtime.detach(session), /stop cleanup/);
    assert.equal(stopCalls, 1);
  });

  it("does not commit STOPPED when a pending stop becomes stale", async () => {
    let release: (() => void) | undefined;
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({ stop: () => new Promise<void>((resolve) => { release = resolve; }) }) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment()], contributions: [] });
    for (const phase of ["prepare", "activate", "drain"] as const) await runtime.execute(phase, fence(phase));
    const stop = runtime.execute("stop", fence("stop"));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    const revision = runtime.applySnapshot({ session, viewRevision: "1", targetRevision: "6", assignments: [assignment()], contributions: [] });
    release?.();
    await revision;
    await assert.rejects(stop, ClientRuntimeError);
    assert.notEqual(runtime.phaseOf("runtime-1"), "STOPPED");
  });

  it("turns a stale accepted prepare into FAILED so queued activation cannot run", async () => {
    let created = 0;
    const runtime = new ClientSessionRuntime({ session, createModule: async () => { created += 1; return {}; } });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "1", assignments: [assignment()], contributions: [] });
    const revision = runtime.applySnapshot({ session, viewRevision: "1", targetRevision: "2", assignments: [assignment()], contributions: [] });
    const prepare = runtime.execute("prepare", fence("prepare", "runtime-1", "1"));
    const activate = runtime.execute("activate", fence("activate", "runtime-1", "1"));
    await revision;
    await assert.rejects(prepare, ClientRuntimeError);
    await assert.rejects(activate, ClientRuntimeError);
    assert.equal(created, 0);
  });

  it("accepts retained assignments with equivalent object fields in a different insertion order", async () => {
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({}) });
    const first = assignment();
    const reordered: Assignment = { runtimeInstanceId: first.runtimeInstanceId, facetId: first.facetId, pluginId: first.pluginId,
      executionTarget: first.executionTarget, entryModule: first.entryModule, payloadDigest: first.payloadDigest,
      requiredCapabilities: first.requiredCapabilities, resources: first.resources };
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [first], contributions: [] });
    await runtime.applySnapshot({ session, viewRevision: "1", targetRevision: "6", assignments: [reordered], contributions: [] });
  });

  it("retains failed owner and blocks unsafe replacement", async () => {
    let stopCalls = 0;
    const failure = new Error("cleanup failed");
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({ stop: () => { stopCalls += 1; throw failure; } }) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "5", assignments: [assignment()], contributions: [] });
    await runtime.execute("prepare", fence("prepare"));
    const replacement = { session, viewRevision: "1", targetRevision: "6", assignments: [assignment("runtime-2")], contributions: [] };
    const first = runtime.applySnapshot(replacement);
    await assert.rejects(first, /cleanup failed/);
    const second = runtime.applySnapshot(replacement);
    await assert.rejects(second, /cleanup failed/);
    assert.equal(runtime.actorCount, 1);
    assert.equal(runtime.phaseOf("runtime-2"), undefined);
    assert.equal(stopCalls, 1);
  });

  it("releases a stale stop reservation before cleanup so the current stop can run", async () => {
    let stopCalls = 0;
    let scopeClosed = false;
    const runtime = new ClientSessionRuntime({ session, createModule: async () => ({
      drain: (context) => { context.scope.effect(() => { scopeClosed = true; }); }, stop: () => { stopCalls += 1; },
    }) });
    await runtime.applySnapshot({ session, viewRevision: "0", targetRevision: "1", assignments: [assignment()], contributions: [] });
    for (const phase of ["prepare", "activate", "drain"] as const) await runtime.execute(phase, fence(phase, "runtime-1", "1"));
    const revision = runtime.applySnapshot({ session, viewRevision: "1", targetRevision: "2", assignments: [assignment()], contributions: [] });
    const stale = runtime.execute("stop", fence("stop-old", "runtime-1", "1"));
    await revision;
    await assert.rejects(stale, ClientRuntimeError);
    assert.equal(stopCalls, 0);
    assert.equal(scopeClosed, false);
    await runtime.execute("stop", fence("stop-current", "runtime-1", "2"));
    assert.equal(stopCalls, 1);
    assert.equal(scopeClosed, true);
    assert.equal(runtime.phaseOf("runtime-1"), "STOPPED");
  });
});
