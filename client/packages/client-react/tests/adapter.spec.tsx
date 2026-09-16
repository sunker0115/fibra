import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { ClientContext } from "@sstlfsj/fibra-client-api";
import { Scope } from "@sstlfsj/fibra-client-runtime";
import { registerReactMount } from "../src/index.js";

const context = (scope: Scope): ClientContext => ({
  scope: scope.view, host: { call: async () => null }, resources: { load: async () => { throw new Error("unused"); } },
});

describe("React reference adapter", () => {
  it("registers a mount factory with scope-owned withdrawal", async () => {
    const scope = new Scope();
    let registrations = 0;
    let removed = 0;
    registerReactMount(context(scope), (factory) => {
      assert.equal(typeof factory, "function"); registrations += 1;
      return { dispose: () => { removed += 1; } };
    }, () => null);
    assert.equal(registrations, 1);
    await scope.close();
    await scope.close();
    assert.equal(removed, 1);
  });

  it("does not publish a contribution after its instance scope closes", async () => {
    const scope = new Scope();
    await scope.close();
    assert.throws(() => registerReactMount(context(scope), () => {
      assert.fail("closed scope must not register a mount");
    }, () => null), /closed/);
  });
});
