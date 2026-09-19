import assert from "node:assert/strict";
import { describe, it } from "node:test";

const scope = {
  closed: false,
  child() { throw new Error("not used"); },
  effect() { throw new Error("not used"); },
  listen() { throw new Error("not used"); },
  timer() { throw new Error("not used"); },
};

const host = {
  async call() { throw new Error("not used"); },
};

function assignment(overrides = {}) {
  return {
    pluginId: "plugin",
    facetId: "client",
    desiredEntryId: "entry-one",
    definitionId: "alpha",
    runtimeInstanceId: "runtime-one",
    unitTargetRevision: "7",
    executionTarget: "client:web",
    config: { kind: "OBJECT", values: { mode: "one" } },
    entryModule: "index.js",
    payloadDigest: "digest",
    requiredCapabilities: [],
    resources: [],
    ...overrides,
  };
}

async function instantiate(entryModule, assignmentValue) {
  const matches = entryModule.definitions.filter(
    ({ definitionId }) => definitionId === assignmentValue.definitionId,
  );
  if (matches.length !== 1) {
    throw new Error(`expected exactly one client definition ${assignmentValue.definitionId}`);
  }
  const context = Object.freeze({
    desiredEntryId: assignmentValue.desiredEntryId,
    definitionId: assignmentValue.definitionId,
    unitTargetRevision: assignmentValue.unitTargetRevision,
    runtimeInstanceId: assignmentValue.runtimeInstanceId,
    config: assignmentValue.config,
    scope,
    host,
  });
  return { context, module: await matches[0].create(context) };
}

describe("client module definition contract", () => {
  it("selects two definitions deterministically from the same entry module", async () => {
    const created = [];
    const entryModule = {
      definitions: [
        {
          definitionId: "alpha",
          create(context) {
            created.push(["alpha", context.definitionId]);
            return {};
          },
        },
        {
          definitionId: "beta",
          create(context) {
            created.push(["beta", context.definitionId]);
            return {};
          },
        },
      ],
    };

    await instantiate(entryModule, assignment());
    await instantiate(entryModule, assignment({ definitionId: "beta" }));

    assert.deepEqual(created, [["alpha", "alpha"], ["beta", "beta"]]);
  });

  it("creates isolated instances and resolved config for two desired entries of one definition", async () => {
    const contexts = [];
    const entryModule = {
      definitions: [{
        definitionId: "alpha",
        create(context) {
          contexts.push(context);
          return {};
        },
      }],
    };

    const first = await instantiate(entryModule, assignment());
    const second = await instantiate(entryModule, assignment({
      desiredEntryId: "entry-two",
      runtimeInstanceId: "runtime-two",
      unitTargetRevision: "8",
      config: { kind: "OBJECT", values: { mode: "two" } },
    }));

    assert.notEqual(first.module, second.module);
    assert.notEqual(first.context, second.context);
    assert.deepEqual(contexts.map(({ desiredEntryId, definitionId, unitTargetRevision, runtimeInstanceId, config }) => ({
      desiredEntryId,
      definitionId,
      unitTargetRevision,
      runtimeInstanceId,
      config,
    })), [
      {
        desiredEntryId: "entry-one",
        definitionId: "alpha",
        unitTargetRevision: "7",
        runtimeInstanceId: "runtime-one",
        config: { kind: "OBJECT", values: { mode: "one" } },
      },
      {
        desiredEntryId: "entry-two",
        definitionId: "alpha",
        unitTargetRevision: "8",
        runtimeInstanceId: "runtime-two",
        config: { kind: "OBJECT", values: { mode: "two" } },
      },
    ]);
  });

  it("rejects missing and duplicate definitions before invoking a factory", async () => {
    let creates = 0;
    const definition = {
      definitionId: "alpha",
      create() {
        creates += 1;
        return {};
      },
    };

    await assert.rejects(instantiate({ definitions: [definition] }, assignment({ definitionId: "missing" })),
      /expected exactly one client definition missing/);
    await assert.rejects(instantiate({ definitions: [definition, definition] }, assignment()),
      /expected exactly one client definition alpha/);
    assert.equal(creates, 0);
  });
});
