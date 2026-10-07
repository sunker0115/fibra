import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

import { decodeEnvelope } from "../../packages/client-protocol/dist/index.js";

const fixtureDirectory = resolve(process.argv[2]
  ?? "fibra-parity-tests/target/client-protocol-conformance");

function snapshot(name) {
  const path = resolve(fixtureDirectory, `${name}.json`);
  assert.ok(existsSync(path), `missing real Engine/Java codec fixture: ${path}`);
  const envelope = decodeEnvelope(readFileSync(path, "utf8"));
  assert.equal(envelope.type, "host.snapshot");
  return envelope.payload;
}

const initial = snapshot("initial");
const candidate = snapshot("candidate");
const retiring = snapshot("retiring");
const replaced = snapshot("replaced");
const reconnected = snapshot("reconnected");
const removed = snapshot("removed");

function assignmentsByEntry(value) {
  return new Map(value.assignments.map(assignment => [assignment.desiredEntryId, assignment]));
}

function modes(value) {
  return value.assignments.map(assignment => [assignment.desiredEntryId, assignment.config]);
}

assert.equal(initial.targetRevision, "1");
assert.deepEqual(modes(initial), [
  ["entry-a", { kind: "OBJECT", values: { mode: "one" } }],
  ["entry-b", { kind: "OBJECT", values: { mode: "two" } }],
]);
assert.equal(initial.assignments[0].definitionId, "fixture");
assert.equal(initial.assignments[1].definitionId, "fixture");
assert.notEqual(initial.assignments[0].runtimeInstanceId, initial.assignments[1].runtimeInstanceId);
assert.equal(candidate.targetRevision, initial.targetRevision);
assert.deepEqual(candidate.assignments, initial.assignments,
  "an unpromoted candidate must not replace current assignments or resolved config");

const before = assignmentsByEntry(initial);
const after = assignmentsByEntry(replaced);
assert.equal(replaced.targetRevision, "2");
assert.equal(after.get("entry-a").unitTargetRevision, "2");
assert.notEqual(after.get("entry-a").runtimeInstanceId, before.get("entry-a").runtimeInstanceId);
assert.deepEqual(after.get("entry-b"), before.get("entry-b"),
  "the retained assignment must preserve its creation revision, identity and config");
assert.equal(after.get("entry-b").unitTargetRevision, "1");
assert.deepEqual(modes(replaced), [
  ["entry-a", { kind: "OBJECT", values: { mode: "updated" } }],
  ["entry-b", { kind: "OBJECT", values: { mode: "two" } }],
]);
assert.equal(retiring.targetRevision, "2");
assert.deepEqual(retiring.assignments, replaced.assignments,
  "retiring execution must not become an assignment in a new snapshot");
assert.ok(!retiring.assignments.some(assignment =>
  assignment.runtimeInstanceId === before.get("entry-a").runtimeInstanceId));
assert.equal(reconnected.session.hostInstanceId, replaced.session.hostInstanceId);
assert.notEqual(reconnected.session.clientExecutionId, replaced.session.clientExecutionId);
assert.deepEqual(reconnected.assignments, replaced.assignments);
assert.equal(removed.targetRevision, "3");
assert.deepEqual(removed.assignments, []);
assert.deepEqual(removed.contributions, []);

const created = [];
/** @type {import("../../packages/client-api/dist/index.js").ClientEntryModule} */
const entryModule = {
  definitions: [
    { definitionId: "unrelated", create() { throw new Error("selected the wrong definition"); } },
    {
      definitionId: "fixture",
      create(context) {
        const lifecycle = [];
        created.push({ context, lifecycle });
        return {
          prepare() { lifecycle.push("prepare"); },
          activate() { lifecycle.push("activate"); },
          drain() { lifecycle.push("drain"); },
          stop() { lifecycle.push("stop"); },
        };
      },
    },
  ],
};

function selectDefinition(module, assignment) {
  const matches = module.definitions.filter(definition =>
    definition.definitionId === assignment.definitionId);
  assert.equal(matches.length, 1, "assignment definition must match exactly once");
  return matches[0];
}

function unsupported() {
  throw new Error("this connection fixture does not implement a product runner");
}

async function instantiate(value) {
  return Promise.all(value.assignments.map(async assignment => {
    // Only public Assignment fields enter the factory. Each binding has its own capabilities.
    const context = Object.freeze({
      desiredEntryId: assignment.desiredEntryId,
      definitionId: assignment.definitionId,
      unitTargetRevision: assignment.unitTargetRevision,
      runtimeInstanceId: assignment.runtimeInstanceId,
      config: assignment.config,
      scope: { closed: false, child: unsupported, effect: unsupported,
        listen: unsupported, timer: unsupported },
      host: { call: unsupported },
    });
    const module = await selectDefinition(entryModule, assignment).create(context);
    await module.prepare?.();
    await module.activate?.();
    return module;
  }));
}

const firstSession = await instantiate(replaced);
const secondSession = await instantiate(reconnected);
assert.equal(created.length, 4);
assert.notEqual(firstSession[0], firstSession[1]);
assert.notEqual(firstSession[0], secondSession[0], "new sessions must create new modules");
assert.notEqual(firstSession[1], secondSession[1], "retained units must also rebind in a new session");
assert.notEqual(created[0].context.scope, created[1].context.scope);
assert.notEqual(created[0].context.host, created[1].context.host);
for (let index = 0; index < created.length; index++) {
  const assignment = replaced.assignments[index % 2];
  const { context, lifecycle } = created[index];
  assert.deepEqual({ ...context, scope: undefined, host: undefined }, {
    desiredEntryId: assignment.desiredEntryId,
    definitionId: assignment.definitionId,
    unitTargetRevision: assignment.unitTargetRevision,
    runtimeInstanceId: assignment.runtimeInstanceId,
    config: assignment.config,
    scope: undefined,
    host: undefined,
  });
  assert.deepEqual(lifecycle, ["prepare", "activate"]);
}
assert.throws(() => selectDefinition({ definitions: [] }, replaced.assignments[0]));
assert.throws(() => selectDefinition({ definitions: [entryModule.definitions[1],
  entryModule.definitions[1]] }, replaced.assignments[0]));
for (const module of [...firstSession, ...secondSession]) {
  await module.drain?.();
  await module.stop?.();
}
assert.ok(created.every(({ lifecycle }) =>
  lifecycle.join(",") === "prepare,activate,drain,stop"));
console.log("Engine current -> Java codec -> client protocol -> isolated client factories: passed");
