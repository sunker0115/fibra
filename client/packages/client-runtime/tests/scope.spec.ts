import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { ClientScopeClosedError, Scope } from "../src/scope.js";

describe("Scope", () => {
  it("closes children and effects in reverse ownership order", async () => {
    const calls: string[] = [];
    const parent = new Scope();
    const first = parent.child();
    const second = parent.child();

    parent.effect(() => { calls.push("parent-first"); });
    parent.effect(() => { calls.push("parent-second"); });
    first.effect(() => { calls.push("first"); });
    second.effect(() => { calls.push("second"); });

    await parent.close();

    assert.deepEqual(calls, ["second", "first", "parent-second", "parent-first"]);
  });

  it("aggregates close failures while attempting every owner-bound cleanup", async () => {
    const scope = new Scope();
    let finalizerCalls = 0;
    scope.effect(() => { throw new Error("last"); });
    scope.effect(() => { throw new Error("first"); });
    scope.effect(() => { finalizerCalls += 1; });

    await assert.rejects(scope.close(), (error: unknown) => {
      assert(error instanceof AggregateError);
      assert.equal(error.errors.length, 2);
      return true;
    });
    assert.equal(finalizerCalls, 1);
  });

  it("shares the pending and final close promise", async () => {
    const scope = new Scope();
    let release: (() => void) | undefined;
    scope.effect(() => new Promise<void>((resolve) => { release = resolve; }));

    const first = scope.close();
    const second = scope.close();
    assert.equal(second, first);
    release?.();
    await first;
    assert.equal(scope.close(), first);
  });

  it("rejects new effects and children after closing begins", async () => {
    const scope = new Scope();
    const close = scope.close();

    assert.throws(() => scope.effect(() => undefined), ClientScopeClosedError);
    assert.throws(() => scope.child(), ClientScopeClosedError);
    await close;
  });

  it("owns listener and timer cleanup through effects", async () => {
    const scope = new Scope();
    let listenerCleanups = 0;
    let timerCleanups = 0;

    scope.listen(() => () => { listenerCleanups += 1; });
    scope.timer(() => () => { timerCleanups += 1; });
    await scope.close();

    assert.equal(listenerCleanups, 1);
    assert.equal(timerCleanups, 1);
  });
});
